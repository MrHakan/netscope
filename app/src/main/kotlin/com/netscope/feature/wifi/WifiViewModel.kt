package com.netscope.feature.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.WifiBand
import com.netscope.core.model.WifiConnectionInfo
import com.netscope.core.model.WifiScanAvailability
import com.netscope.core.model.WifiScanEntry
import com.netscope.core.network.WifiInspector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One sample in the live signal graph. */
data class SignalSample(val epochMillis: Long, val rssiDbm: Int)

data class WifiUiState(
    val connection: WifiConnectionInfo? = null,
    val networks: List<WifiScanEntry> = emptyList(),
    val availability: WifiScanAvailability = WifiScanAvailability.Available,
    val selectedBand: WifiBand? = null,
    val liveSignalActive: Boolean = false,
    val samples: List<SignalSample> = emptyList(),
) {
    val visibleNetworks: List<WifiScanEntry>
        get() = if (selectedBand == null) networks else networks.filter { it.band == selectedBand }

    val minRssi: Int? get() = samples.minOfOrNull { it.rssiDbm }
    val maxRssi: Int? get() = samples.maxOfOrNull { it.rssiDbm }
    val averageRssi: Double? get() = samples.map { it.rssiDbm }.average().takeIf { samples.isNotEmpty() }
}

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val wifiInspector: WifiInspector,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0)
    private val selectedBand = MutableStateFlow<WifiBand?>(null)
    private val liveSignalActive = MutableStateFlow(false)
    private val samples = MutableStateFlow<List<SignalSample>>(emptyList())
    private var liveSignalJob: Job? = null

    private val networks: StateFlow<List<WifiScanEntry>> = wifiInspector.observeScanResults()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<WifiUiState> = combine(
        networks,
        refreshTick,
        selectedBand,
        liveSignalActive,
        samples,
    ) { scanResults, _, band, live, sampleList ->
        WifiUiState(
            connection = runCatching { wifiInspector.connectionInfo() }.getOrNull(),
            networks = scanResults,
            availability = wifiInspector.scanAvailability(),
            selectedBand = band,
            liveSignalActive = live,
            samples = sampleList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WifiUiState())

    /**
     * Asks Android for a fresh scan.
     *
     * If the platform refuses, the availability state explains why rather than leaving
     * the user looking at silently stale results.
     */
    fun requestScan() {
        viewModelScope.launch {
            wifiInspector.requestScan()
            refreshTick.value += 1
        }
    }

    fun setBand(band: WifiBand?) { selectedBand.value = band }

    /**
     * Starts the live signal meter.
     *
     * Sampling is once per second: RSSI is reported by the driver at roughly that rate
     * anyway, and polling faster would only burn battery.
     */
    fun startLiveSignal() {
        if (liveSignalJob?.isActive == true) return
        samples.value = emptyList()
        liveSignalActive.value = true
        liveSignalJob = viewModelScope.launch {
            while (isActive) {
                val rssi = runCatching { wifiInspector.connectionInfo()?.rssiDbm?.value }.getOrNull()
                if (rssi != null) {
                    val now = System.currentTimeMillis()
                    // Keep a rolling 60-second window.
                    samples.value = (samples.value + SignalSample(now, rssi))
                        .filter { now - it.epochMillis <= WINDOW_MILLIS }
                }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    fun stopLiveSignal() {
        liveSignalJob?.cancel()
        liveSignalJob = null
        liveSignalActive.value = false
    }

    override fun onCleared() {
        liveSignalJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 1_000L
        const val WINDOW_MILLIS = 60_000L
    }
}
