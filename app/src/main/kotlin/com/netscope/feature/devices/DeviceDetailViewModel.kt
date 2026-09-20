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
    val saveResult: String? = null,
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    private val scanController: ScanController,
    private val portScanner: PortScanner,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: com.netscope.core.database.HistoryRepository,
) : ViewModel() {

    private val deviceKey = MutableStateFlow("")
    private val ports = MutableStateFlow<List<PortResult>>(emptyList())
    private val scanningPorts = MutableStateFlow(false)
    private val labelDraft = MutableStateFlow("")
    private val saveResult = MutableStateFlow<String?>(null)
    private var portScanJob: Job? = null

    val uiState: StateFlow<DeviceDetailUiState> = combine(
        scanController.state,
        deviceKey,
        ports,
        scanningPorts,
        combine(labelDraft, saveResult) { draft, result -> draft to result },
    ) { scan, key, portResults, scanning, (draft, result) ->
        val device = scan.devices.firstOrNull { it.key == key }
        DeviceDetailUiState(
            device = device,
            ports = portResults,
            serviceNames = portResults.associate { it.port to (portScanner.serviceNameFor(it.port) ?: "") },
            isScanningPorts = scanning,
            labelDraft = draft,
            saveResult = result,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState())

    fun load(key: String) {
        if (deviceKey.value == key) return
        deviceKey.value = key
        ports.value = emptyList()
        labelDraft.value = scanController.state.value.devices
            .firstOrNull { it.key == key }?.userLabel.orEmpty()
    }

    fun setLabelDraft(value: String) {
        labelDraft.value = value
        saveResult.value = null
    }

    /**
     * Saves the label the user typed.
     *
     * It is applied to the visible list immediately and written to the device's record
     * so it survives the next scan. An empty field clears the label rather than storing
     * a blank name.
     */
    fun saveLabel() {
        val key = deviceKey.value.ifEmpty { return }
        val label = labelDraft.value.trim().ifEmpty { null }
        val profileId = scanController.state.value.profileId
        scanController.applyUserLabel(key, label)
        viewModelScope.launch {
            saveResult.value = when {
                profileId == null ->
                    "Saved for this session. It will persist once this network has a stored " +
                        "profile, which the first completed scan creates."
                historyRepository.setDeviceLabelByKey(profileId, key, label) ->
                    if (label == null) "Label cleared." else "Saved."
                else ->
                    "Saved for this session. This device is not in the stored history yet, so " +
                        "the name will persist after the next completed scan."
            }
        }
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
