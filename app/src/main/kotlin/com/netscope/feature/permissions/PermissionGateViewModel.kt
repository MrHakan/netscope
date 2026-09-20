package com.netscope.feature.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.network.PermissionInspector
import com.netscope.core.network.PermissionRequirement
import com.netscope.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionGateUiState(
    val shouldPrompt: Boolean = false,
    val requirements: List<PermissionRequirement> = emptyList(),
    /** Non-null for exactly one composition, when the system dialog should open. */
    val launchRequest: List<String>? = null,
)

@HiltViewModel
class PermissionGateViewModel @Inject constructor(
    private val permissionInspector: PermissionInspector,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val launchRequest = MutableStateFlow<List<String>?>(null)
    private val dismissed = MutableStateFlow(false)
    private val refresh = MutableStateFlow(0)

    val uiState: StateFlow<PermissionGateUiState> = combine(
        settingsRepository.settings,
        launchRequest,
        dismissed,
        refresh,
    ) { settings, request, isDismissed, _ ->
        val outstanding = outstandingRequirements()
        PermissionGateUiState(
            // Asked once. After that the dashboard's permission card is the way back in,
            // so the app never nags on every launch.
            shouldPrompt = !settings.permissionsPrompted && !isDismissed && outstanding.isNotEmpty(),
            requirements = outstanding,
            launchRequest = request,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PermissionGateUiState())

    /**
     * The permissions worth asking for right now.
     *
     * A permission the running platform does not define is filtered out: requesting it
     * would silently fail and the user would see a prompt for something that does not
     * exist on their device.
     */
    private fun outstandingRequirements(): List<PermissionRequirement> =
        permissionInspector.requirements()
            .filter { it.isDefinedByPlatform && !it.isGranted }

    fun request() {
        val permissions = outstandingRequirements().map { it.permission }
        if (permissions.isEmpty()) {
            markPrompted()
            return
        }
        launchRequest.value = permissions
    }

    /** Clears the one-shot trigger so the system dialog is not relaunched. */
    fun onRequestLaunched() {
        launchRequest.value = null
    }

    fun onRequestCompleted() {
        refresh.value += 1
        markPrompted()
    }

    fun dismiss() {
        dismissed.value = true
        markPrompted()
    }

    private fun markPrompted() {
        viewModelScope.launch { settingsRepository.setPermissionsPrompted(true) }
    }
}
