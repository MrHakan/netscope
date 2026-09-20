package com.netscope.core.model

/** Best guess at what a device is. Always rendered with an INFERRED marker. */
enum class DeviceType {
    ROUTER, ACCESS_POINT, SWITCH, PHONE, TABLET, COMPUTER, SERVER,
    PRINTER, NAS, CAMERA, TV, IOT, THIS_DEVICE, UNKNOWN
}

/** Liveness. Deliberately has no OFFLINE member — silence is not proof of absence. */
enum class HostState {
    /** Something answered. */
    RESPONDING,

    /** We probed and nothing came back. Says nothing about whether the host exists. */
    NO_RESPONSE,

    /** Announced by mDNS/SSDP but not yet probed directly. */
    ANNOUNCED,

    /** Not probed in this scan. */
    NOT_PROBED,
}

/** Which probe produced a latency figure. Never omitted from the UI. */
enum class ProbeType {
    /** Real ICMP echo over an unprivileged datagram socket. */
    ICMP,

    /** TCP connect() round trip. Not a ping. */
    TCP_CONNECT,

    /** InetAddress.isReachable — may itself be a TCP connect. Labelled as a fallback. */
    FALLBACK,
}

/** A MAC address, only ever constructed from a real observation. */
data class MacAddress(val bytes: ByteArray) {
    init {
        require(bytes.size == 6) { "MAC needs 6 bytes, got ${bytes.size}" }
    }

    /** The 24-bit OUI prefix used for offline vendor lookup. */
    val oui: String
        get() = bytes.take(3).joinToString("") { "%02X".format(it) }

    /**
     * Locally administered addresses are usually randomised privacy MACs, so an OUI
     * lookup on them is meaningless and must not be attempted.
     */
    val isLocallyAdministered: Boolean get() = (bytes[0].toInt() and 0x02) != 0

    override fun toString(): String = bytes.joinToString(":") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean = other is MacAddress && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun parse(text: String): MacAddress? {
            val cleaned = text.trim().replace("-", ":").replace(".", ":")
            val parts = if (cleaned.contains(':')) cleaned.split(':') else cleaned.chunked(2)
            if (parts.size != 6) return null
            val out = ByteArray(6)
            for ((i, p) in parts.withIndex()) {
                if (p.length !in 1..2) return null
                out[i] = p.toIntOrNull(16)?.takeIf { it in 0..255 }?.toByte() ?: return null
            }
            return MacAddress(out)
        }
    }
}

/** A service found on a host, with the evidence that produced it. */
data class DiscoveredService(
    val port: Int?,
    val protocol: String,
    val name: String,
    val serviceType: String?,
    val detail: String?,
    val source: EvidenceSource,
    val confidence: Confidence,
    val observedAtEpochMillis: Long,
)

enum class PortState { OPEN, CLOSED, FILTERED_OR_TIMEOUT }

data class PortResult(val port: Int, val state: PortState, val latencyMillis: Long?, val banner: String?)

/**
 * A host on the network, assembled from every discovery source that saw it.
 *
 * Every field that could be guessed is an [Evidence] instead of a bare value.
 */
data class DiscoveredDevice(
    val ipv4: Ipv4Address?,
    val ipv6Addresses: List<Ipv6Address> = emptyList(),
    val hostname: Evidence<String> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val friendlyName: Evidence<String> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val mac: Evidence<MacAddress> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val vendor: Evidence<String> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val deviceType: Evidence<DeviceType> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val state: HostState = HostState.NOT_PROBED,
    val latencyMillis: Double? = null,
    val probeType: ProbeType? = null,
    val services: List<DiscoveredService> = emptyList(),
    val ports: List<PortResult> = emptyList(),
    val discoverySources: Set<EvidenceSource> = emptySet(),
    val firstSeenEpochMillis: Long = 0L,
    val lastSeenEpochMillis: Long = 0L,
    val isNew: Boolean = false,
    val isThisDevice: Boolean = false,
    val isGateway: Boolean = false,
    val userLabel: String? = null,
) {
    /** Stable identity across scans: IPv4 if present, else the first IPv6. */
    val key: String
        get() = ipv4?.toCanonicalString()
            ?: ipv6Addresses.firstOrNull()?.toCanonicalString()
            ?: "unknown"

    /** What to show as the primary line in a list. Never invented. */
    val displayName: String
        get() = userLabel
            ?: friendlyName.value
            ?: hostname.value
            ?: ipv4?.toCanonicalString()
            ?: ipv6Addresses.firstOrNull()?.toCanonicalString()
            ?: "unknown"
}

