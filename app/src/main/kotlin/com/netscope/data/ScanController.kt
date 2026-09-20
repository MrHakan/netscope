package com.netscope.data

import com.netscope.core.database.HistoryRepository
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.model.ScanComparator
import com.netscope.core.model.ScanDiff
import com.netscope.core.model.ScanMetadata
import com.netscope.core.model.ScanPhase
import com.netscope.core.model.ScanProfile
import com.netscope.core.model.ScanProgress
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.PermissionInspector
import com.netscope.core.network.ScanEngine
import com.netscope.core.network.WifiInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the scan screen needs to render one run. */
data class ScanState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val progress: ScanProgress = ScanProgress(
        phase = ScanPhase.NETWORK_ENUMERATION,
        target = "",
        probed = 0,
        total = 0,
        found = 0,
        isRunning = false,
        startedAtEpochMillis = 0,
    ),
    val diff: ScanDiff? = null,
    val metadata: ScanMetadata? = null,
    val error: String? = null,
    val isDemoData: Boolean = false,
)

/**
 * Owns the lifetime of a scan.
 *
 * The scan runs in a scope this class owns rather than a ViewModel's, so it survives a
 * rotation, and STOP cancels that scope — which is what makes cancellation immediate
 * and releases every socket and the multicast lock through the engine's finally blocks.
 */
