package com.netscope.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.Confidence
import com.netscope.core.model.Evidence
import com.netscope.core.model.EvidenceSource
import com.netscope.core.model.HealthPanel
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.model.Unavailability
import com.netscope.core.model.WifiConnectionInfo
import com.netscope.core.network.NetworkInspector
import com.netscope.core.network.PermissionInspector
import com.netscope.core.network.PermissionRequirement
import com.netscope.core.network.ReachabilityService
import com.netscope.core.network.WifiInspector
import com.netscope.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class DashboardUiState(
    val network: NetworkSnapshot? = null,
    val wifi: WifiConnectionInfo? = null,
    val health: HealthPanel? = null,
    val permissions: List<PermissionRequirement> = emptyList(),
    val publicIp: Evidence<String> = Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    val publicIpLookupEnabled: Boolean = false,
    val demoMode: Boolean = false,
    val isLoadingHealth: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val networkInspector: NetworkInspector,
    private val wifiInspector: WifiInspector,
    private val reachabilityService: ReachabilityService,
    private val permissionInspector: PermissionInspector,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val manualRefresh = MutableStateFlow(0)
    private val health = MutableStateFlow<HealthPanel?>(null)
    private val publicIp = MutableStateFlow<Evidence<String>>(
        Evidence.unavailable(Unavailability.NOT_ATTEMPTED),
    )
    private val loadingHealth = MutableStateFlow(false)

    private val networks: StateFlow<List<NetworkSnapshot>> =
        networkInspector.observeNetworks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<DashboardUiState> = combine(
        networks,
        settingsRepository.settings,
        health,
        publicIp,
        combine(manualRefresh, loadingHealth) { refresh, loading -> refresh to loading },
    ) { networkList, settings, healthPanel, ip, (_, loading) ->
        DashboardUiState(
            network = networkList.firstOrNull { it.isDefaultNetwork } ?: networkList.firstOrNull(),
            wifi = runCatching { wifiInspector.connectionInfo() }.getOrNull(),
            health = healthPanel,
            permissions = permissionInspector.requirements(),
            publicIp = ip,
            publicIpLookupEnabled = settings.publicIpLookupEnabled,
            demoMode = settings.demoModeEnabled,
            isLoadingHealth = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refresh() {
        manualRefresh.value += 1
        runHealthCheck()
    }

    /** Runs the health panel probes. Kept manual so the app never probes in the background. */
    fun runHealthCheck() {
        viewModelScope.launch {
            loadingHealth.value = true
            try {
                val network = withContext(Dispatchers.IO) { networkInspector.activeSnapshot() }
                health.value = reachabilityService.healthPanel(network)
            } finally {
                loadingHealth.value = false
            }
        }
    }

    /**
     * Looks up the public IP.
     *
     * This is the only feature on the dashboard that contacts anything outside the
     * local network, so it is opt-in, never automatic, and the endpoint is named in the
     * UI next to the button.
     */
    fun lookupPublicIp() {
        viewModelScope.launch {
            publicIp.value = Evidence.unavailable(
                Unavailability.NOT_ATTEMPTED, System.currentTimeMillis(), "Contacting $PUBLIC_IP_ENDPOINT…",
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = (URL(PUBLIC_IP_ENDPOINT).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        requestMethod = "GET"
                        // No identifying headers: the request carries nothing about the device.
                        setRequestProperty("Accept", "text/plain")
                    }
                    try {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                        connection.inputStream.bufferedReader()
                            .use { it.readText() }
                            .trim()
                            .take(64)
                            .filter { !it.isISOControl() }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            val now = System.currentTimeMillis()
            publicIp.value = if (result.isNullOrEmpty()) {
                Evidence.unavailable(
                    Unavailability.NOT_DISCOVERED, now,
                    "$PUBLIC_IP_ENDPOINT did not answer. This needs working internet access.",
                )
            } else {
                Evidence.observed(
                    result, EvidenceSource.DNS, Confidence.HIGH, now,
                    "Reported by $PUBLIC_IP_ENDPOINT. This is the address the internet sees, which " +
                        "may belong to your ISP's NAT rather than to this device.",
                )
            }
        }
    }

    companion object {
        /** Named in the UI before the request is made, as the privacy model requires. */
        const val PUBLIC_IP_ENDPOINT = "https://api.ipify.org"
    }
}
