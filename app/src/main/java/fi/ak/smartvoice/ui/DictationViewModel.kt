package fi.ak.smartvoice.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.LocalAsrSession
import fi.ak.smartvoice.asr.ModelDownloadProgress
import fi.ak.smartvoice.asr.ModelDownloadResult
import fi.ak.smartvoice.asr.ModelDownloader
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.cleanup.CleanupModel
import fi.ak.smartvoice.cleanup.CleanupModelStore
import fi.ak.smartvoice.cleanup.CleanupModels
import fi.ak.smartvoice.cleanup.FileBytes
import fi.ak.smartvoice.cleanup.PullProgress
import fi.ak.smartvoice.cleanup.SdkState
import com.geniex.sdk.ModelManagerWrapper
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.engine.DictationOutcome
import fi.ak.smartvoice.engine.Engine
import fi.ak.smartvoice.engine.EnginePreference
import fi.ak.smartvoice.engine.TierPolicy
import fi.ak.smartvoice.net.EXPECTED_CONTRACT_VERSION
import fi.ak.smartvoice.net.StackHttpException
import fi.ak.smartvoice.net.StackResponseParseException
import fi.ak.smartvoice.net.StackUnreachableException
import fi.ak.smartvoice.net.TranscribeClient
import fi.ak.smartvoice.net.TranscribeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The settings/diagnostics screen. It is both:
 * - the app's settings surface — server URL, engine choice, and the model download the IME cannot
 *   do for itself (an IME has no screen and cannot request permission), and
 * - the diagnostics surface for whichever tier is selected — record, transcribe, show exactly what
 *   came back, including the parts that went wrong.
 *
 * The dictation path here uses the *same* engine choice and the same code as the panel
 * (`engine/TierPolicy.kt`, `asr/LocalAsrSession.kt`), so what this screen proves is what the
 * keyboard does. Otherwise there would be two dictation paths and only one of them verified.
 */
sealed interface DictationUiState {
    data object Idle : DictationUiState
    data class Recording(val seconds: Double, val engine: Engine) : DictationUiState
    data class Working(val stage: String, val engine: Engine) : DictationUiState
    data class Done(val outcome: DictationOutcome, val roundTripMs: Long?) : DictationUiState
    data class Failed(val code: String, val message: String) : DictationUiState
}

sealed interface ServerStatus {
    data object Unknown : ServerStatus
    data class Ready(val contractVersion: String) : ServerStatus
    data class VersionMismatch(val contractVersion: String) : ServerStatus
    data class Unreachable(val message: String) : ServerStatus
}

/** State of the on-device model. Never silent: "missing" says what to do about it. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Downloading(val progress: ModelDownloadProgress) : ModelStatus
    data object Installed : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * State of the **cleanup** model, which GenieX's own model manager owns (D-030) — a deliberately
 * separate download path from the ASR model above, because the SDK knows which files its runtime
 * needs for a given GGUF and we do not.
 *
 * `Absent` carries a note rather than a bare flag: during the spike the SDK's `init` callback did
 * not need to be awaited for the manager to work, so "init unconfirmed" is a fact worth showing
 * instead of hiding behind a boolean.
 */
sealed interface CleanupStatus {
    data object Checking : CleanupStatus
    data class Absent(val note: String?) : CleanupStatus
    data class Pulling(val progress: PullProgress) : CleanupStatus
    data class Installed(val bytes: Long) : CleanupStatus
    data class Failed(val message: String) : CleanupStatus
}

