package com.netscope.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.DeviceType
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.ScanProfile
import com.netscope.core.network.NetworkInspector
import com.netscope.data.ScanController
import com.netscope.data.ScanState
import com.netscope.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A filter the user can apply to the device list. */
enum class DeviceFilter(val label: String) {
    ALL("All"),
    RESPONDING("Responding"),
    NEW("New"),
    ROUTER("Router"),
    SERVER("Server"),
    PRINTER("Printer"),
    IOT("IoT"),
    UNKNOWN("Unidentified"),
}

/** Asked before a scan that would probe a very large number of addresses. */
data class LargeScanConfirmation(val target: Ipv4Cidr, val hostCount: Int, val profile: ScanProfile)

data class DevicesUiState(
    val scan: ScanState = ScanState(),
    val target: String = "",
    val targetError: String? = null,
    val query: String = "",
    val filter: DeviceFilter = DeviceFilter.ALL,
    val scanProfile: ScanProfile = ScanProfile.SMART,
    val pendingConfirmation: LargeScanConfirmation? = null,
    val scopeWarning: String? = null,
) {
    /** The list actually rendered, after search and filtering. */
    val visibleDevices: List<DiscoveredDevice>
        get() = scan.devices
            .filter { device -> matchesFilter(device, filter) }
            .filter { device -> matchesQuery(device, query) }
}

private fun matchesFilter(device: DiscoveredDevice, filter: DeviceFilter): Boolean = when (filter) {
    DeviceFilter.ALL -> true
    DeviceFilter.RESPONDING -> device.state == com.netscope.core.model.HostState.RESPONDING
    DeviceFilter.NEW -> device.isNew
    DeviceFilter.ROUTER -> device.deviceType.value == DeviceType.ROUTER ||
        device.deviceType.value == DeviceType.ACCESS_POINT
    DeviceFilter.SERVER -> device.deviceType.value == DeviceType.SERVER ||
        device.deviceType.value == DeviceType.NAS
    DeviceFilter.PRINTER -> device.deviceType.value == DeviceType.PRINTER
    DeviceFilter.IOT -> device.deviceType.value == DeviceType.IOT ||
        device.deviceType.value == DeviceType.CAMERA
    DeviceFilter.UNKNOWN -> device.deviceType.value == null ||
        device.deviceType.value == DeviceType.UNKNOWN
}

private fun matchesQuery(device: DiscoveredDevice, query: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return listOfNotNull(
        device.ipv4?.toCanonicalString(),
        device.hostname.value,
        device.friendlyName.value,
        device.userLabel,
        device.vendor.value,
        device.mac.value?.toString(),
        device.deviceType.value?.name,
    ).any { it.lowercase().contains(needle) } ||
        device.services.any { service ->
            service.name.lowercase().contains(needle) || service.port?.toString() == needle
        }
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val scanController: ScanController,
    private val networkInspector: NetworkInspector,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val target = MutableStateFlow("")
    private val targetError = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DeviceFilter.ALL)
    private val pendingConfirmation = MutableStateFlow<LargeScanConfirmation?>(null)
    private val scopeWarning = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DevicesUiState> = combine(
        scanController.state,
        combine(target, targetError) { t, e -> t to e },
        combine(query, filter) { q, f -> q to f },
        settingsRepository.settings,
        combine(pendingConfirmation, scopeWarning) { c, w -> c to w },
    ) { scan, (targetText, error), (queryText, activeFilter), settings, (confirmation, warning) ->
        DevicesUiState(
            scan = scan,
            target = targetText,
            targetError = error,
            query = queryText,
            filter = activeFilter,
            scanProfile = settings.scanProfile,
            pendingConfirmation = confirmation,
            scopeWarning = warning,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    init {
        // Default the target to the subnet this device is actually on.
        viewModelScope.launch {
            val network = withContext(Dispatchers.IO) { networkInspector.activeSnapshot() }
            network?.primaryIpv4Cidr?.let { target.value = it.toString() }
        }
    }

    fun setTarget(value: String) {
        target.value = value
        targetError.value = null
        scopeWarning.value = null
    }

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: DeviceFilter) { filter.value = value }

    fun setScanProfile(profile: ScanProfile) {
        viewModelScope.launch { settingsRepository.setScanProfile(profile) }
    }

    /**
     * Validates the target and either starts the scan or asks for confirmation.
     *
     * Two things are always checked first: whether the range is enormous, and whether it
     * lies outside private address space.
     */
    fun startScan() {
        viewModelScope.launch {
            val cidr = Ipv4Cidr.parse(target.value.trim())
            if (cidr == null) {
                targetError.value = "Enter a target as an address and prefix, for example 10.0.2.0/24."
                return@launch
            }

            val profile = uiState.value.scanProfile

            if (!cidr.networkAddress.isPrivateScope) {
                scopeWarning.value = "$cidr is outside private address space (RFC 1918, RFC 6598 and " +
                    "link-local). Scanning addresses you are not responsible for may be unwelcome " +
                    "and may be unlawful where you are. Confirm that you are authorised to scan " +
                    "this range."
                return@launch
            }

            if (cidr.prefixLength <= LARGE_SCAN_PREFIX) {
                pendingConfirmation.value = LargeScanConfirmation(
                    target = cidr,
                    hostCount = cidr.usableHostCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    profile = profile,
                )
                return@launch
            }

            launchScan(cidr, profile)
        }
    }

    /** Proceeds after the user confirmed a large range or a non-private scope. */
    fun confirmPendingScan() {
        val confirmation = pendingConfirmation.value
        if (confirmation != null) {
            pendingConfirmation.value = null
            launchScan(confirmation.target, confirmation.profile)
            return
        }
        val cidr = Ipv4Cidr.parse(target.value.trim()) ?: return
        scopeWarning.value = null
        launchScan(cidr, uiState.value.scanProfile)
    }

    fun dismissConfirmation() {
        pendingConfirmation.value = null
        scopeWarning.value = null
    }

    private fun launchScan(cidr: Ipv4Cidr, profile: ScanProfile) {
        viewModelScope.launch {
            // Read the current settings once; collecting the flow here would never return.
            val settings = settingsRepository.settings.first()
            scanController.start(cidr, profile, settings.performanceProfile, settings.demoModeEnabled)
        }
    }

    fun stopScan() = scanController.stop()

    companion object {
        /** A /16 is 65,534 hosts; anything this wide needs an explicit confirmation. */
        const val LARGE_SCAN_PREFIX = 16
    }
}
