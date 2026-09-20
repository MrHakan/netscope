package com.netscope.core.model

/** The phases of a scan. Each is independently cancellable and reported separately. */
enum class ScanPhase(val label: String) {
    NETWORK_ENUMERATION("Network enumeration"),
    ROUTE_ANALYSIS("Route analysis"),
    HOST_DISCOVERY("Host discovery"),
    NAME_RESOLUTION("Name resolution"),
    SERVICE_DISCOVERY("Service discovery"),
    FINGERPRINTING("Fingerprinting"),
    DATABASE_COMPARISON("Comparison with history"),
}

/**
 * How aggressive a scan is.
 *
 * SMART is the default: it probes the addresses most likely to answer first, so the
 * useful results arrive in the first second rather than the last.
 */
enum class ScanProfile(val label: String, val description: String) {
    QUICK(
        "Quick",
        "Gateway, this device, and addresses seen in previous scans. Fastest, least complete.",
    ),
    SMART(
        "Smart",
        "Prioritises the gateway, DHCP-typical ranges and previously seen hosts, then sweeps the rest.",
    ),
    FULL(
        "Full",
        "Every usable address in the range, in order.",
    ),
    CUSTOM(
        "Custom range",
        "A range you specify.",
    ),
}

/** Performance envelope. Bounds concurrency and per-probe timeouts. */
enum class PerformanceProfile(
    val label: String,
    val maxConcurrency: Int,
    val probeTimeoutMillis: Long,
) {
    FAST("Fast", maxConcurrency = 64, probeTimeoutMillis = 600),
    BALANCED("Balanced", maxConcurrency = 32, probeTimeoutMillis = 1000),
    BATTERY_SAVER("Battery saver", maxConcurrency = 8, probeTimeoutMillis = 1500),
}

/** Live progress for the scan screen. Streams continuously; never a single batch. */
data class ScanProgress(
    val phase: ScanPhase,
    val target: String,
    val probed: Int,
    val total: Int,
    val found: Int,
    val isRunning: Boolean,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val cancelled: Boolean = false,
    val message: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (probed.toFloat() / total).coerceIn(0f, 1f)
}

/** What a scan actually covered, exported alongside the results. */
data class ScanMetadata(
    val scanId: Long,
    val target: String,
    val profile: ScanProfile,
    val performanceProfile: PerformanceProfile,
    val startedAtEpochMillis: Long,
    val durationMillis: Long,
    val addressesProbed: Int,
    val devicesFound: Int,
    val peakConcurrency: Int,
    val icmpAvailable: Boolean,
    val completed: Boolean,
    val isDemoData: Boolean = false,
)

/** What changed between two scans of the same network. */
data class ScanDiff(
    val newDevices: List<DiscoveredDevice>,
    val disappearedDevices: List<DiscoveredDevice>,
    val addressChanged: List<DeviceChange>,
    val hostnameChanged: List<DeviceChange>,
    val servicesChanged: List<DeviceChange>,
) {
    val hasChanges: Boolean
        get() = newDevices.isNotEmpty() || disappearedDevices.isNotEmpty() ||
            addressChanged.isNotEmpty() || hostnameChanged.isNotEmpty() || servicesChanged.isNotEmpty()
}

data class DeviceChange(val device: DiscoveredDevice, val before: String?, val after: String?)

/**
 * Compares two scans.
 *
 * A device present in [previous] but not in [current] is reported as "not seen in this
 * scan", never as offline: it may simply not have answered.
 */
object ScanComparator {

    fun compare(previous: List<DiscoveredDevice>, current: List<DiscoveredDevice>): ScanDiff {
        val previousByKey = previous.associateBy { it.key }
        val currentByKey = current.associateBy { it.key }

        val newDevices = current.filter { it.key !in previousByKey }
        val disappeared = previous.filter { it.key !in currentByKey }

        val hostnameChanged = mutableListOf<DeviceChange>()
        val servicesChanged = mutableListOf<DeviceChange>()

        for (device in current) {
            val before = previousByKey[device.key] ?: continue
            if (before.hostname.value != device.hostname.value) {
                hostnameChanged += DeviceChange(device, before.hostname.value, device.hostname.value)
            }
            val beforeServices = before.services.map { it.name }.toSortedSet()
            val afterServices = device.services.map { it.name }.toSortedSet()
            if (beforeServices != afterServices) {
                servicesChanged += DeviceChange(
                    device,
                    beforeServices.joinToString(", ").ifEmpty { null },
                    afterServices.joinToString(", ").ifEmpty { null },
                )
            }
        }

        // An IP change is only detectable when the MAC stayed the same, because the IP
        // is otherwise the identity. Without a MAC this is genuinely not knowable.
        val addressChanged = mutableListOf<DeviceChange>()
        val previousByMac = previous.mapNotNull { d -> d.mac.value?.let { it to d } }.toMap()
        for (device in current) {
            val mac = device.mac.value ?: continue
            val before = previousByMac[mac] ?: continue
            if (before.key != device.key) {
                addressChanged += DeviceChange(device, before.key, device.key)
            }
        }

        return ScanDiff(newDevices, disappeared, addressChanged, hostnameChanged, servicesChanged)
    }
}