class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val settings = AppSettings(application)
    private val recorder = WavRecorder()
    private var ticker: Job? = null

    /** Same rule as the IME: one worker, because a streaming session is serial. */
    private val asrDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smartvoice-asr")
    }.asCoroutineDispatcher()

    private val modelDirectory = AsrModels.directory(application.filesDir)
    private val localEngine = LocalAsrEngine(modelDirectory)
    private val localSession = LocalAsrSession(
        engine = localEngine,
        dispatcher = asrDispatcher,
        scope = viewModelScope,
    )

    @Volatile
    private var cancelDownload = false

    /** Cold-load time reported on the utterance that paid it (D-010 rule 2). */
    @Volatile
    private var coldLoadMs = 0L

    @Volatile
    private var paysColdLoad = false

    private val _serverUrl = MutableStateFlow(settings.serverUrl)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private val _enginePreference = MutableStateFlow(settings.enginePreference)
    val enginePreference: StateFlow<EnginePreference> = _enginePreference.asStateFlow()

    private val _modelStatus = MutableStateFlow<ModelStatus>(currentModelStatus())
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /**
     * Result of the last explicit load check — D-010's cold-load number, obtainable without having
     * to dictate. The keyboard reports the same number in its status line when it pays it for real.
     */
    private val _loadProbe = MutableStateFlow<String?>(null)
    val loadProbe: StateFlow<String?> = _loadProbe.asStateFlow()

    private val _state = MutableStateFlow<DictationUiState>(DictationUiState.Idle)
    val state: StateFlow<DictationUiState> = _state.asStateFlow()

    private val _cleanupStatus = MutableStateFlow<CleanupStatus>(CleanupStatus.Checking)
    val cleanupStatus: StateFlow<CleanupStatus> = _cleanupStatus.asStateFlow()

    private var cleanupPull: Job? = null

    val modelBytes: Long get() = AsrModels.TOTAL_BYTES

    /** The catalogue entry the user picked; unknown or unset means the default (D-029 item 3). */
    val cleanupModel: CleanupModel get() = CleanupModels.byRepository(settings.cleanupModelId)

    init {
        refreshCleanupStatus()
    }

    // ------------------------------------------------------------------ settings

    fun onServerUrlChange(value: String) {
        _serverUrl.value = value
        settings.serverUrl = value
        _serverStatus.value = ServerStatus.Unknown
    }

    fun onEnginePreferenceChange(preference: EnginePreference) {
        settings.enginePreference = preference
        _enginePreference.value = preference
    }

    /** What the tier policy would decide for the next utterance, for the screen to display. */
    fun engineSummary(): String {
        val choice = TierPolicy.choose(settings.enginePreference, localEngine.availability())
        return "${choice.engine.label} — ${choice.note}"
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

    // ------------------------------------------------------------------ model download

    private fun currentModelStatus(): ModelStatus =
        if (AsrModels.isInstalled(modelDirectory)) ModelStatus.Installed else ModelStatus.Missing

    fun downloadModel() {
        if ((_modelStatus.value as? ModelStatus.Downloading) != null) return
        cancelDownload = false

        viewModelScope.launch {
            _modelStatus.value = ModelStatus.Downloading(
                ModelDownloadProgress(0f, 0, AsrModels.FILES.first().name, 0, AsrModels.FILES.size),
            )
            val result = withContext(Dispatchers.IO) {
                ModelDownloader(modelDirectory) { progress -> _modelStatus.value = ModelStatus.Downloading(progress) }
                    .download(isCancelled = { cancelDownload })
            }
            _modelStatus.value = when (result) {
                ModelDownloadResult.Complete -> {
                    // A completed download is worth proving: load it once, here, and report the ms.
                    probeLocalEngine()
                    ModelStatus.Installed
                }
                is ModelDownloadResult.Cancelled ->
                    if (AsrModels.isInstalled(modelDirectory)) {
                        ModelStatus.Installed
                    } else {
                        // The .part files are kept, so resuming does not re-download what is done.
                        ModelStatus.Failed("cancelled after ${result.downloadedBytes / 1_000_000} MB — tap download to resume")
                    }
                is ModelDownloadResult.Failed -> ModelStatus.Failed(result.message)
            }
        }
    }

    fun cancelModelDownload() {
        cancelDownload = true
    }

    /**
     * Loads the recogniser once and reports how long it took. This is the same code path the first
     * dictation takes, so a green result here means the model loads, not merely that it downloaded.
     */
    fun probeLocalEngine() {
        if (!AsrModels.isInstalled(modelDirectory)) {
            _loadProbe.value = "not installed — nothing to load"
            return
        }
        _loadProbe.value = "loading…"
        viewModelScope.launch {
            val availability = withContext(asrDispatcher) { localEngine.load() }
            _loadProbe.value = when (availability) {
                LocalAsrEngine.Availability.Loaded -> "load check: OK in ${localEngine.lastLoadMillis} ms"
                LocalAsrEngine.Availability.NotInstalled -> "not installed — nothing to load"
                LocalAsrEngine.Availability.NotLoaded -> "not loaded"
                is LocalAsrEngine.Availability.Unavailable -> "load check FAILED: ${availability.reason}"
            }
        }
    }

    /**
     * Frees ~681 MB. Honest about what it costs: the next local utterance pays the download again,
     * and the engine must be unloaded first or the runtime keeps reading deleted files.
     */
    fun deleteModel() {
        viewModelScope.launch {
            withContext(asrDispatcher) { localEngine.unload() }
            withContext(Dispatchers.IO) {
                File(modelDirectory, "").listFiles()?.forEach { it.delete() }
            }
            _loadProbe.value = null
            _modelStatus.value = currentModelStatus()
        }
    }

    // ------------------------------------------------------------------ cleanup model (D-030)

    /**
     * Asks the SDK what is actually on disk. This is the only honest source: the model manager owns
     * the files, and it resumes its own downloads.
     */
    fun refreshCleanupStatus() {
        if (_cleanupStatus.value is CleanupStatus.Pulling) return
        viewModelScope.launch {
            _cleanupStatus.value = CleanupStatus.Checking
            _cleanupStatus.value = readCleanupStatus()
        }
    }

    private suspend fun readCleanupStatus(): CleanupStatus {
        val sdk = CleanupModelStore.ensureReady(app)
        if (sdk is SdkState.NotReady) return CleanupStatus.Failed("GenieX init failed — ${sdk.message}")
        val note = (sdk as? SdkState.Unconfirmed)?.let {
            "SDK init: no callback within 10 s, so this proceeds as the spike's probe did (D-029)."
        }
        val model = cleanupModel
        return if (CleanupModelStore.paths(app, model) == null) {
            CleanupStatus.Absent(note)
        } else {
            CleanupStatus.Installed(CleanupModelStore.installedBytes(app, model))
        }
    }

    fun pullCleanupModel() {
        if (_cleanupStatus.value is CleanupStatus.Pulling) return
        val model = cleanupModel
        cleanupPull = viewModelScope.launch {
            _cleanupStatus.value = CleanupStatus.Pulling(
                PullProgress(0f, 0L, model.approximateBytes, "", 0, 0),
            )
            val sdk = CleanupModelStore.ensureReady(app)
            if (sdk is SdkState.NotReady) {
                _cleanupStatus.value = CleanupStatus.Failed("GenieX init failed — ${sdk.message}")
                return@launch
            }
            try {
                CleanupModelStore.pull(model).collect { event ->
                    when (event) {
                        is ModelManagerWrapper.PullEvent.Progress -> {
                            _cleanupStatus.value = CleanupStatus.Pulling(
                                CleanupModels.progressOf(
                                    event.files.map {
                                        FileBytes(it.file_name, it.downloaded_bytes, it.total_bytes)
                                    },
                                ),
                            )
                        }
                        // Not trusted as "installed": the state is re-read from the SDK below, so a
                        // completion event cannot make the screen claim a file that is not there.
                        is ModelManagerWrapper.PullEvent.Completed -> Unit
                        is ModelManagerWrapper.PullEvent.Error -> {
                            _cleanupStatus.value =
                                CleanupStatus.Failed("download failed (code ${event.code}) — ${event.message}")
                        }
                    }
                }
                if (_cleanupStatus.value !is CleanupStatus.Failed) {
                    _cleanupStatus.value = readCleanupStatus()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _cleanupStatus.value = CleanupStatus.Failed(t.message ?: t::class.java.simpleName)
            }
        }
    }

    /**
     * Cancels the pull. The SDK's manager keeps what it has already fetched, so the bytes are not
     * wasted and a later download resumes.
     */
    fun cancelCleanupPull() {
        cleanupPull?.cancel()
        cleanupPull = null
        viewModelScope.launch {
            _cleanupStatus.value = CleanupStatus.Checking
            _cleanupStatus.value = readCleanupStatus()
        }
    }

    fun deleteCleanupModel() {
        viewModelScope.launch {
            _cleanupStatus.value = CleanupStatus.Checking
            _cleanupStatus.value = CleanupModelStore.remove(app, cleanupModel).fold(
                onSuccess = { readCleanupStatus() },
                onFailure = { CleanupStatus.Failed(it.message ?: "delete failed") },
            )
        }
    }

    // ------------------------------------------------------------------ dictation

    fun startRecording() {
        val choice = TierPolicy.choose(settings.enginePreference, localEngine.availability())
        coldLoadMs = 0L

        if (choice.engine == Engine.LOCAL) {
            // Queued before the model loads, so the first utterance is not silently truncated.
            paysColdLoad = localEngine.availability() != LocalAsrEngine.Availability.Loaded
            localSession.begin()
            recorder.frameSink = localSession::onFrames
        } else {
            recorder.frameSink = null
        }

        runCatching { recorder.start() }
            .onSuccess {
                _state.value = DictationUiState.Recording(0.0, choice.engine)
                ticker = viewModelScope.launch {
                    while (isActive) {
                        delay(TICK_MS)
                        _state.value = DictationUiState.Recording(recorder.capturedSeconds, choice.engine)
                    }
                }
                if (choice.engine == Engine.LOCAL) {
                    viewModelScope.launch {
                        val availability = localSession.open(settings.language)
                        if (availability !is LocalAsrEngine.Availability.Loaded) {
                            val reason = (availability as? LocalAsrEngine.Availability.Unavailable)?.reason
                                ?: "model not installed"
                            localSession.abort()
                            recorder.cancel()
                            ticker?.cancel()
                            _state.value = DictationUiState.Failed("local_engine", reason)
                        } else if (paysColdLoad) {
                            coldLoadMs = localEngine.lastLoadMillis
                        }
                    }
                }
            }
            .onFailure {
                localSession.abort()
                _state.value = DictationUiState.Failed("audio_init", it.message ?: "AudioRecord failed to start")
            }
    }

    fun stopAndTranscribe() {
        ticker?.cancel()
        ticker = null

        val choice = TierPolicy.choose(settings.enginePreference, localEngine.availability())
        val audioSeconds = recorder.capturedSeconds

        when (choice.engine) {
            Engine.LOCAL -> transcribeLocally(choice.note, audioSeconds)
            Engine.REMOTE -> transcribeOnServer(audioSeconds)
        }
    }

    private fun transcribeLocally(note: String, audioSeconds: Double) {
        _state.value = DictationUiState.Working("Transcribing on device…", Engine.LOCAL)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recorder.stop() }
            val stoppedAt = SystemClock.elapsedRealtime()
            val text = localSession.finish()
            val tailMs = SystemClock.elapsedRealtime() - stoppedAt
            _state.value = DictationUiState.Done(
                DictationOutcome(
                    text = text,
                    engine = Engine.LOCAL,
                    note = buildString {
                        append(note)
                        if (coldLoadMs > 0L) append(" · cold model load $coldLoadMs ms")
                        if (localSession.droppedFrames > 0) {
                            append(" · ${localSession.droppedFrames} frame(s) dropped")
                        }
                    },
                    audioSeconds = audioSeconds,
                    elapsedMs = tailMs,
                ),
                roundTripMs = null,
            )
        }
    }

    private fun transcribeOnServer(audioSeconds: Double) {
        _state.value = DictationUiState.Working("Uploading %.1f s of audio, cleaned on the PC…".format(audioSeconds), Engine.REMOTE)
        viewModelScope.launch {
            val wav = recorder.stop()
            if (wav == null) {
                _state.value = DictationUiState.Failed("empty_recording", "No audio was captured — nothing was sent.")
                return@launch
            }
            try {
                // Same settings the dictation panel uses, so both surfaces send the same thing.
                val (response, roundTripMs) = client().transcribe(
                    wav = wav,
                    cleanup = settings.cleanupRequestValue,
                    language = settings.language,
                )
                _state.value = DictationUiState.Done(
                    DictationOutcome(
                        text = response.text,
                        engine = Engine.REMOTE,
                        note = "server",
                        audioSeconds = audioSeconds,
                        elapsedMs = roundTripMs,
                        response = response,
                    ),
                    roundTripMs = roundTripMs,
                )
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
                _state.value = DictationUiState.Failed("client_error", t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun cancelRecording() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        localSession.abort()
        _state.value = DictationUiState.Idle
    }

    fun reset() {
        _state.value = DictationUiState.Idle
    }

    private fun client() = TranscribeClient(_serverUrl.value)

    override fun onCleared() {
        ticker?.cancel()
        cleanupPull?.cancel()
        recorder.cancel()
        localSession.abort()
        localEngine.unload()
        asrDispatcher.close()
        super.onCleared()
    }

    companion object {
        private const val TICK_MS = 200L
    }
}
