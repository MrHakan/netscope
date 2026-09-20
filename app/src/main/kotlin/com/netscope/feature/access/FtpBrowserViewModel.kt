package com.netscope.feature.access

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.network.FtpBrowserService
import com.netscope.core.network.FtpEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FtpBrowserUiState(
    val host: String = "",
    val port: Int = 21,
    val username: String = "anonymous",
    val password: String = "netscope@local",
    val path: String = "/",
    val entries: List<FtpEntry> = emptyList(),
    val greeting: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class FtpBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ftp: FtpBrowserService,
) : ViewModel() {

    private val _state = MutableStateFlow(
        FtpBrowserUiState(
            host = savedStateHandle.get<String>("host").orEmpty(),
            port = savedStateHandle.get<String>("port")?.toIntOrNull() ?: 21,
        ),
    )
    val state: StateFlow<FtpBrowserUiState> = _state.asStateFlow()

    private var browseJob: Job? = null

    fun setUsername(value: String) {
        _state.value = _state.value.copy(username = value.take(256), error = null)
    }

    fun setPassword(value: String) {
        _state.value = _state.value.copy(password = value.take(512), error = null)
    }

    fun connect() = loadPath(_state.value.path)

    fun openDirectory(name: String) {
        val clean = name.replace("\r", "").replace("\n", "")
        if (clean.isBlank()) return
        val base = _state.value.path
        val next = if (base == "/") "/$clean" else base.trimEnd('/') + "/" + clean
        loadPath(next)
    }

    fun up() {
        val current = _state.value.path.trimEnd('/')
        if (current.isEmpty() || current == "/") {
            loadPath("/")
            return
        }
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
        loadPath(parent)
    }

    private fun loadPath(path: String) {
        if (_state.value.host.isBlank()) {
            _state.value = _state.value.copy(error = "No FTP host was supplied.")
            return
        }

        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val result = ftp.list(
                    host = _state.value.host,
                    port = _state.value.port,
                    username = _state.value.username,
                    password = _state.value.password,
                    path = path,
                )
                _state.value = _state.value.copy(
                    path = result.workingDirectory,
                    entries = result.entries,
                    greeting = result.serverGreeting,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "FTP connection failed.",
                    entries = emptyList(),
                )
            } finally {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    override fun onCleared() {
        browseJob?.cancel()
        _state.value = _state.value.copy(password = "")
        super.onCleared()
    }
}
