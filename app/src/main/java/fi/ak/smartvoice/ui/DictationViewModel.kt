package fi.ak.smartvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.net.EXPECTED_CONTRACT_VERSION
import fi.ak.smartvoice.net.StackHttpException
import fi.ak.smartvoice.net.StackResponseParseException
import fi.ak.smartvoice.net.StackUnreachableException
import fi.ak.smartvoice.net.TranscribeClient
import fi.ak.smartvoice.net.TranscribeResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chunk 0 states (PLAN §14). One screen, five states, and no state is ever silent:
 *
 * | State       | What the user sees                                          |
 * |-------------|-------------------------------------------------------------|
 * | Idle        | record button, server URL + reachability, last result if any |
 * | Recording   | elapsed seconds, stop + cancel; cancel commits nothing       |
 * | Uploading   | audio length being sent, spinner                              |
 * | Done        | text at full width, stages, latency split, corrections       |
 * | Failed      | the contract's own error code and a human sentence           |
 */
sealed interface DictationUiState {
    data object Idle : DictationUiState
    data class Recording(val seconds: Double) : DictationUiState
    data class Uploading(val audioSeconds: Double) : DictationUiState
    data class Done(val result: TranscribeResponse, val roundTripMs: Long) : DictationUiState
    data class Failed(val code: String, val message: String) : DictationUiState
}

sealed interface ServerStatus {
    data object Unknown : ServerStatus
    data class Ready(val contractVersion: String) : ServerStatus
    data class VersionMismatch(val contractVersion: String) : ServerStatus
    data class Unreachable(val message: String) : ServerStatus
}

class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS, Application.MODE_PRIVATE)
    private val recorder = WavRecorder()
    private var ticker: Job? = null

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_URL).orEmpty())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _state = MutableStateFlow<DictationUiState>(DictationUiState.Idle)
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    fun onServerUrlChange(value: String) {
        _serverUrl.value = value
        prefs.edit().putString(KEY_SERVER_URL, value).apply()
        _serverStatus.value = ServerStatus.Unknown
    }

    fun checkServer() {
        val url = _serverUrl.value
        if (url.isBlank()) {
            _serverStatus.value = ServerStatus.Unreachable("No server URL set yet.")
            return
        }
        viewModelScope.launch {
            _serverStatus.value = ServerStatus.Unknown
            try {
                val meta = client().meta()
                _serverStatus.value = if (meta.contractVersion == EXPECTED_CONTRACT_VERSION) {
                    ServerStatus.Ready(meta.contractVersion)
                } else {
                    // The contract is pinned: a different version refuses the remote tier rather
                    // than guessing at field meanings (docs/contract.md).
                    ServerStatus.VersionMismatch(meta.contractVersion)
                }
            } catch (t: Throwable) {
                _serverStatus.value = ServerStatus.Unreachable(t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess {
                _state.value = DictationUiState.Recording(0.0)
                ticker = viewModelScope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        _state.value = DictationUiState.Recording(recorder.capturedSeconds)
                    }
                }
            }
            .onFailure { _state.value = DictationUiState.Failed("audio_init", it.message ?: "AudioRecord failed to start") }
    }

    fun stopAndTranscribe() {
        ticker?.cancel()
        ticker = null

        val wav = recorder.stop()
        if (wav == null) {
            _state.value = DictationUiState.Failed("empty_recording", "No audio was captured — nothing was sent.")
            return
        }
        val seconds = (wav.size - 44).toDouble() / (WavRecorder.SAMPLE_RATE * 2)
        _state.value = DictationUiState.Uploading(seconds)

        viewModelScope.launch {
            try {
                val (response, roundTripMs) = client().transcribe(wav)
                _state.value = DictationUiState.Done(response, roundTripMs)
            } catch (e: StackHttpException) {
                _state.value = DictationUiState.Failed(e.code, "${e.message} (HTTP ${e.status})")
            } catch (e: StackUnreachableException) {
                _state.value = DictationUiState.Failed("unreachable", e.message ?: "Server unreachable")
            } catch (e: StackResponseParseException) {
                // Show the payload: a parse failure is diagnosed from the body, not the exception.
                _state.value = DictationUiState.Failed(
                    "response_unreadable",
                    (e.message ?: "Unparseable response") + "\n\nRaw response:\n" + e.rawBody.take(600),
                )
            } catch (t: Throwable) {
                _state.value = DictationUiState.Failed(
                    "client_error",
                    t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    fun cancelRecording() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        _state.value = DictationUiState.Idle
    }

    fun reset() {
        _state.value = DictationUiState.Idle
    }

    private fun client() = TranscribeClient(_serverUrl.value)

    override fun onCleared() {
        ticker?.cancel()
        recorder.cancel()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "smart_voice"
        private const val KEY_SERVER_URL = "server_url"
        private const val TICK_MS = 200L

        /** Placeholder only — the real address is the home PC's LAN/Tailscale IP and port. */
        const val DEFAULT_URL = "http://192.0.2.100:8000"
    }
}
