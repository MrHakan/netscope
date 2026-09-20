package com.netscope.core.model

/**
 * Infers a device type from whatever evidence exists.
 *
 * Every result carries a confidence and a justification; the caller renders it with an
 * INFERRED marker. Returning UNKNOWN is a perfectly good answer and is preferred over
 * a plausible-sounding guess.
 */
object DeviceTypeInference {

    /** mDNS service types that identify a class of device with reasonable reliability. */
    private val mdnsTypeHints: List<Pair<String, DeviceType>> = listOf(
        "_ipp._tcp" to DeviceType.PRINTER,
        "_ipps._tcp" to DeviceType.PRINTER,
        "_printer._tcp" to DeviceType.PRINTER,
        "_pdl-datastream._tcp" to DeviceType.PRINTER,
        "_scanner._tcp" to DeviceType.PRINTER,
        "_smb._tcp" to DeviceType.NAS,
        "_afpovertcp._tcp" to DeviceType.NAS,
        "_nfs._tcp" to DeviceType.NAS,
        "_airplay._tcp" to DeviceType.TV,
        "_googlecast._tcp" to DeviceType.TV,
        "_raop._tcp" to DeviceType.TV,
        "_rtsp._tcp" to DeviceType.CAMERA,
        "_axis-video._tcp" to DeviceType.CAMERA,
        "_ssh._tcp" to DeviceType.COMPUTER,
        "_workstation._tcp" to DeviceType.COMPUTER,
        "_companion-link._tcp" to DeviceType.PHONE,
        "_homekit._tcp" to DeviceType.IOT,
        "_hap._tcp" to DeviceType.IOT,
    )

    /** Open ports that suggest a role. Weak evidence on their own. */
    private val portHints: List<Pair<Int, DeviceType>> = listOf(
        631 to DeviceType.PRINTER,
        9100 to DeviceType.PRINTER,
        554 to DeviceType.CAMERA,
        445 to DeviceType.NAS,
        2049 to DeviceType.NAS,
        3389 to DeviceType.COMPUTER,
        22 to DeviceType.SERVER,
    )

    private val hostnameHints: List<Pair<Regex, DeviceType>> = listOf(
        Regex("(?i)(router|gateway|openwrt|mikrotik|edgerouter|fritz)") to DeviceType.ROUTER,
        Regex("(?i)(unifi|^ap[-_]|accesspoint|aruba)") to DeviceType.ACCESS_POINT,
        Regex("(?i)(switch|sw[-_]?\\d)") to DeviceType.SWITCH,
        Regex("(?i)(iphone|android|pixel|galaxy|oneplus|xiaomi)") to DeviceType.PHONE,
        Regex("(?i)(ipad|tablet)") to DeviceType.TABLET,
        Regex("(?i)(printer|epson|brother|canon|hp[-_]?laser|officejet)") to DeviceType.PRINTER,
        Regex("(?i)(synology|qnap|diskstation|truenas|freenas|nas)") to DeviceType.NAS,
        Regex("(?i)(camera|ipcam|hikvision|dahua|reolink)") to DeviceType.CAMERA,
        Regex("(?i)(tv|bravia|roku|chromecast|firetv|shield)") to DeviceType.TV,
        Regex("(?i)(server|srv|esxi|proxmox)") to DeviceType.SERVER,
        Regex("(?i)(desktop|laptop|macbook|imac|pc[-_])") to DeviceType.COMPUTER,
    )

