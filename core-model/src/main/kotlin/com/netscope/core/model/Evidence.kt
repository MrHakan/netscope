package com.netscope.core.model

/**
 * Where a piece of information came from.
 *
 * The UI renders this next to every value, so the user can always tell an observation
 * from an inference and an inference from a platform restriction.
 */
enum class EvidenceSource {
    /** Read from [android.net.LinkProperties] for a specific Network. */
    LINK_PROPERTIES,

    /** Read from [android.net.NetworkCapabilities]. */
    NETWORK_CAPABILITIES,

    /** Read from java.net.NetworkInterface (Linux view, not the Android Network view). */
    NETWORK_INTERFACE,

    /** Read from WifiManager / WifiInfo / ScanResult. */
    WIFI_MANAGER,

    /** A TCP connect() succeeded or was refused, which proves the host exists. */
    TCP_PROBE,

    /** An unprivileged ICMP datagram socket produced an echo reply. */
    ICMP_PROBE,

    /** Forward DNS lookup. */
    DNS,

    /** Reverse DNS (PTR) lookup. */
    DNS_PTR,

    /** Multicast DNS / Bonjour service advertisement. */
    MDNS,

    /** SSDP / UPnP discovery response or device description. */
    SSDP,

    /** NetBIOS name service response. */
    NETBIOS,

    /** Link-Local Multicast Name Resolution. */
    LLMNR,

    /** IEEE OUI prefix of an observed MAC address, resolved offline. */
    OUI,

    /** Derived by combining other evidence. Always renders with an INFERRED marker. */
    INFERENCE,

    /** Entered or renamed by the user. */
    USER,

    /** Fabricated demo data. Only ever produced in demo mode, always marked as such. */
    DEMO,

    /** Nothing produced this value. */
    NONE,
}

/** How much the app trusts a value. Never upgraded without new evidence. */
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

/**
 * Why a value is absent. Rendered verbatim instead of a blank or a plausible guess.
 */
enum class Unavailability {
    /** We looked and nothing answered. */
    NOT_DISCOVERED,

    /** The platform refuses to expose this to third-party apps. */
    RESTRICTED_BY_ANDROID,

    /** The field has no meaning for this object (e.g. RSSI on Ethernet). */
    NOT_APPLICABLE,

    /** A runtime permission is missing. */
    PERMISSION_REQUIRED,

    /** Location services are off system-wide, which blanks Wi-Fi scan results. */
    LOCATION_SERVICES_REQUIRED,

    /** Root / Device Owner capability would be required. */
    ELEVATED_CAPABILITY_REQUIRED,

    /** We have not attempted to determine this yet. */
    NOT_ATTEMPTED,
}

/**
 * A single discovered property together with everything needed to justify it in the UI.
 *
 * [value] is null exactly when the property is unavailable, and then [unavailability]
 * explains why. There is deliberately no "empty string means unknown" convention.
 */
data class Evidence<out T>(
    val value: T?,
    val source: EvidenceSource,
    val confidence: Confidence,
    val observedAtEpochMillis: Long,
    val detail: String? = null,
    val unavailability: Unavailability? = null,
) {
    val isPresent: Boolean get() = value != null

    /** True when the value was not directly observed and must carry an INFERRED marker. */
    val isInferred: Boolean get() = source == EvidenceSource.INFERENCE

    companion object {
        fun <T> observed(
            value: T,
            source: EvidenceSource,
            confidence: Confidence = Confidence.HIGH,
            observedAtEpochMillis: Long,
            detail: String? = null,
        ): Evidence<T> = Evidence(value, source, confidence, observedAtEpochMillis, detail)

        fun <T> inferred(
            value: T,
            confidence: Confidence,
            observedAtEpochMillis: Long,
            detail: String,
        ): Evidence<T> =
            Evidence(value, EvidenceSource.INFERENCE, confidence, observedAtEpochMillis, detail)

        fun <T> unavailable(
            reason: Unavailability,
            observedAtEpochMillis: Long = 0L,
            detail: String? = null,
        ): Evidence<T> =
            Evidence(null, EvidenceSource.NONE, Confidence.UNKNOWN, observedAtEpochMillis, detail, reason)
    }
}

/**
 * Resolves conflicting evidence for the same property.
 *
 * Rules, in order:
 *  1. A user-entered value always wins — the human is the authority on naming.
 *  2. Otherwise the highest confidence wins.
 *  3. Ties are broken by source rank (a direct observation beats an inference).
 *  4. Remaining ties are broken by recency.
 *
 * Returns an unavailable Evidence when the input is empty.
 */
object EvidenceResolver {

    /** Lower rank is more trustworthy. */
    private fun rank(source: EvidenceSource): Int = when (source) {
        EvidenceSource.USER -> 0
        EvidenceSource.LINK_PROPERTIES, EvidenceSource.NETWORK_CAPABILITIES -> 1
        EvidenceSource.WIFI_MANAGER -> 2
        EvidenceSource.ICMP_PROBE, EvidenceSource.TCP_PROBE -> 3
        EvidenceSource.MDNS, EvidenceSource.SSDP -> 4
        EvidenceSource.DNS_PTR, EvidenceSource.DNS -> 5
        EvidenceSource.NETBIOS, EvidenceSource.LLMNR -> 6
        EvidenceSource.NETWORK_INTERFACE -> 7
        EvidenceSource.OUI -> 8
        EvidenceSource.INFERENCE -> 9
        EvidenceSource.DEMO -> 10
        EvidenceSource.NONE -> 11
    }

    private fun confidenceRank(confidence: Confidence): Int = when (confidence) {
        Confidence.HIGH -> 0
        Confidence.MEDIUM -> 1
        Confidence.LOW -> 2
        Confidence.UNKNOWN -> 3
    }

    fun <T> resolve(candidates: List<Evidence<T>>): Evidence<T> {
        val present = candidates.filter { it.isPresent }
        if (present.isEmpty()) {
            // Prefer the most specific explanation of absence over a generic one.
            return candidates.minByOrNull { unavailabilityRank(it.unavailability) }
                ?: Evidence.unavailable(Unavailability.NOT_ATTEMPTED)
        }
        present.firstOrNull { it.source == EvidenceSource.USER }?.let { return it }
        return present.sortedWith(
            compareBy<Evidence<T>> { confidenceRank(it.confidence) }
                .thenBy { rank(it.source) }
                .thenByDescending { it.observedAtEpochMillis },
        ).first()
    }

    private fun unavailabilityRank(unavailability: Unavailability?): Int = when (unavailability) {
        Unavailability.PERMISSION_REQUIRED -> 0
        Unavailability.LOCATION_SERVICES_REQUIRED -> 1
        Unavailability.RESTRICTED_BY_ANDROID -> 2
        Unavailability.ELEVATED_CAPABILITY_REQUIRED -> 3
        Unavailability.NOT_APPLICABLE -> 4
        Unavailability.NOT_DISCOVERED -> 5
        Unavailability.NOT_ATTEMPTED -> 6
        null -> 7
    }
}
