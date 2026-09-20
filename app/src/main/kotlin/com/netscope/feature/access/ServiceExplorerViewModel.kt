package com.netscope.feature.access

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.model.CapabilityArea
import com.netscope.core.model.CapabilityVerdict
import com.netscope.core.model.Ipv4Address
import com.netscope.core.model.Ipv4Cidr
import com.netscope.core.network.PermissionInspector
import com.netscope.core.network.ServiceAccessScanner
import com.netscope.core.network.ServiceEndpoint
import com.netscope.core.network.ServiceScanProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ServiceExplorerUiState(
    val targetText: String = "",
    val endpoints: List<ServiceEndpoint> = emptyList(),
    val progress: ServiceScanProgress = ServiceScanProgress(),
    val isScanning: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ServiceExplorerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scanner: ServiceAccessScanner,
    private val permissionInspector: PermissionInspector,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ServiceExplorerUiState(targetText = savedStateHandle.get<String>("target").orEmpty()),
    )
    val state: StateFlow<ServiceExplorerUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private val resultMutex = Mutex()

    fun setTarget(value: String) {
        if (_state.value.isScanning) return
        _state.value = _state.value.copy(targetText = value, error = null)
    }

    fun scan() {
        val text = _state.value.targetText.trim()
        val cidr = Ipv4Cidr.parse(text)
        val host = if (cidr == null) Ipv4Address.parse(text) else null
        if (cidr == null && host == null) {
            _state.value = _state.value.copy(
                error = "Enter an IPv4 host such as 10.0.7.5 or a CIDR such as 10.0.7.0/24.",
            )
            return
        }

        when (val verdict = permissionInspector.verdict(CapabilityArea.LAN_SCAN)) {
            is CapabilityVerdict.PermissionRequired -> {
                _state.value = _state.value.copy(error = verdict.rationale)
                return
            }
            is CapabilityVerdict.Unsupported -> {
                _state.value = _state.value.copy(error = verdict.reason)
                return
            }
            else -> Unit
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                endpoints = emptyList(),
                progress = ServiceScanProgress(),
                isScanning = true,
                error = null,
            )
            try {
                val onEndpoint: suspend (ServiceEndpoint) -> Unit = { endpoint ->
                    resultMutex.withLock {
                        val current = _state.value.endpoints
                        if (current.none {
                                it.host == endpoint.host &&
                                    it.port == endpoint.port &&
                                    it.protocol == endpoint.protocol
                            }
                        ) {
                            _state.value = _state.value.copy(
                                endpoints = (current + endpoint).sortedWith(
                                    compareBy<ServiceEndpoint>(
                                        { Ipv4Address.parse(it.host)?.value ?: Long.MAX_VALUE },
                                        { it.port },
                                        { it.protocol.name },
                                    ),
                                ),
                            )
                        }
                    }
                }
                val onProgress: suspend (ServiceScanProgress) -> Unit = { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }

                if (cidr != null) {
                    scanner.scanSubnet(cidr, onEndpoint = onEndpoint, onProgress = onProgress)
                } else if (host != null) {
                    scanner.scanHost(host, onEndpoint = onEndpoint, onProgress = onProgress)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Service discovery failed.",
                )
            } finally {
                _state.value = _state.value.copy(isScanning = false)
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(isScanning = false)
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