    /**
     * Combines every available signal.
     *
     * Confidence rises only when independent sources agree; a single weak hint never
     * produces HIGH confidence.
     */
    fun infer(
        isGateway: Boolean,
        isThisDevice: Boolean,
        hostname: String?,
        mdnsServiceTypes: List<String>,
        openPorts: List<Int>,
        upnpDeviceType: String?,
        vendor: String?,
        observedAtEpochMillis: Long,
    ): Evidence<DeviceType> {
        if (isThisDevice) {
            return Evidence.observed(
                value = DeviceType.THIS_DEVICE,
                source = EvidenceSource.LINK_PROPERTIES,
                confidence = Confidence.HIGH,
                observedAtEpochMillis = observedAtEpochMillis,
                detail = "This is the device running NetScope.",
            )
        }
        if (isGateway) {
            return Evidence.inferred(
                value = DeviceType.ROUTER,
                confidence = Confidence.HIGH,
                observedAtEpochMillis = observedAtEpochMillis,
                detail = "This address is the default gateway for the active network.",
            )
        }

        val hits = mutableListOf<Pair<DeviceType, String>>()

        upnpDeviceType?.let { type ->
            val mapped = when {
                type.contains("InternetGatewayDevice", ignoreCase = true) -> DeviceType.ROUTER
                type.contains("Printer", ignoreCase = true) -> DeviceType.PRINTER
                type.contains("MediaRenderer", ignoreCase = true) -> DeviceType.TV
                type.contains("MediaServer", ignoreCase = true) -> DeviceType.NAS
                else -> null
            }
            if (mapped != null) hits += mapped to "UPnP device type \"$type\""
        }

        for (serviceType in mdnsServiceTypes) {
            mdnsTypeHints.firstOrNull { serviceType.contains(it.first, ignoreCase = true) }
                ?.let { hits += it.second to "mDNS service ${it.first}" }
        }

        if (hostname != null) {
            hostnameHints.firstOrNull { it.first.containsMatchIn(hostname) }
                ?.let { hits += it.second to "hostname \"$hostname\"" }
        }

        if (vendor != null) {
            hostnameHints.firstOrNull { it.first.containsMatchIn(vendor) }
                ?.let { hits += it.second to "vendor \"$vendor\"" }
        }

        for (port in openPorts) {
            portHints.firstOrNull { it.first == port }
                ?.let { hits += it.second to "open port ${it.first}" }
        }

        if (hits.isEmpty()) {
            return Evidence.unavailable(
                reason = Unavailability.NOT_DISCOVERED,
                observedAtEpochMillis = observedAtEpochMillis,
                detail = "No hostname, service or vendor evidence identified this device.",
            )
        }

        // The type backed by the most independent signals wins.
        val grouped = hits.groupBy { it.first }
        val best = grouped.maxByOrNull { it.value.size }!!
        val supporting = best.value.map { it.second }.distinct()
        val confidence = when {
            supporting.size >= 3 -> Confidence.HIGH
            supporting.size == 2 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return Evidence.inferred(
            value = best.key,
            confidence = confidence,
            observedAtEpochMillis = observedAtEpochMillis,
            detail = "Inferred from " + supporting.joinToString(", ") + ".",
        )
    }
}

/**
 * Offline IEEE OUI lookup.
 *
 * The table is supplied by the caller so it can be updated independently of the app,
 * and a randomised (locally administered) MAC is never looked up at all.
 */
class OuiLookup(private val table: Map<String, String>) {

    fun vendorFor(mac: MacAddress, observedAtEpochMillis: Long): Evidence<String> {
        if (mac.isLocallyAdministered) {
            return Evidence.unavailable(
                reason = Unavailability.NOT_APPLICABLE,
                observedAtEpochMillis = observedAtEpochMillis,
                detail = "This is a locally administered (randomised) MAC address, so it carries " +
                    "no manufacturer information.",
            )
        }
        val vendor = table[mac.oui]
            ?: return Evidence.unavailable(
                reason = Unavailability.NOT_DISCOVERED,
                observedAtEpochMillis = observedAtEpochMillis,
                detail = "OUI ${mac.oui} is not in the offline database.",
            )
        return Evidence.observed(
            value = vendor,
            source = EvidenceSource.OUI,
            confidence = Confidence.HIGH,
            observedAtEpochMillis = observedAtEpochMillis,
            detail = "IEEE OUI ${mac.oui} is registered to $vendor.",
        )
    }

    val size: Int get() = table.size

    companion object {
        /** Parses the bundled "AABBCC\tVendor" table. Blank and comment lines are skipped. */
        fun parse(lines: Sequence<String>): OuiLookup {
            val table = mutableMapOf<String, String>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val prefix = line.substring(0, tab).trim().uppercase()
                val vendor = line.substring(tab + 1).trim()
                if (prefix.length == 6 && vendor.isNotEmpty()) table[prefix] = vendor
            }
            return OuiLookup(table)
        }
    }
}
