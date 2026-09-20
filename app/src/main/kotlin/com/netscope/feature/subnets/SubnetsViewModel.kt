package com.netscope.feature.subnets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.ReachabilityReport
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.ReachabilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SubnetsUiState(
    val network: NetworkSnapshot? = null,
    val targetText: String = "",
    val targetError: String? = null,
    val report: ReachabilityReport? = null,
    val isAnalyzing: Boolean = false,
)

@HiltViewModel
class SubnetsViewModel @Inject constructor(
    private val networkInspector: NetworkInspector,
    private val reachabilityService: ReachabilityService,
) : ViewModel() {

    private val targetText = MutableStateFlow("")
    private val targetError = MutableStateFlow<String?>(null)
    private val report = MutableStateFlow<ReachabilityReport?>(null)
    private val analyzing = MutableStateFlow(false)
    private val network = MutableStateFlow<NetworkSnapshot?>(null)
    private var analysisJob: Job? = null

    val uiState: StateFlow<SubnetsUiState> = combine(
        network,
        combine(targetText, targetError) { text, error -> text to error },
        report,
        analyzing,
    ) { snapshot, (text, error), result, isAnalyzing ->
        SubnetsUiState(
            network = snapshot,
            targetText = text,
            targetError = error,
            report = result,
            isAnalyzing = isAnalyzing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubnetsUiState())

    init {
        viewModelScope.launch {
            network.value = withContext(Dispatchers.IO) { networkInspector.activeSnapshot() }
        }
    }

    fun setTarget(value: String) {
        targetText.value = value
        targetError.value = null
    }

    /**
     * Runs the analyzer.
     *
     * Observation happens in the service; every conclusion is drawn by the pure
     * analyzer, so what the UI shows is exactly what the unit tests cover.
     */
    fun analyze() {
        val cidr = Ipv4Cidr.parse(targetText.value.trim())
        if (cidr == null) {
            targetError.value = "Enter a target subnet as an address and prefix, for example 10.0.7.0/24."
            return
        }
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            analyzing.value = true
            try {
                val snapshot = withContext(Dispatchers.IO) { networkInspector.activeSnapshot() }
                network.value = snapshot
                report.value = reachabilityService.analyze(cidr, snapshot)
            } finally {
                analyzing.value = false
            }
        }
    }

    fun cancel() {
        analysisJob?.cancel()
        analyzing.value = false
    }

    override fun onCleared() {
        analysisJob?.cancel()
        super.onCleared()
    }
}