@Singleton
class ScanController @Inject constructor(
    private val scanEngine: ScanEngine,
    private val networkInspector: NetworkInspector,
    private val wifiInspector: WifiInspector,
    private val historyRepository: HistoryRepository,
    private val permissionInspector: PermissionInspector,
    private val demoDataSource: DemoDataSource,
    private val ouiRepository: OuiRepository,
) {

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()
    private var currentJob: Job? = null

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    val isRunning: Boolean get() = currentJob?.isActive == true

    /**
     * Starts a scan of [target].
     *
     * Callers are expected to have confirmed large ranges already; [addressCountFor]
     * exists so the UI can show the real host count in that confirmation.
     */
    fun start(
        target: Ipv4Cidr,
        profile: ScanProfile,
        performanceProfile: PerformanceProfile,
        demoMode: Boolean,
    ) {
        scope.launch {
            mutex.withLock {
                currentJob?.cancelAndJoin()
                currentJob = scope.launch { runScan(target, profile, performanceProfile, demoMode) }
            }
        }
    }

    /** Cancels the running scan. The engine's structured concurrency does the rest. */
    fun stop() {
        scope.launch {
            currentJob?.cancelAndJoin()
            currentJob = null
            scanEngine.markCancelled()
            _state.value = _state.value.copy(progress = scanEngine.progress.value)
        }
    }

    fun addressCountFor(target: Ipv4Cidr, profile: ScanProfile): Int {
        val network = networkInspector.activeSnapshot()
        return scanEngine.plannedAddressCount(
            target = target,
            profile = profile,
            localAddress = network?.primaryIpv4,
            gateway = network?.ipv4Gateway,
            previouslySeen = emptySet(),
        )
    }

    private suspend fun runScan(
        target: Ipv4Cidr,
        profile: ScanProfile,
        performanceProfile: PerformanceProfile,
        demoMode: Boolean,
    ) {
        if (demoMode) {
            runDemoScan(target)
            return
        }

        if (permissionInspector.localNetworkAccessBlocked()) {
            _state.value = _state.value.copy(
                error = "This version of Android requires the local network access permission " +
                    "before an app can reach other devices on your network. Grant it in Settings " +
                    "and run the scan again. Without it a scan returns nothing, which would look " +
                    "identical to an empty network.",
                progress = _state.value.progress.copy(isRunning = false),
            )
            return
        }

        val network = networkInspector.activeSnapshot()
        val startedAt = System.currentTimeMillis()

        val wifi = runCatching { wifiInspector.connectionInfo() }.getOrNull()
        val profileId = network?.let {
            runCatching {
                historyRepository.profileFor(it, wifi?.ssid?.value, wifi?.bssid?.value)
            }.getOrNull()
        }
        val previouslySeen = profileId?.let {
            runCatching { historyRepository.knownDeviceKeys(it) }.getOrNull()
        }.orEmpty()
        val previousDevices = emptyList<DiscoveredDevice>()

        _state.value = ScanState(
            devices = emptyList(),
            progress = scanEngine.progress.value.copy(isRunning = true, target = target.toString()),
        )

        val collector = scope.launch {
            scanEngine.updates.collect { update ->
                val current = _state.value.devices.filterNot { it.key == update.device.key }
                _state.value = _state.value.copy(
                    devices = (current + update.device).sortedBy { it.ipv4?.value ?: Long.MAX_VALUE },
                    progress = scanEngine.progress.value,
                )
            }
        }

        try {
            val boundAddress = network?.primaryIpv4
                ?.let { runCatching { InetAddress.getByAddress(it.bytes) }.getOrNull() }

            val devices = scanEngine.scan(
                target = target,
                profile = profile,
                performanceProfile = performanceProfile,
                localAddress = network?.primaryIpv4,
                gateway = network?.ipv4Gateway,
                previouslySeen = previouslySeen,
                boundInterfaceAddress = boundAddress,
            )

            val enriched = enrichWithVendors(devices)

            val metadata = ScanMetadata(
                scanId = 0,
                target = target.toString(),
                profile = profile,
                performanceProfile = performanceProfile,
                startedAtEpochMillis = startedAt,
                durationMillis = System.currentTimeMillis() - startedAt,
                addressesProbed = scanEngine.progress.value.probed,
                devicesFound = enriched.size,
                peakConcurrency = scanEngine.observedPeakConcurrency,
                icmpAvailable = scanEngine.progress.value.message?.contains("ICMP echo") == true,
                completed = true,
            )

            if (profileId != null) {
                runCatching {
                    historyRepository.recordScan(profileId, metadata, enriched, network?.routes.orEmpty())
                }
            }

            _state.value = _state.value.copy(
                devices = enriched,
                progress = scanEngine.progress.value,
                diff = ScanComparator.compare(previousDevices, enriched),
                metadata = metadata,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = friendlyError(e),
                progress = _state.value.progress.copy(isRunning = false),
            )
        } finally {
            collector.cancel()
        }
    }

    /** Fills in vendors for the few devices that volunteered a MAC address. */
    private suspend fun enrichWithVendors(devices: List<DiscoveredDevice>): List<DiscoveredDevice> {
        if (devices.none { it.mac.value != null }) return devices
        val lookup = ouiRepository.lookup()
        return devices.map { device ->
            val mac = device.mac.value ?: return@map device
            device.copy(vendor = lookup.vendorFor(mac, device.lastSeenEpochMillis))
        }
    }

    private fun runDemoScan(target: Ipv4Cidr) {
        val devices = demoDataSource.devices().filter { device ->
            device.ipv4?.let { it in target } ?: false
        }
        _state.value = ScanState(
            devices = devices,
            progress = ScanProgress(
                phase = ScanPhase.DATABASE_COMPARISON,
                target = target.toString(),
                probed = target.usableHostCount.toInt().coerceAtMost(254),
                total = target.usableHostCount.toInt().coerceAtMost(254),
                found = devices.size,
                isRunning = false,
                startedAtEpochMillis = System.currentTimeMillis(),
                finishedAtEpochMillis = System.currentTimeMillis(),
                message = "Demo mode: this is fabricated data, not a real scan.",
            ),
            metadata = ScanMetadata(
                scanId = 0,
                target = target.toString(),
                profile = ScanProfile.SMART,
                performanceProfile = PerformanceProfile.BALANCED,
                startedAtEpochMillis = System.currentTimeMillis(),
                durationMillis = 0,
                addressesProbed = 0,
                devicesFound = devices.size,
                peakConcurrency = 0,
                icmpAvailable = false,
                completed = true,
                isDemoData = true,
            ),
            isDemoData = true,
        )
    }

    /** Turns an exception into something a user can act on. Stack traces never surface. */
    private fun friendlyError(e: Exception): String = when (e) {
        is SecurityException ->
            "Android refused this operation. A permission the scan needs has not been granted."
        is java.net.SocketException ->
            "A socket could not be opened. The network may have changed while the scan was running."
        is java.net.UnknownHostException ->
            "A name could not be resolved. Check the DNS settings for this network."
        else -> "The scan stopped unexpectedly: ${e.message ?: e::class.simpleName ?: "unknown error"}."
    }
}
