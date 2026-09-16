package fi.ak.smartvoice.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.LocalAsrEngine
import fi.ak.smartvoice.asr.LocalAsrSession
import fi.ak.smartvoice.asr.ModelDownloadProgress
import fi.ak.smartvoice.asr.ModelDownloadResult
import fi.ak.smartvoice.asr.ModelDownloader
import fi.ak.smartvoice.audio.WavRecorder
import fi.ak.smartvoice.asr.ListenerTimings
import fi.ak.smartvoice.cleanup.CleanupEngine
import fi.ak.smartvoice.cleanup.CleanupPrompt
import fi.ak.smartvoice.cleanup.LiteRtLmCleanupEngine
import fi.ak.smartvoice.cleanup.LiteRtLmDownloadProgress
import fi.ak.smartvoice.cleanup.LiteRtLmDownloadResult
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.cleanup.LiteRtLmModelStore
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import fi.ak.smartvoice.cleanup.LiteRtCompatibilityTester
import fi.ak.smartvoice.data.AppSettings
import fi.ak.smartvoice.data.AppThemeMode
import fi.ak.smartvoice.data.CleaningProfile
import fi.ak.smartvoice.data.LiteRtProcessor
import fi.ak.smartvoice.data.DictionaryEntry
import fi.ak.smartvoice.data.DictionaryStore
import fi.ak.smartvoice.data.ProfileStore
import fi.ak.smartvoice.data.UsageStatsData
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
import fi.ak.smartvoice.ui.onboarding.OnboardingModelPreset
import fi.ak.smartvoice.ui.onboarding.OnboardingModelPresets
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
import java.util.Locale
import java.util.concurrent.Executors
import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import fi.ak.smartvoice.floating.FloatingOverlayService
import fi.ak.smartvoice.floating.VoiceAccessibilityService

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
    data class Done(
        val outcome: DictationOutcome,
        val roundTripMs: Long?,
        val rawText: String? = null,
        val diff: fi.ak.smartvoice.data.WordDiffResult? = null,
    ) : DictationUiState
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
 * A timing field on the settings screen: the text as typed, and whether it currently parses to a usable
 * number. `invalid` is what lets the screen say "that is not a number of seconds" instead of silently
 * keeping the old value.
 */
data class TimingField(val text: String, val seconds: Float?, val invalid: Boolean)



class DictationViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val settings = AppSettings(application)
    private val dictionaryStore = DictionaryStore(application)
    private val recorder = WavRecorder()
    private var ticker: Job? = null

    /** Same rule as the IME: one worker, because a streaming session is serial. */
    private val asrDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smartvoice-asr")
    }.asCoroutineDispatcher()

    val asrCatalog: List<AsrModelSpec> = AsrModels.CATALOG

    private val _selectedAsrModel = MutableStateFlow(settings.asrModel)
    val selectedAsrModel: StateFlow<AsrModelSpec> = _selectedAsrModel.asStateFlow()

    private val modelDirectory: File
        get() = _selectedAsrModel.value.directory(app.filesDir)

    /**
     * The pause threshold is *part of the recogniser's config*, so it is passed at construction and
     * applied again through [onPauseSilenceChange] (D-035). Everything else about the listener is a
     * software rule and needs no rebuild.
     */
    private val localEngine = LocalAsrEngine(
        modelDirectory = settings.asrModel.directory(app.filesDir),
        pauseSilenceSeconds = settings.pauseSilenceSeconds,
        modelSpec = settings.asrModel,
        assetManager = app.assets,
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

    private val _unloadOnKeyboardClose = MutableStateFlow(settings.unloadOnKeyboardClose)
    val unloadOnKeyboardClose: StateFlow<Boolean> = _unloadOnKeyboardClose.asStateFlow()

    private val _modelIdleTimeoutMinutes = MutableStateFlow(settings.modelIdleTimeoutMinutes)
    val modelIdleTimeoutMinutes: StateFlow<Int> = _modelIdleTimeoutMinutes.asStateFlow()

    private val _enginePreference = MutableStateFlow(settings.enginePreference)
    val enginePreference: StateFlow<EnginePreference> = _enginePreference.asStateFlow()

    private val _pauseField = MutableStateFlow(field(settings.pauseSilenceSeconds))
    val pauseField: StateFlow<TimingField> = _pauseField.asStateFlow()

    private val _stopField = MutableStateFlow(field(settings.stopAfterSilenceSeconds))
    val stopField: StateFlow<TimingField> = _stopField.asStateFlow()

    private val _healingSentencesCount = MutableStateFlow(settings.healingSentencesCount)
    val healingSentencesCount: StateFlow<Int> = _healingSentencesCount.asStateFlow()

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

    private var cleanupEngine: CleanupEngine? = null

    /** Non-null while a bench step runs, and it is the step's own description. */
    private val _cleanupBusy = MutableStateFlow<String?>(null)
    val cleanupBusy: StateFlow<String?> = _cleanupBusy.asStateFlow()

    /** Cold-load evidence for the cleanup model (D-010 rule 2), or an unload/failure message. */
    private val _cleanupLoad = MutableStateFlow<String?>(null)
    val cleanupLoad: StateFlow<String?> = _cleanupLoad.asStateFlow()

    private val _cleanupVariant = MutableStateFlow(CleanupPrompt.Variant.DEFAULT)
    val cleanupVariant: StateFlow<CleanupPrompt.Variant> = _cleanupVariant.asStateFlow()

    fun selectAsrModel(spec: AsrModelSpec) {
        if (spec.id == _selectedAsrModel.value.id) return
        settings.asrModelId = spec.id
        _selectedAsrModel.value = spec
        localEngine.applyModel(spec, app.filesDir)
        _modelStatus.value = currentModelStatus()
        _loadProbe.value = ""
    }

    init {
        refreshLitertInstalled()
        probeHardwareCompatibility()
    }

    // ------------------------------------------------------------------ LLM cleanup (LiteRT-LM)
    val litertCatalog: List<LiteRtLmModelSpec> = LiteRtLmModels.ALL

    private val _selectedLitertModel = MutableStateFlow(settings.litertModel)
    val selectedLitertModel: StateFlow<LiteRtLmModelSpec> = _selectedLitertModel.asStateFlow()

    fun selectLitertModel(spec: LiteRtLmModelSpec) {
        if (spec.id == _selectedLitertModel.value.id) return
        settings.litertModelId = spec.id
        _selectedLitertModel.value = spec
        cleanupEngine?.unload()
        cleanupEngine = null
        _cleanupLoad.value = null
        _cleanupBusy.value = null
    }

    private val _litertProcessor = MutableStateFlow(settings.litertProcessor)
    val litertProcessor: StateFlow<LiteRtProcessor> = _litertProcessor.asStateFlow()

    private val _litertGpuSupported = MutableStateFlow(settings.litertGpuSupported)
    val litertGpuSupported: StateFlow<Boolean> = _litertGpuSupported.asStateFlow()

    private val _litertCompatibilityNote = MutableStateFlow(settings.litertCompatibilityNote)
    val litertCompatibilityNote: StateFlow<String> = _litertCompatibilityNote.asStateFlow()

    fun setLitertProcessor(processor: LiteRtProcessor) {
        settings.litertProcessor = processor
        _litertProcessor.value = processor
        cleanupEngine?.unload()
        cleanupEngine = null
    }

    private val _litertSpeculativeDecoding = MutableStateFlow(settings.litertSpeculativeDecoding)
    val litertSpeculativeDecoding: StateFlow<Boolean> = _litertSpeculativeDecoding.asStateFlow()

    fun setLitertSpeculativeDecoding(enabled: Boolean) {
        settings.litertSpeculativeDecoding = enabled
        _litertSpeculativeDecoding.value = enabled
        cleanupEngine?.unload()
        cleanupEngine = null
    }

    fun probeHardwareCompatibility() {
        viewModelScope.launch {
            val result = LiteRtCompatibilityTester.probe(app, saveToSettings = true)
            _litertGpuSupported.value = result.gpuSupported
            _litertCompatibilityNote.value = result.summaryNote
        }
    }

    private val _litertInstalled = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val litertInstalled: StateFlow<Map<String, Boolean>> = _litertInstalled.asStateFlow()

    private val _litertPulling = MutableStateFlow<String?>(null)
    val litertPulling: StateFlow<String?> = _litertPulling.asStateFlow()

    private val _litertProgress = MutableStateFlow<LiteRtLmDownloadProgress?>(null)
    val litertProgress: StateFlow<LiteRtLmDownloadProgress?> = _litertProgress.asStateFlow()

    private var litertJob: Job? = null

    fun refreshLitertInstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            val map = LiteRtLmModels.ALL.associate { it.id to LiteRtLmModelStore.isInstalled(app, it) }
            _litertInstalled.value = map
        }
    }

    fun downloadLitertModel(spec: LiteRtLmModelSpec) {
        if (_litertPulling.value != null) return
        litertJob = viewModelScope.launch {
            _litertPulling.value = spec.id
            _litertProgress.value = null
            LiteRtLmModelStore.downloadFlow(app, spec, isCancelled = { litertJob?.isCancelled == true })
                .collect { (progress, result) ->
                    _litertProgress.value = progress
                    when (result) {
                        LiteRtLmDownloadResult.Complete -> {
                            _litertPulling.value = null
                            refreshLitertInstalled()
                        }
                        is LiteRtLmDownloadResult.Failed -> {
                            _litertPulling.value = null
                            _cleanupLoad.value = "LiteRT download failed: ${result.reason}"
                        }
                        LiteRtLmDownloadResult.Cancelled -> {
                            _litertPulling.value = null
                        }
                        null -> {}
                    }
                }
        }
    }

    fun deleteLitertModel(spec: LiteRtLmModelSpec) {
        viewModelScope.launch {
            LiteRtLmModelStore.remove(app, spec)
            if (cleanupEngine?.isServing(settings) == true) {
                cleanupEngine?.unload()
                cleanupEngine = null
            }
            refreshLitertInstalled()
        }
    }

    fun cancelLitertDownload() {
        litertJob?.cancel()
        _litertPulling.value = null
        _litertProgress.value = null
    }

    // ------------------------------------------------------------------ theming
    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        settings.themeMode = mode
        _themeMode.value = mode
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

    fun onUnloadOnKeyboardCloseChange(enabled: Boolean) {
        settings.unloadOnKeyboardClose = enabled
        _unloadOnKeyboardClose.value = enabled
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
        _timingNote.value = String.format(Locale.US, "Sentence pause set to %.1f s", parsed)
    }

    fun onStopFieldChange(text: String) {
        val parsed = ListenerTimings.parseSeconds(text, AppSettings.MAX_TIMING_SECONDS)
        _stopField.value = TimingField(text, parsed, parsed == null)
        if (parsed == null) return
        settings.stopAfterSilenceSeconds = parsed
        _timingNote.value = if (parsed == 0f) {
            "Auto-stop disabled (manual stop only)"
        } else {
            String.format(Locale.US, "Auto-stop set to %.1f s", parsed)
        }
    }

    /**
     * Slider entry points for the Basic screen's two timing selectors. The slider owns the 0.5 s
     * step; these keep the single write path — settings, the recogniser's config and the status
     * note — in one place rather than duplicating it in the screen.
     */
    fun setPauseSilenceSeconds(seconds: Float) {
        onPauseFieldChange(ListenerTimings.formatSeconds(seconds))
    }

    fun setStopAfterSilenceSeconds(seconds: Float) {
        onStopFieldChange(ListenerTimings.formatSeconds(seconds))
    }

    fun onHealingSentencesCountChange(count: Int) {
        val clamped = count.coerceIn(0, 10)
        _healingSentencesCount.value = clamped
        settings.healingSentencesCount = clamped
    }

    fun resetTimingsToDefaults() {
        onPauseFieldChange(ListenerTimings.formatSeconds(AppSettings.DEFAULT_PAUSE_SILENCE_S))
        onStopFieldChange(ListenerTimings.formatSeconds(AppSettings.DEFAULT_STOP_AFTER_SILENCE_S))
        onHealingSentencesCountChange(AppSettings.DEFAULT_HEALING_SENTENCES_COUNT)
    }

    // ------------------------------------------------------------------ Floating Dictation Pill
    private val _floatingPillEnabled = MutableStateFlow(settings.floatingPillEnabled)
    val floatingPillEnabled: StateFlow<Boolean> = _floatingPillEnabled.asStateFlow()

    private val _floatingPillOnlyWhenKeyboardOpen = MutableStateFlow(settings.floatingPillOnlyWhenKeyboardOpen)
    val floatingPillOnlyWhenKeyboardOpen: StateFlow<Boolean> = _floatingPillOnlyWhenKeyboardOpen.asStateFlow()

    private val _floatingPillAutoCommit = MutableStateFlow(settings.floatingPillAutoCommit)
    val floatingPillAutoCommit: StateFlow<Boolean> = _floatingPillAutoCommit.asStateFlow()

    private val _pillFlickFriction = MutableStateFlow(settings.pillFlickFriction)
    val pillFlickFriction: StateFlow<Float> = _pillFlickFriction.asStateFlow()

    private val _isOverlayPermissionGranted = MutableStateFlow(Settings.canDrawOverlays(app))
    val isOverlayPermissionGranted: StateFlow<Boolean> = _isOverlayPermissionGranted.asStateFlow()

    private val _isAccessibilityPermissionGranted = MutableStateFlow(checkAccessibilityPermission())
    val isAccessibilityPermissionGranted: StateFlow<Boolean> = _isAccessibilityPermissionGranted.asStateFlow()

    fun refreshFloatingPillPermissions() {
        _isOverlayPermissionGranted.value = Settings.canDrawOverlays(app)
        _isAccessibilityPermissionGranted.value = checkAccessibilityPermission()
    }

    private fun checkAccessibilityPermission(): Boolean {
        return VoiceAccessibilityService.isRunning() || runCatching {
            val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            services.any { it.resolveInfo.serviceInfo.packageName == app.packageName }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------------
    // Home dashboard state: permission health, usage stats, profile switcher.
    // ------------------------------------------------------------------------

    private val profileStore = ProfileStore(application)
    private val usageStatsStore = UsageStatsStore(application)

    private val _isMicPermissionGranted = MutableStateFlow(hasMicPermission())
    val isMicPermissionGranted: StateFlow<Boolean> = _isMicPermissionGranted.asStateFlow()

    private val _profiles = MutableStateFlow(profileStore.allProfiles())
    val profiles: StateFlow<List<CleaningProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(profileStore.activeProfile().id)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _usageStats = MutableStateFlow(usageStatsStore.load())
    val usageStats: StateFlow<UsageStatsData> = _usageStats.asStateFlow()

    /** The shared dictionary, exposed reactively so Profiles and the pill never disagree. */
    private val _dictionaryEntries = MutableStateFlow(dictionaryStore.getEntries())
    val dictionaryEntries: StateFlow<List<DictionaryEntry>> = _dictionaryEntries.asStateFlow()

    /**
     * Re-reads everything the dashboard shows. Called when Home resumes, because
     * dictation happens in other components (the pill overlay, the IME) whose
     * writes this ViewModel never sees directly.
     */
    fun refreshDashboard() {
        refreshFloatingPillPermissions()
        _isMicPermissionGranted.value = hasMicPermission()
        if (_modelStatus.value !is ModelStatus.Downloading) {
            _modelStatus.value = currentModelStatus()
        }
        refreshProfiles()
        refreshUsageStats()
        refreshInstalledPresets()
        refreshDictionary()
    }

    fun refreshProfiles() {
        _profiles.value = profileStore.allProfiles()
        _activeProfileId.value = profileStore.activeProfile().id
    }

    fun setActiveProfile(id: String) {
        profileStore.setActiveProfile(id)
        refreshProfiles()
    }

    /** Saves a custom profile (created or edited) and re-reads the list. */
    fun saveCustomProfile(profile: CleaningProfile) {
        profileStore.saveCustomProfile(profile)
        refreshProfiles()
    }

    /** Deletes a custom profile. The store falls back to the built-in when it was active. */
    fun deleteCustomProfile(id: String) {
        profileStore.deleteCustomProfile(id)
        refreshProfiles()
    }

    fun refreshDictionary() {
        _dictionaryEntries.value = dictionaryStore.getEntries()
    }

    /** Adds `incorrect -> correct`, or a bare "always spell" term when `incorrect` is blank. */
    fun addDictionaryCorrection(correct: String, incorrect: String? = null) {
        val heard = incorrect?.trim()?.takeIf { it.isNotBlank() }
        dictionaryStore.addCorrection(correct, listOfNotNull(heard))
        refreshDictionary()
    }

    fun removeDictionaryEntry(correct: String) {
        dictionaryStore.removeEntry(correct)
        refreshDictionary()
    }

    fun removeDictionaryCorrection(correct: String, incorrect: String) {
        dictionaryStore.removeCorrection(correct, incorrect)
        refreshDictionary()
    }

    fun refreshUsageStats() {
        _usageStats.value = usageStatsStore.load()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------------
    // Onboarding wizard: the first-launch flag and the recommended-model download.
    // ------------------------------------------------------------------------

    /**
     * Whether first launch should show the wizard. It is skipped when all three permissions are
     * already granted, so an install that was set up outside the wizard (or an app update on a
     * permissioned device) goes straight to Home.
     *
     * Deliberately a one-shot question, not a live flow: once the wizard is on screen it must not
     * disappear the moment the last permission is granted, because the models step is still ahead.
     */
    fun shouldShowOnboarding(): Boolean =
        !settings.onboardingCompleted &&
            !(hasMicPermission() && Settings.canDrawOverlays(app) && checkAccessibilityPermission())

    /** Finishes the wizard for good. The flag is never cleared, so it shows only once. */
    fun completeOnboarding() {
        settings.onboardingCompleted = true
    }

    /** How the wizard's "download the recommended models" step is getting on. */
    sealed interface OnboardingDownload {
        /** Which half of the preset is being fetched. */
        enum class Stage(val label: String) {
            ASR("speech model"),
            CLEANUP("cleanup model"),
        }

        data object Idle : OnboardingDownload
        data class Running(val presetId: String, val stage: Stage, val overall: Float) : OnboardingDownload
        data class Ready(val presetId: String) : OnboardingDownload
        data class Failed(val presetId: String, val message: String) : OnboardingDownload
    }

    private val _onboardingDownload = MutableStateFlow<OnboardingDownload>(OnboardingDownload.Idle)
    val onboardingDownload: StateFlow<OnboardingDownload> = _onboardingDownload.asStateFlow()

    /** Presets whose speech **and** cleanup models are both fully on disk. */
    private val _installedPresetIds = MutableStateFlow(installedPresetIds())
    val installedPresetIds: StateFlow<Set<String>> = _installedPresetIds.asStateFlow()

    private var onboardingJob: Job? = null

    @Volatile
    private var cancelOnboarding = false

    private fun installedPresetIds(): Set<String> =
        OnboardingModelPresets.ALL
            .filter { preset ->
                preset.asrModel.isInstalled(preset.asrModel.directory(app.filesDir)) &&
                    LiteRtLmModelStore.isInstalled(app, preset.llmModel)
            }
            .map { it.id }
            .toSet()

    fun refreshInstalledPresets() {
        _installedPresetIds.value = installedPresetIds()
    }

    /**
     * Downloads a preset's two models end to end: speech model first, then cleanup model.
     * Sequenced here rather than in the screen so a rotation mid-download cannot strand the
     * second half, and both models become the active selection as soon as they land.
     */
    fun startOnboardingDownload(preset: OnboardingModelPreset) {
        if (_onboardingDownload.value is OnboardingDownload.Running) return
        cancelOnboarding = false

        val asrBytes = preset.asrModel.totalBytes
        val cleanupBytes = preset.llmModel.approximateBytes
        val totalBytes = (asrBytes + cleanupBytes).coerceAtLeast(1L)

        onboardingJob = viewModelScope.launch {
            // Adopt the preset, so the models the user chose are the ones the app will run.
            selectAsrModel(preset.asrModel)
            selectLitertModel(preset.llmModel)

            _onboardingDownload.value =
                OnboardingDownload.Running(preset.id, OnboardingDownload.Stage.ASR, 0f)
            val directory = preset.asrModel.directory(app.filesDir)
            val asrResult = withContext(Dispatchers.IO) {
                ModelDownloader(directory, preset.asrModel) { progress ->
                    _onboardingDownload.value = OnboardingDownload.Running(
                        presetId = preset.id,
                        stage = OnboardingDownload.Stage.ASR,
                        overall = (progress.overall * asrBytes / totalBytes).coerceIn(0f, 1f),
                    )
                }.download(isCancelled = { cancelOnboarding })
            }
            when (asrResult) {
                ModelDownloadResult.Complete -> _modelStatus.value = currentModelStatus()
                is ModelDownloadResult.Cancelled -> {
                    _onboardingDownload.value = OnboardingDownload.Idle
                    return@launch
                }
                is ModelDownloadResult.Failed -> {
                    _onboardingDownload.value = OnboardingDownload.Failed(preset.id, asrResult.message)
                    return@launch
                }
            }

            _onboardingDownload.value = OnboardingDownload.Running(
                presetId = preset.id,
                stage = OnboardingDownload.Stage.CLEANUP,
                overall = (asrBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
            )
            var cleanupFailure: String? = null
            var cleanupCancelled = false
            LiteRtLmModelStore.downloadFlow(app, preset.llmModel, isCancelled = { cancelOnboarding })
                .collect { (progress, result) ->
                    when (result) {
                        null -> _onboardingDownload.value = OnboardingDownload.Running(
                            presetId = preset.id,
                            stage = OnboardingDownload.Stage.CLEANUP,
                            overall = ((asrBytes + cleanupBytes * progress.percent) / totalBytes.toFloat())
                                .coerceIn(0f, 1f),
                        )

                        LiteRtLmDownloadResult.Complete -> Unit
                        LiteRtLmDownloadResult.Cancelled -> cleanupCancelled = true
                        is LiteRtLmDownloadResult.Failed -> cleanupFailure = result.reason
                    }
                }

            if (cleanupCancelled) {
                _onboardingDownload.value = OnboardingDownload.Idle
                return@launch
            }
            val cleanupError = cleanupFailure
            if (cleanupError != null) {
                _onboardingDownload.value = OnboardingDownload.Failed(preset.id, cleanupError)
                return@launch
            }
            refreshLitertInstalled()
            _modelStatus.value = currentModelStatus()
            refreshInstalledPresets()
            _onboardingDownload.value = OnboardingDownload.Ready(preset.id)
        }
    }

    fun cancelOnboardingDownload() {
        cancelOnboarding = true
        _onboardingDownload.value = OnboardingDownload.Idle
    }

    fun setFloatingPillEnabled(enabled: Boolean) {
        settings.floatingPillEnabled = enabled
        _floatingPillEnabled.value = enabled
        if (enabled) {
            if (Settings.canDrawOverlays(app)) {
                FloatingOverlayService.start(app)
            }
        } else {
            FloatingOverlayService.stop(app)
        }
    }

    fun setFloatingPillOnlyWhenKeyboardOpen(enabled: Boolean) {
        settings.floatingPillOnlyWhenKeyboardOpen = enabled
        _floatingPillOnlyWhenKeyboardOpen.value = enabled
        FloatingOverlayService.updateVisibility(app, isKeyboardOpen = false, hasEditableFocus = false)
    }

    fun setFloatingPillAutoCommit(enabled: Boolean) {
        settings.floatingPillAutoCommit = enabled
        _floatingPillAutoCommit.value = enabled
    }

    /**
     * The pill's flick friction. Written straight through to settings; the overlay service reads it
     * per flick, so the change is felt on the next flick without restarting anything.
     */
    fun setPillFlickFriction(friction: Float) {
        val clamped = friction.coerceIn(
            AppSettings.MIN_PILL_FLICK_FRICTION,
            AppSettings.MAX_PILL_FLICK_FRICTION,
        )
        settings.pillFlickFriction = clamped
        _pillFlickFriction.value = clamped
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
        if (_selectedAsrModel.value.isInstalled(modelDirectory)) ModelStatus.Installed else ModelStatus.Missing

    fun downloadModel(spec: AsrModelSpec = _selectedAsrModel.value) {
        if ((_modelStatus.value as? ModelStatus.Downloading) != null) return
        cancelDownload = false

        viewModelScope.launch {
            val dir = spec.directory(app.filesDir)
            _modelStatus.value = ModelStatus.Downloading(
                ModelDownloadProgress(0f, 0, spec.files.first().name, 0, spec.files.size),
            )
            val result = withContext(Dispatchers.IO) {
                ModelDownloader(dir, spec) { progress -> _modelStatus.value = ModelStatus.Downloading(progress) }
                    .download(isCancelled = { cancelDownload })
            }
            _modelStatus.value = when (result) {
                ModelDownloadResult.Complete -> {
                    // A completed download is worth proving: load it once, here, and report the ms.
                    probeLocalEngine()
                    ModelStatus.Installed
                }
                is ModelDownloadResult.Cancelled ->
                    if (spec.isInstalled(dir)) {
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
        val spec = _selectedAsrModel.value
        val dir = spec.directory(app.filesDir)
        if (!spec.isInstalled(dir)) {
            _loadProbe.value = "not installed — nothing to load"
            return
        }
        _loadProbe.value = "loading…"
        viewModelScope.launch {
            var loadTime = 0L
            val availability = withContext(asrDispatcher) {
                try {
                    val res = localEngine.load()
                    loadTime = localEngine.lastLoadMillis
                    res
                } finally {
                    localEngine.unload()
                }
            }
            _loadProbe.value = when (availability) {
                LocalAsrEngine.Availability.Loaded -> "Verified in ${loadTime} ms (unloaded)"
                LocalAsrEngine.Availability.NotInstalled -> "not downloaded"
                LocalAsrEngine.Availability.NotLoaded -> "not loaded"
                is LocalAsrEngine.Availability.Unavailable -> "Verification failed: ${availability.reason}"
            }
        }
    }

    /**
     * Frees model storage and unloads resident instances.
     */
    fun deleteModel(spec: AsrModelSpec = _selectedAsrModel.value) {
        viewModelScope.launch {
            if (spec.id == _selectedAsrModel.value.id) {
                withContext(asrDispatcher) { localEngine.unload() }
            }
            withContext(Dispatchers.IO) {
                spec.directory(app.filesDir).deleteRecursively()
            }
            _modelStatus.value = currentModelStatus()
            _loadProbe.value = ""
        }
    }

    // ------------------------------------------------------------------ cleanup model (D-030)

    // ------------------------------------------------------------------ cleanup test bench

    /**
     * One engine per model, reused across bench runs, so the cold load is paid once.
     */
    private fun engine(): CleanupEngine {
        cleanupEngine?.let { existing ->
            if (existing.isServing(settings)) return existing
            existing.unload()
        }
        return LiteRtLmCleanupEngine(
            context = app,
            modelSpec = settings.litertModel,
            processor = settings.litertProcessor,
            speculativeDecoding = settings.litertSpeculativeDecoding,
            dictionary = dictionaryStore.getWords(),
            dictionaryEntries = dictionaryStore.getEntries(),
        ).also { cleanupEngine = it }
    }

    fun loadCleanupModel() {
        if (_cleanupBusy.value != null) return
        val effectiveProcName = if (!settings.litertModel.supportsGpu) "CPU" else settings.litertProcessor.name
        val currentLabel = "${settings.litertModel.label} (LiteRT-LM $effectiveProcName)"
        viewModelScope.launch {
            _cleanupBusy.value = "loading $currentLabel…"
            val engine = engine()
            val res = engine.load()
            _cleanupLoad.value = res.fold(
                onSuccess = { "Verified in ${engine.lastLoadMs} ms (unloaded)" },
                onFailure = { "Verification failed: ${it.message}" },
            )
            // Immediately unload to ensure GPU memory is not held resident
            engine.unload()
            cleanupEngine = null
            System.gc()
            _cleanupBusy.value = null
        }
    }

    fun onCleanupVariantChange(variant: CleanupPrompt.Variant) {
        _cleanupVariant.value = variant
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
                        if (recorder.capturedSeconds >= 300.0) {
                            stopAndTranscribe()
                            return@launch
                        }
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

    /**
     * Transcribes an audio file on-device using the selected ASR model and cleans it with the LLM backend.
     */
    fun transcribeAudioFile(samples: FloatArray, fileName: String, audioDurationSec: Float) {
        if (_state.value is DictationUiState.Working || _state.value is DictationUiState.Recording) return
        _state.value = DictationUiState.Working("Transcribing audio file: $fileName…", Engine.LOCAL)
        viewModelScope.launch {
            try {
                val asrStart = SystemClock.elapsedRealtime()
                withContext(asrDispatcher) {
                    localEngine.load()
                    localEngine.startSession(settings.language)
                    val chunkSize = 16000 // 1 second chunks
                    for (i in samples.indices step chunkSize) {
                        val end = minOf(i + chunkSize, samples.size)
                        val chunk = samples.copyOfRange(i, end)
                        localEngine.accept(chunk)
                    }
                }
                val rawText = withContext(asrDispatcher) { localEngine.finish().trim() }
                val asrMs = SystemClock.elapsedRealtime() - asrStart

                if (rawText.isBlank()) {
                    _state.value = DictationUiState.Done(
                        outcome = DictationOutcome(
                            text = "(no speech detected in file)",
                            engine = Engine.LOCAL,
                            note = "Audio file decoded ($audioDurationSec s) but no speech recognized",
                            audioSeconds = audioDurationSec.toDouble(),
                            elapsedMs = asrMs,
                        ),
                        roundTripMs = null,
                        rawText = "",
                        diff = null,
                    )
                    return@launch
                }

                if (settings.cleanupEnabled) {
                    _state.value = DictationUiState.Working("Cleaning up transcription with LLM…", Engine.LOCAL)
                    val cleanupEngine = engine()
                    val pass = cleanupEngine.clean(
                        unit = rawText,
                        context = null,
                        variant = _cleanupVariant.value,
                    )
                    val totalMs = SystemClock.elapsedRealtime() - asrStart
                    val diff = WordDiffCalculator.calculate(rawText = rawText, cleanedText = pass.text)

                    _state.value = DictationUiState.Done(
                        outcome = DictationOutcome(
                            text = pass.text,
                            engine = Engine.LOCAL,
                            note = "File: $fileName ($audioDurationSec s) · ASR: $asrMs ms · LLM: %.0f ms".format(pass.ttftMs),
                            audioSeconds = audioDurationSec.toDouble(),
                            elapsedMs = totalMs,
                        ),
                        roundTripMs = null,
                        rawText = rawText,
                        diff = diff,
                    )
                } else {
                    val diff = WordDiffCalculator.verbatim(rawText)
                    _state.value = DictationUiState.Done(
                        outcome = DictationOutcome(
                            text = rawText,
                            engine = Engine.LOCAL,
                            note = "File: $fileName ($audioDurationSec s) · ASR: $asrMs ms (cleanup off)",
                            audioSeconds = audioDurationSec.toDouble(),
                            elapsedMs = asrMs,
                        ),
                        roundTripMs = null,
                        rawText = rawText,
                        diff = diff,
                    )
                }
            } catch (e: Exception) {
                _state.value = DictationUiState.Failed("file_transcribe", e.message ?: "Failed to transcribe audio file")
            }
        }
    }

    private fun client() = TranscribeClient(_serverUrl.value)

    fun onAppBackgrounded() {
        ticker?.cancel()
        litertJob?.cancel()
        if (recorder.isRecording) {
            recorder.cancel()
            localSession.abort()
            _state.value = DictationUiState.Idle
        }
        viewModelScope.launch {
            withContext(asrDispatcher) {
                localEngine.unload()
            }
            cleanupEngine?.unload()
            cleanupEngine = null
            System.gc()
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        litertJob?.cancel()
        onboardingJob?.cancel()
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
    }
}
