package com.netscope.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.DiscoveredDevice
import com.netscope.core.model.PortResult
import com.netscope.core.network.PortScanner
import com.netscope.data.ScanController
import com.netscope.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.inject.Inject

data class DeviceDetailUiState(
    val device: DiscoveredDevice? = null,
    val ports: List<PortResult> = emptyList(),
    val serviceNames: Map<Int, String> = emptyMap(),
    val isScanningPorts: Boolean = false,
    val labelDraft: String = "",
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val scanController: ScanController,
    private val portScanner: PortScanner,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val deviceKey = MutableStateFlow("")
    private val ports = MutableStateFlow<List<PortResult>>(emptyList())
    private val scanningPorts = MutableStateFlow(false)
    private val labelDraft = MutableStateFlow("")
    private var portScanJob: Job? = null

    val uiState: StateFlow<DeviceDetailUiState> = combine(
        scanController.state,
        deviceKey,
        ports,
        scanningPorts,
        labelDraft,
    ) { scan, key, portResults, scanning, draft ->
        val device = scan.devices.firstOrNull { it.key == key }
        DeviceDetailUiState(
            device = device,
            ports = portResults,
            serviceNames = portResults.associate { it.port to (portScanner.serviceNameFor(it.port) ?: "") },
            isScanningPorts = scanning,
            labelDraft = draft,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState())

    fun load(key: String) {
        if (deviceKey.value == key) return
        deviceKey.value = key
        ports.value = emptyList()
        labelDraft.value = scanController.state.value.devices
            .firstOrNull { it.key == key }?.userLabel.orEmpty()
    }

    fun setLabelDraft(value: String) { labelDraft.value = value }

    fun saveLabel() {
        // Labels belong to the stored device record; persisting them is part of the
        // history feature and is wired through the repository in the history screen.
    }

    fun scanPorts() {
        val device = uiState.value.device ?: return
        val address = device.ipv4 ?: return
        portScanJob?.cancel()
        portScanJob = viewModelScope.launch {
            scanningPorts.value = true
            ports.value = emptyList()
            try {
                val grabBanners = settingsRepository.settings.first().bannerGrabbingEnabled
                portScanner.scan(
                    address = InetAddress.getByAddress(address.bytes),
                    ports = PortScanner.Profile.COMMON.ports,
                    grabBanners = grabBanners,
                ) { result ->
                    ports.value = (ports.value + result).sortedBy { it.port }
                }
            } finally {
                scanningPorts.value = false
            }
        }
    }

    fun stopPortScan() {
        portScanJob?.cancel()
        scanningPorts.value = false
    }

    override fun onCleared() {
        portScanJob?.cancel()
        super.onCleared()
    }
}
