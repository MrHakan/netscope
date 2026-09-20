package com.netscope.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.BuildConfig
import com.netscope.core.model.CapabilityLevel
import com.netscope.core.model.PerformanceProfile
import com.netscope.core.network.IcmpProbe
import com.netscope.core.network.PermissionInspector
import com.netscope.data.NetScopeSettings
import com.netscope.data.OuiRepository
import com.netscope.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: NetScopeSettings = NetScopeSettings(),
    val icmpAvailable: Boolean = false,
    val localNetworkPermission: String? = null,
    val ouiEntryCount: Int = 0,
    val capabilityLabel: String = CapabilityLevel.NORMAL.label,
    val versionName: String = BuildConfig.VERSION_NAME,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val icmpProbe: IcmpProbe,
    private val permissionInspector: PermissionInspector,
    private val ouiRepository: OuiRepository,
) : ViewModel() {

    private val icmpAvailable = MutableStateFlow(false)
    private val ouiCount = MutableStateFlow(0)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        icmpAvailable,
        ouiCount,
    ) { settings, icmp, oui ->
        SettingsUiState(
            settings = settings,
            icmpAvailable = icmp,
            localNetworkPermission = permissionInspector.localNetworkPermission(),
            ouiEntryCount = oui,
            // Root and device-owner modes are detected, never assumed. Neither is
            // implemented yet, so the app reports the mode it is actually running in.
            capabilityLabel = CapabilityLevel.NORMAL.label,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            icmpAvailable.value = withContext(Dispatchers.IO) { icmpProbe.isAvailable() }
            ouiCount.value = ouiRepository.lookup().size
        }
    }

    fun setPublicIpLookup(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPublicIpLookupEnabled(enabled) }
    }

    fun setNewDeviceNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNewDeviceNotificationsEnabled(enabled) }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDemoModeEnabled(enabled) }
    }

    fun setBannerGrabbing(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBannerGrabbingEnabled(enabled) }
    }

    fun setPerformanceProfile(profile: PerformanceProfile) {
        viewModelScope.launch { settingsRepository.setPerformanceProfile(profile) }
    }
}
