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
import fi.ak.smartvoice.cleanup.CachedModel
import fi.ak.smartvoice.cleanup.CleanupModel
import fi.ak.smartvoice.cleanup.CleanupModelStore
import fi.ak.smartvoice.cleanup.CleanupModels
import fi.ak.smartvoice.asr.ListenerTimings
import fi.ak.smartvoice.cleanup.CleanupPass
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.FileBytes
import fi.ak.smartvoice.cleanup.GenieXCleanupEngine
import fi.ak.smartvoice.cleanup.PullProgress
import fi.ak.smartvoice.cleanup.SdkState
import com.geniex.sdk.ModelManagerWrapper
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.UsageStatsStore
import fi.ak.smartvoice.data.WordDiffCalculator
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
 * One line of the cleanup test bench: what went in, what came out, and what it cost. `cleaned = false`
 * means the pass failed closed and the input text is what stands (that is why `output` equals `input`
 * rather than being empty).
 */
data class CleanupCase(
    val label: String,
    val input: String,
    val output: String,
    val cleaned: Boolean,
    val detail: String,
    val prompt: String,
)

/**
 * A timing field on the settings screen: the text as typed, and whether it currently parses to a usable
 * number. `invalid` is what lets the screen say "that is not a number of seconds" instead of silently
 * keeping the old value.
 */