/**
 * Merges observations of the same host from different discovery sources.
 *
 * Conflicts are resolved by [EvidenceResolver], never by "last writer wins", so a
 * low-confidence mDNS name can't overwrite a user label or a reverse-DNS answer.
 */
object DeviceMerger {

    fun merge(a: DiscoveredDevice, b: DiscoveredDevice): DiscoveredDevice {
        require(a.key == b.key) { "Refusing to merge devices with different keys: ${a.key} vs ${b.key}" }
        return DiscoveredDevice(
            ipv4 = a.ipv4 ?: b.ipv4,
            ipv6Addresses = (a.ipv6Addresses + b.ipv6Addresses).distinct(),
            hostname = EvidenceResolver.resolve(listOf(a.hostname, b.hostname)),
            friendlyName = EvidenceResolver.resolve(listOf(a.friendlyName, b.friendlyName)),
            mac = EvidenceResolver.resolve(listOf(a.mac, b.mac)),
            vendor = EvidenceResolver.resolve(listOf(a.vendor, b.vendor)),
            deviceType = EvidenceResolver.resolve(listOf(a.deviceType, b.deviceType)),
            state = strongerState(a.state, b.state),
            // Keep the more trustworthy measurement rather than the newer one.
            latencyMillis = pickLatency(a, b)?.first,
            probeType = pickLatency(a, b)?.second,
            services = mergeServices(a.services + b.services),
            ports = (a.ports + b.ports).distinctBy { it.port },
            discoverySources = a.discoverySources + b.discoverySources,
            firstSeenEpochMillis = minOfNonZero(a.firstSeenEpochMillis, b.firstSeenEpochMillis),
            lastSeenEpochMillis = maxOf(a.lastSeenEpochMillis, b.lastSeenEpochMillis),
            isNew = a.isNew || b.isNew,
            isThisDevice = a.isThisDevice || b.isThisDevice,
            isGateway = a.isGateway || b.isGateway,
            userLabel = a.userLabel ?: b.userLabel,
        )
    }

    fun mergeAll(devices: List<DiscoveredDevice>): List<DiscoveredDevice> =
        devices.groupBy { it.key }
            .map { (_, group) -> group.reduce(::merge) }
            .sortedWith(compareBy(nullsLast()) { it.ipv4?.value })

    /** RESPONDING beats ANNOUNCED beats NO_RESPONSE beats NOT_PROBED. */
    private fun strongerState(a: HostState, b: HostState): HostState {
        val order = listOf(HostState.NOT_PROBED, HostState.NO_RESPONSE, HostState.ANNOUNCED, HostState.RESPONDING)
        return if (order.indexOf(a) >= order.indexOf(b)) a else b
    }

    /** An ICMP figure is preferred over a TCP-connect figure over a fallback. */
    private fun pickLatency(a: DiscoveredDevice, b: DiscoveredDevice): Pair<Double, ProbeType>? {
        val candidates = listOfNotNull(
            a.latencyMillis?.let { l -> a.probeType?.let { l to it } },
            b.latencyMillis?.let { l -> b.probeType?.let { l to it } },
        )
        return candidates.minByOrNull { (_, type) ->
            when (type) {
                ProbeType.ICMP -> 0
                ProbeType.TCP_CONNECT -> 1
                ProbeType.FALLBACK -> 2
            }
        }
    }

    private fun mergeServices(services: List<DiscoveredService>): List<DiscoveredService> =
        services.distinctBy { listOf(it.port, it.protocol, it.name, it.serviceType) }
            .sortedWith(compareBy(nullsLast()) { it.port })

    private fun minOfNonZero(a: Long, b: Long): Long = when {
        a == 0L -> b
        b == 0L -> a
        else -> minOf(a, b)
    }
}
