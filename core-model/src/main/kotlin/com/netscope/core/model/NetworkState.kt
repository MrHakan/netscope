package com.netscope.core.model

enum class TransportType(val label: String) {
    WIFI("Wi-Fi"),
    ETHERNET("Ethernet"),
    CELLULAR("Cellular"),
    VPN("VPN"),
    BLUETOOTH("Bluetooth"),
    USB("USB"),
    LOWPAN("LoWPAN"),
    UNKNOWN("Unknown"),
}

/** Whether the app is running unprivileged, with root, or as a device owner. */
enum class CapabilityLevel(val label: String) {
    NORMAL("Normal"),
    ROOT("Root"),
    MANAGED_DEVICE("Managed device"),
}

/** One Network object as ConnectivityManager describes it. */
data class NetworkSnapshot(
    val networkId: String,
    val interfaceName: Evidence<String>,
    val transports: List<TransportType>,
    val ipv4Addresses: List<Pair<Ipv4Address, Int>>,
    val ipv6Addresses: List<Pair<Ipv6Address, Int>>,
    val routes: List<RouteEntry>,
    val dnsServers: List<IpAddress>,
    val privateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val domains: String?,
    val mtu: Evidence<Int>,
    val httpProxy: String?,
    val hasValidatedInternet: Evidence<Boolean>,
    val isMetered: Evidence<Boolean>,
    val isDefaultNetwork: Boolean,
    val isVpn: Boolean,
    val linkDownstreamKbps: Int?,
    val linkUpstreamKbps: Int?,
    val observedAtEpochMillis: Long,
) {
    /** Gateway for the default route, when Android exposed one. */
    val ipv4Gateway: Ipv4Address?
        get() = routes.firstOrNull { it.isDefaultRoute && it.gateway is Ipv4Address }
            ?.gateway as? Ipv4Address

    /** The primary IPv4 subnet this network sits on. */
    val primaryIpv4Cidr: Ipv4Cidr?
        get() = ipv4Addresses.firstOrNull()?.let { (address, prefix) -> Ipv4Cidr.of(address, prefix) }

    val primaryIpv4: Ipv4Address? get() = ipv4Addresses.firstOrNull()?.first

    val transportLabel: String
        get() = if (transports.isEmpty()) TransportType.UNKNOWN.label else transports.joinToString(" + ") { it.label }
}

/** Wi-Fi details for the currently connected AP. */
data class WifiConnectionInfo(
    val ssid: Evidence<String>,
    val bssid: Evidence<String>,
    val rssiDbm: Evidence<Int>,
    val linkSpeedMbps: Evidence<Int>,
    val txLinkSpeedMbps: Evidence<Int>,
    val rxLinkSpeedMbps: Evidence<Int>,
    val frequencyMhz: Evidence<Int>,
    val wifiStandard: Evidence<String>,
    val securityType: Evidence<String>,
    val ownMacAddress: Evidence<MacAddress>,
    val observedAtEpochMillis: Long,
) {
    val band: WifiBand? get() = frequencyMhz.value?.let { WifiBand.forFrequency(it) }
    val channel: Int? get() = frequencyMhz.value?.let { channelForFrequency(it) }
}

enum class WifiBand(val label: String) {
    BAND_2_4("2.4 GHz"),
    BAND_5("5 GHz"),
    BAND_6("6 GHz"),
    BAND_60("60 GHz");

    companion object {
        fun forFrequency(mhz: Int): WifiBand? = when (mhz) {
            in 2400..2500 -> BAND_2_4
            in 4900..5900 -> BAND_5
            in 5925..7125 -> BAND_6
            in 57000..71000 -> BAND_60
            else -> null
        }
    }
}

/**
 * Converts a centre frequency to a channel number.
 *
 * Returns null rather than a guess for frequencies outside the defined plans.
 */
fun channelForFrequency(mhz: Int): Int? = when {
    mhz == 2484 -> 14
    mhz in 2412..2472 && (mhz - 2412) % 5 == 0 -> (mhz - 2412) / 5 + 1
    mhz in 5160..5885 && (mhz - 5000) % 5 == 0 -> (mhz - 5000) / 5
    mhz in 5955..7115 && (mhz - 5955) % 5 == 0 -> (mhz - 5955) / 5 + 1
    else -> null
}

/** One nearby access point from a Wi-Fi scan. */
data class WifiScanEntry(
    val ssid: String?,
    val isHidden: Boolean,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val centerFrequency0Mhz: Int,
    val centerFrequency1Mhz: Int,
    val channelWidthMhz: Int?,
    val securityCapabilities: String,
    val securityTypes: List<String> = emptyList(),
    val wifiStandard: String?,
    val observedAtEpochMillis: Long,
    val ageMillis: Long,
) {
    val band: WifiBand? get() = WifiBand.forFrequency(frequencyMhz)
    val channel: Int? get() = channelForFrequency(frequencyMhz)
    val signalQuality: SignalQuality get() = SignalQuality.forRssi(rssiDbm)
    val wpsSupported: Boolean
        get() = securityCapabilities.contains("[WPS]", ignoreCase = true)

    val cipherSummary: String
        get() = buildList {
            if (securityCapabilities.contains("CCMP", true) ||
                securityCapabilities.contains("AES", true)
            ) add("AES/CCMP")
            if (securityCapabilities.contains("TKIP", true)) add("TKIP")
            if (securityCapabilities.contains("GCMP", true)) add("GCMP")
        }.distinct().joinToString(" + ").ifBlank { "NOT DISCOVERED" }

    val securitySummary: String
        get() = securityTypes.joinToString(" + ").ifBlank {
            securityCapabilities.ifBlank { "NOT DISCOVERED" }
        }

    /** Lowest and highest frequency the AP occupies, for the overlap graph. */
    val occupiedRangeMhz: IntRange?
        get() {
            val width = channelWidthMhz ?: return null
            val center = if (centerFrequency0Mhz > 0) centerFrequency0Mhz else frequencyMhz
            return (center - width / 2)..(center + width / 2)
        }
}

/** Display-only classification. The raw dBm is always retained and shown alongside. */
enum class SignalQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    WEAK("Weak"),
    VERY_WEAK("Very weak");

    companion object {
        fun forRssi(dbm: Int): SignalQuality = when {
            dbm >= -50 -> EXCELLENT
            dbm >= -60 -> GOOD
            dbm >= -70 -> FAIR
            dbm >= -80 -> WEAK
            else -> VERY_WEAK
        }
    }
}

/** Why Wi-Fi scan results may be empty. Each maps to a specific UI message. */
sealed interface WifiScanAvailability {
    data object Available : WifiScanAvailability
    data class PermissionRequired(val permissions: List<String>) : WifiScanAvailability
    data object LocationServicesDisabled : WifiScanAvailability
    data object WifiDisabled : WifiScanAvailability
    data class Throttled(val retryAfterMillis: Long) : WifiScanAvailability
}