data class TimingField(val text: String, val seconds: Float?, val invalid: Boolean)

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

    /**
     * The pause threshold is *part of the recogniser's config*, so it is passed at construction and
     * applied again through [onPauseSilenceChange] (D-035). Everything else about the listener is a
     * software rule and needs no rebuild.
     */
    private val localEngine = LocalAsrEngine(
        modelDirectory = modelDirectory,
        pauseSilenceSeconds = settings.pauseSilenceSeconds,
    )
    private val localSession = LocalAsrSession(
        engine = localEngine,
        dispatcher = asrDispatcher,
        scope = viewModelScope,
        timings = { settings.listenerTimings() },
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

    private val _unloadOnScreenOff = MutableStateFlow(settings.unloadOnScreenOff)
    val unloadOnScreenOff: StateFlow<Boolean> = _unloadOnScreenOff.asStateFlow()

    private val _modelIdleTimeoutMinutes = MutableStateFlow(settings.modelIdleTimeoutMinutes)
    val modelIdleTimeoutMinutes: StateFlow<Int> = _modelIdleTimeoutMinutes.asStateFlow()

    private val _enginePreference = MutableStateFlow(settings.enginePreference)
    val enginePreference: StateFlow<EnginePreference> = _enginePreference.asStateFlow()

    private val _pauseField = MutableStateFlow(field(settings.pauseSilenceSeconds))
    val pauseField: StateFlow<TimingField> = _pauseField.asStateFlow()

    private val _stableField = MutableStateFlow(field(settings.stableSilenceSeconds))
    val stableField: StateFlow<TimingField> = _stableField.asStateFlow()

    private val _stopField = MutableStateFlow(field(settings.stopAfterSilenceSeconds))
    val stopField: StateFlow<TimingField> = _stopField.asStateFlow()

    /** What a timing change actually did, said out loud — notably that one of them rebuilds the model. */
    private val _timingNote = MutableStateFlow<String?>(null)
    val timingNote: StateFlow<String?> = _timingNote.asStateFlow()

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

    private var cleanupEngine: GenieXCleanupEngine? = null

    private val _cleanupCases = MutableStateFlow<List<CleanupCase>>(emptyList())
    val cleanupCases: StateFlow<List<CleanupCase>> = _cleanupCases.asStateFlow()

    /** Non-null while a bench step runs, and it is the step's own description. */
    private val _cleanupBusy = MutableStateFlow<String?>(null)
    val cleanupBusy: StateFlow<String?> = _cleanupBusy.asStateFlow()

    /** Cold-load evidence for the cleanup model (D-010 rule 2), or an unload/failure message. */
    private val _cleanupLoad = MutableStateFlow<String?>(null)
    val cleanupLoad: StateFlow<String?> = _cleanupLoad.asStateFlow()

    private val _cleanupCache = MutableStateFlow<List<CachedModel>>(emptyList())
    val cleanupCache: StateFlow<List<CachedModel>> = _cleanupCache.asStateFlow()

    /**
     * Which editing rules the bench runs under. Exposed because the first device run showed the rules,
     * not the model, decide how much gets fixed — so comparing them has to be possible without a new
     * build.
     */
    private val _cleanupVariant = MutableStateFlow(CleanupPrompt.Variant.DEFAULT)
    val cleanupVariant: StateFlow<CleanupPrompt.Variant> = _cleanupVariant.asStateFlow()

    /** A unit the user typed, so their own Finnish can be tested without waiting for a new APK. */
    private val _cleanupCustomUnit = MutableStateFlow("")
    val cleanupCustomUnit: StateFlow<String> = _cleanupCustomUnit.asStateFlow()

    val modelBytes: Long get() = AsrModels.TOTAL_BYTES

    private val _selectedCleanupModel = MutableStateFlow(CleanupModels.byRepository(settings.cleanupModelId))
    val selectedCleanupModel: StateFlow<CleanupModel> = _selectedCleanupModel.asStateFlow()

    /** The catalogue entry the user picked; unknown or unset means the default (D-029 item 3). */
    val cleanupModel: CleanupModel get() = _selectedCleanupModel.value

    fun selectCleanupModel(model: CleanupModel) {
        settings.cleanupModelId = model.repository
        _selectedCleanupModel.value = model
        unloadCleanupModel()
        refreshCleanupStatus()
    }

    init {
        refreshCleanupStatus()
    }

    // ------------------------------------------------------------------ settings

    fun onServerUrlChange(value: String) {
        _serverUrl.value = value
        settings.serverUrl = value
        _serverStatus.value = ServerStatus.Unknown
    }

    fun onUnloadOnScreenOffChange(enabled: Boolean) {
        settings.unloadOnScreenOff = enabled
        _unloadOnScreenOff.value = enabled
    }

    fun onModelIdleTimeoutMinutesChange(minutes: Int) {
        settings.modelIdleTimeoutMinutes = minutes
        _modelIdleTimeoutMinutes.value = minutes
    }

    fun onEnginePreferenceChange(preference: EnginePreference) {
        settings.enginePreference = preference
        _enginePreference.value = preference
    }

    private fun field(seconds: Float) = TimingField(
        text = ListenerTimings.formatSeconds(seconds),
        seconds = seconds,
        invalid = false,
    )

    /**
     * A timing is applied as it is typed, but **only when the text is a usable number**: while the user is
     * on the way to `2.5` — `2`, then `2.` — the stored value must not lurch, and an unparseable entry
     * leaves the previous value in force while the field says so.
     *
     * The unit pause lives in the recogniser's own config, so changing it rebuilds the recogniser: one cold
     * load, paid on the next load, which the status line reports (D-035).
     */
    fun onPauseFieldChange(text: String) {
        val parsed = ListenerTimings.parseSeconds(text, AppSettings.MAX_TIMING_SECONDS)
        _pauseField.value = TimingField(text, parsed, parsed == null)
        if (parsed == null) return
        settings.pauseSilenceSeconds = parsed
        localEngine.applyPauseSilence(parsed)
        _loadProbe.value = null
        _timingNote.value = "unit pause %.2f s — the recogniser rebuilds on the next load".format(parsed)
    }

    fun onStableFieldChange(text: String) {
        val parsed = ListenerTimings.parseSeconds(text, AppSettings.MAX_TIMING_SECONDS)
        _stableField.value = TimingField(text, parsed, parsed == null)
        if (parsed == null) return
        settings.stableSilenceSeconds = parsed
        _timingNote.value = "fallback %.2f s — applies from the next utterance, no rebuild".format(parsed)
    }

    fun onStopFieldChange(text: String) {
        val parsed = ListenerTimings.parseSeconds(text, AppSettings.MAX_TIMING_SECONDS)
        _stopField.value = TimingField(text, parsed, parsed == null)
        if (parsed == null) return
        settings.stopAfterSilenceSeconds = parsed
        _timingNote.value = if (parsed == 0f) {
            "auto-stop off — the recording runs until you stop it"
        } else {
            "auto-stop after %.2f s of silence".format(parsed)
        }
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
        _cleanupCache.value = CleanupModelStore.installedModels(app)
        val model = cleanupModel
        return if (CleanupModelStore.paths(app, model) == null) {
            CleanupStatus.Absent(note)
        } else {
            CleanupStatus.Installed(CleanupModelStore.installedBytes(app, model))
        }
    }

    /** Deletes anything the manager has, including ids the catalogue does not list. */
    fun deleteCachedModel(id: String) {
        viewModelScope.launch {
            _cleanupBusy.value = "deleting $id…"
            CleanupModelStore.removeById(app, id).onFailure {
                _cleanupLoad.value = "delete failed — ${it.message}"
            }
            _cleanupStatus.value = CleanupStatus.Checking
            _cleanupStatus.value = readCleanupStatus()
            _cleanupBusy.value = null
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

    // ------------------------------------------------------------------ cleanup test bench

    /**
     * One engine per model, reused across bench runs, so the cold load is paid once — the same
     * residency rule the streaming pipeline will follow (D-010's lazy-sticky load, D-028's no automatic
     * unload). Changing the catalogue selection discards the old instance.
     */
    private fun engine(): GenieXCleanupEngine {
        val wanted = cleanupModel
        cleanupEngine?.let { existing ->
            if (existing.uses(wanted)) return existing
            existing.unload()
        }
        return GenieXCleanupEngine(app, wanted).also { cleanupEngine = it }
    }

    /** D-010 rule 2's number for the cleanup model: the cold load, measured on the phone. */
    fun loadCleanupModel() {
        if (_cleanupBusy.value != null) return
        viewModelScope.launch {
            _cleanupBusy.value = "loading ${cleanupModel.label} on the NPU…"
            val engine = engine()
            _cleanupLoad.value = engine.load().fold(
                onSuccess = { "loaded in ${engine.lastLoadMs} ms — resident until unloaded (D-028)" },
                onFailure = { "load failed — ${it.message}" },
            )
            _cleanupBusy.value = null
        }
    }

    fun unloadCleanupModel() {
        cleanupEngine?.unload()
        _cleanupLoad.value = "unloaded — the next pass pays the cold load again"
    }

    fun onCleanupVariantChange(variant: CleanupPrompt.Variant) {
        _cleanupVariant.value = variant
    }

    fun onCleanupCustomUnitChange(unit: String) {
        _cleanupCustomUnit.value = unit
    }

    /**
     * Cleans a unit the user typed, carrying the latest cleaned text as context — the same shape the
     * pipeline uses, so a sentence evaluated here behaves like a dictated one.
     */
    fun cleanCustomUnit() {
        val unit = _cleanupCustomUnit.value.trim()
        if (unit.isEmpty() || _cleanupBusy.value != null) return
        viewModelScope.launch {
            _cleanupBusy.value = "cleaning your unit (${_cleanupVariant.value.name.lowercase()} rules)"
            val pass = engine().clean(unit, latestCleaned.ifBlank { null }, _cleanupVariant.value)
            _cleanupCases.value = listOf(
                CleanupCase(
                    label = "your unit — ${_cleanupVariant.value.label}",
                    input = unit,
                    output = pass.text,
                    cleaned = pass.cleaned,
                    detail = describe(pass),
                    prompt = pass.promptSent,
                ),
            ) + _cleanupCases.value
            if (pass.cleaned) {
                if (latestCleaned.isNotEmpty()) latestCleaned += " "
                latestCleaned += pass.text
            }
            _cleanupBusy.value = null
        }
    }

    /** The cleaned text so far, which is what a continuation pass receives as context (D-032). */
    private var latestCleaned = ""

    /**
     * Runs the fixed cases in order, each case's cleaned output becoming the *context* for the next
     * one — which is how the streaming pipeline will carry the tail (D-032). Two questions live here
     * that no document could answer: whether Gemma cleans Finnish, and whether the carried context
     * changes anything.
     */
    fun runCleanupCases() {
        if (_cleanupBusy.value != null) return
        viewModelScope.launch {
            val engine = engine()
            val results = mutableListOf<CleanupCase>()
            val cleanedSoFar = StringBuilder()
            _cleanupCases.value = emptyList()

            CLEANUP_CASES.forEachIndexed { index, spec ->
                _cleanupBusy.value = "case ${index + 1} of ${CLEANUP_CASES.size} — ${spec.label}"
                val pass = engine.clean(
                    unit = spec.unit,
                    context = if (spec.usesContext) cleanedSoFar.toString().ifBlank { null } else null,
                    variant = _cleanupVariant.value,
                )
                results += CleanupCase(
                    label = spec.label,
                    input = spec.unit,
                    output = pass.text,
                    cleaned = pass.cleaned,
                    detail = describe(pass),
                    prompt = pass.promptSent,
                )
                _cleanupCases.value = results.toList()
                if (pass.cleaned) {
                    if (cleanedSoFar.isNotEmpty()) cleanedSoFar.append(' ')
                    cleanedSoFar.append(pass.text)
                }
            }
            latestCleaned = cleanedSoFar.toString()
            _cleanupLoad.value = "resident · cold load was ${engine.lastLoadMs} ms"
            _cleanupBusy.value = null
        }
    }

    /**
     * D-014's two-call test, which was recorded as "undocumented, not unsupported" and stayed that way:
     * the same prompt twice. If the runtime keeps its KV cache between calls, the second pass's prefill
     * time collapses; if not, the two numbers match. Either answer is useful, and neither is a guess.
     */
    fun checkPrefixReuse() {
        if (_cleanupBusy.value != null) return
        viewModelScope.launch {
            val engine = engine()
            _cleanupBusy.value = "prefix-cache check — first pass"
            val first = engine.clean(PREFIX_CHECK_UNIT, null, _cleanupVariant.value)
            _cleanupBusy.value = "prefix-cache check — second pass, identical prompt"
            val second = engine.clean(PREFIX_CHECK_UNIT, null, _cleanupVariant.value)

            val ratio = if (first.promptTimeMs > 0.0) second.promptTimeMs / first.promptTimeMs else 1.0
            val verdict = when {
                !first.cleaned || !second.cleaned ->
                    "inconclusive — a pass failed closed (${second.note.ifBlank { first.note }})"
                ratio <= 0.35 ->
                    "prefix IS reused — second prefill %.0f ms vs %.0f ms (%.2f×)".format(
                        second.promptTimeMs, first.promptTimeMs, ratio,
                    )
                else ->
                    "prefix is NOT reused — second prefill %.0f ms vs %.0f ms (%.2f×), so the prompt " +
                        "is re-prefilled on every pass".format(second.promptTimeMs, first.promptTimeMs, ratio)
            }
            _cleanupCases.value = _cleanupCases.value + CleanupCase(
                label = "D-014 two-call check (identical prompt twice)",
                input = PREFIX_CHECK_UNIT,
                output = verdict,
                cleaned = first.cleaned && second.cleaned,
                detail = "first: prompt %.0f ms, %d tok · second: prompt %.0f ms, %d tok".format(
                    first.promptTimeMs, first.promptTokens, second.promptTimeMs, second.promptTokens,
                ),
                prompt = "",
            )
            _cleanupBusy.value = null
        }
    }

    /** What the pass cost, in the SDK's own numbers — the same ones the spike's tables quote. */
    private fun describe(pass: CleanupPass): String = if (!pass.cleaned) {
        "failed closed — ${pass.note}"
    } else {
        "ttft %.0f ms · prompt %.0f ms (%d tok) · decode %.0f ms (%d tok) · prefill %.0f tps · decode %.1f tps · stop %s".format(
            pass.ttftMs,
            pass.promptTimeMs,
            pass.promptTokens,
            pass.decodeTimeMs,
            pass.generatedTokens,
            if (pass.promptTimeMs > 0.0) pass.promptTokens * 1000.0 / pass.promptTimeMs else 0.0,
            if (pass.decodeTimeMs > 0.0) pass.generatedTokens * 1000.0 / pass.decodeTimeMs else 0.0,
            pass.stopReason.ifBlank { "unknown" },
        )
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

            val diff = WordDiffCalculator.verbatim(text)
            UsageStatsStore(app).record(
                durationSeconds = audioSeconds,
                rawWords = diff.rawWords,
                cleanedWords = 0,
                engine = Engine.LOCAL.label,
                deliveredWords = diff.deliveredWords,
                wordsRemoved = 0,
                wordsChanged = 0,
                wordsAdded = 0,
            )

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

                val diff = WordDiffCalculator.verbatim(response.text)
                val correctionsCount = response.corrections.size
                UsageStatsStore(app).record(
                    durationSeconds = audioSeconds,
                    rawWords = diff.rawWords,
                    cleanedWords = correctionsCount,
                    engine = Engine.REMOTE.label,
                    deliveredWords = diff.deliveredWords,
                    wordsRemoved = 0,
                    wordsChanged = correctionsCount,
                    wordsAdded = 0,
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
        // The settings screen owns this instance, so it releases it; the IME will own its own (5c).
        cleanupEngine?.unload()
        cleanupEngine = null
        recorder.cancel()
        localSession.abort()
        localEngine.unload()
        asrDispatcher.close()
        super.onCleared()
    }

    companion object {
        private const val TICK_MS = 200L

        /** One bench case. `usesContext` is the continuation case, which must see the previous output. */
        private data class CleanupCaseSpec(val label: String, val unit: String, val usesContext: Boolean = false)

        /**
         * Fixed sample text, not audio: the point is to see the *cleanup* stage's behaviour in English
         * and Finnish before any of it touches dictation. The real fixtures are dictation from the
         * phone (`~/ime-bench/cases.json`), which the app cannot reach — these are a smoke test, and
         * they are labelled as such on screen.
         */
        private val CLEANUP_CASES = listOf(
            CleanupCaseSpec(
                label = "English, one unit",
                unit = "okay so i think we should move the meeting to thursday or friday next week",
            ),
            CleanupCaseSpec(
                label = "Finnish, one unit",
                unit = "tuota niin tota mä ajattelin että voitaisiinko me siirtää se palaveri " +
                    "torstaille tai perjantaille ensi viikolla",
            ),
            CleanupCaseSpec(
                label = "Finnish, with a name to repair",
                unit = "joo ja sit mä kysyin matiakselta että onko se gemma malli jo ladattu",
            ),
            CleanupCaseSpec(
                label = "Continuation — carries the previous output as context",
                unit = "ja meidän pitää vielä varata se kokoushuone keskiviikkoon mennessä",
                usesContext = true,
            ),
        )

        private const val PREFIX_CHECK_UNIT = "yes i think that works lets go with friday"
    }
}
