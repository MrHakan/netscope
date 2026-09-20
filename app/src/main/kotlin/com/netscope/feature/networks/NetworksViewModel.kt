package com.netscope.feature.networks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.NetworkSnapshot
import com.netscope.core.network.NetworkInspector
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

data class NetworksUiState(
    val networks: List<NetworkSnapshot> = emptyList(),
    val interfaces: List<NetworkInspector.InterfaceDetail> = emptyList(),
    val query: String = "",
)

@HiltViewModel
class NetworksViewModel @Inject constructor(
    private val networkInspector: NetworkInspector,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val interfaces = MutableStateFlow<List<NetworkInspector.InterfaceDetail>>(emptyList())

    val uiState: StateFlow<NetworksUiState> = combine(
        networkInspector.observeNetworks(),
        interfaces,
        query,
    ) { networks, interfaceList, queryText ->
        NetworksUiState(networks = networks, interfaces = interfaceList, query = queryText)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworksUiState())

    init {
        viewModelScope.launch {
            interfaces.value = withContext(Dispatchers.IO) { networkInspector.interfaceDetails() }
        }
    }

    fun setQuery(value: String) { query.value = value }
}
