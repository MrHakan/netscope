package com.netscope.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.database.HistoryRepository
import com.netscope.core.database.NetworkProfileEntity
import com.netscope.core.database.ScanSessionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val profiles: List<NetworkProfileEntity> = emptyList(),
    val sessions: List<ScanSessionEntity> = emptyList(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = combine(
        historyRepository.observeProfiles(),
        historyRepository.observeSessions(),
    ) { profiles, sessions ->
        HistoryUiState(profiles = profiles, sessions = sessions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clearHistory() }
    }
}
