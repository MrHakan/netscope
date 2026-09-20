package com.netscope.feature.subnets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.model.ScanProfile
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.ReachabilityReport
import com.netscope.core.network.ConnectionReport
import com.netscope.core.network.ConnectionTester
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.ReachabilityService
import com.netscope.data.ScanController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val hostToTest: String = "",
    val hostError: String? = null,
    val connectionReport: ConnectionReport? = null,
    val isTestingHost: Boolean = false,
    val scanStartedFor: String? = null,
)

@HiltViewModel
class SubnetsViewModel @Inject constructor(
    private val networkInspector: NetworkInspector,
    private val reachabilityService: ReachabilityService,
    private val connectionTester: ConnectionTester,
    private val scanController: ScanController,
    private val settingsRepository: com.netscope.data.SettingsRepository,
) : ViewModel() {

    private val targetText = MutableStateFlow("")
    private val targetError = MutableStateFlow<String?>(null)
    private val report = MutableStateFlow<ReachabilityReport?>(null)
    private val analyzing = MutableStateFlow(false)
    private val network = MutableStateFlow<NetworkSnapshot?>(null)
    private val hostToTest = MutableStateFlow("")
    private val hostError = MutableStateFlow<String?>(null)
    private val connectionReport = MutableStateFlow<ConnectionReport?>(null)
    private val testingHost = MutableStateFlow(false)
    private val scanStartedFor = MutableStateFlow<String?>(null)
    private var analysisJob: Job? = null
    private var connectionJob: Job? = null

    val uiState: StateFlow<SubnetsUiState> = combine(
        network,
        combine(targetText, targetError) { text, error -> text to error },
        report,
        analyzing,
        combine(hostToTest, hostError, connectionReport, testingHost, scanStartedFor) {
            host, error, report, testing, startedFor ->
            HostTestState(host, error, report, testing, startedFor)
        },
    ) { snapshot, (text, error), result, isAnalyzing, hostTest ->
        SubnetsUiState(
            network = snapshot,
            targetText = text,
            targetError = error,
            report = result,
            isAnalyzing = isAnalyzing,
            hostToTest = hostTest.host,
            hostError = hostTest.error,
            connectionReport = hostTest.report,
            isTestingHost = hostTest.testing,
            scanStartedFor = hostTest.scanStartedFor,
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

    fun setHostToTest(value: String) {
        hostToTest.value = value
        hostError.value = null
    }

    /**
     * Runs a battery of connection techniques against one host in the target subnet.
     *
     * This answers the practical question the analyzer's sampling cannot: can this
     * device actually talk to that specific machine. A single technique succeeding is
     * proof; all of them failing is not proof of the opposite, and the report says so.
     */
    fun testHost() {
        val address = Ipv4Address.parse(hostToTest.value.trim())
        if (address == null) {
            hostError.value = "Enter an IPv4 address in the target subnet, for example 10.0.7.5."
            return
        }
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            testingHost.value = true
            connectionReport.value = null
            try {
                connectionReport.value = withContext(Dispatchers.IO) {
                    connectionTester.test(java.net.InetAddress.getByAddress(address.bytes))
                }
            } finally {
                testingHost.value = false
            }
        }
    }

    /** Prefills the host field from the analyzer's own findings. */
    fun useRespondingHost() {
        report.value?.observations?.respondingHosts?.firstOrNull()?.let {
            hostToTest.value = it.toCanonicalString()
            hostError.value = null
        }
    }

    /**
     * Starts a full discovery scan of the target subnet.
     *
     * The analyzer only samples a handful of addresses to answer "is anything there".
     * Once the answer is yes, this enumerates the subnet properly; results appear on
     * the Devices screen, which owns scan presentation.
     */
    fun scanTargetSubnet() {
        val cidr = Ipv4Cidr.parse(targetText.value.trim())
        if (cidr == null) {
            targetError.value = "Enter a target subnet first, for example 10.0.7.0/24."
            return
        }
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            scanController.start(
                target = cidr,
                profile = ScanProfile.SMART,
                performanceProfile = settings.performanceProfile,
                demoMode = settings.demoModeEnabled,
            )
            scanStartedFor.value = cidr.toString()
        }
    }

    fun cancel() {
        analysisJob?.cancel()
        connectionJob?.cancel()
        analyzing.value = false
        testingHost.value = false
    }

    override fun onCleared() {
        analysisJob?.cancel()
        connectionJob?.cancel()
        super.onCleared()
    }
}

/** Groups the host-test fields so the combine stays within its arity limit. */
private data class HostTestState(
    val host: String,
    val error: String?,
    val report: ConnectionReport?,
    val testing: Boolean,
    val scanStartedFor: String?,
)
