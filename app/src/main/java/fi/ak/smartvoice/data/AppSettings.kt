package fi.ak.smartvoice.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.asr.ListenerTimings
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import fi.ak.smartvoice.engine.EnginePreference

enum class LiteRtProcessor(val id: String, val label: String, val note: String) {
    GPU("gpu", "GPU (OpenCL / Vulkan)", "Fastest performance (Recommended)"),
    CPU("cpu", "CPU", "Standard processor fallback"),
}

enum class AppThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System Default"),
    LIGHT("light", "Light Theme"),
    DARK("dark", "Dark Theme"),
}

/**
 * The one place the app's settings are read and written.
 *
 * Exists because both entry points need them: `MainActivity` (where the server URL is edited) and
 * the dictation panel (where they are used per utterance). Two copies of a preference key would
 * drift, and the IME has no screen to fix a wrong value from.
 *
 * `cleanupEnabled` and `language` are the contract's own request fields (`cleanup: off|full`,
 * `language: auto|<ISO-639-1>`), so the panel's toggles are a direct view of what is sent.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

    /** v1 ships cleanup on: it is the feature the server tier exists for. */
    var cleanupEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLEANUP, true)
        set(value) = prefs.edit { putBoolean(KEY_CLEANUP, value) }

    /** "auto" or an ISO-639-1 code. Auto is right until language detection proves otherwise. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_AUTO).orEmpty()
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    /**
     * Which tier dictates. **Local by default** (D-027): the app's premise is that it works with
     * nothing but the phone, so the server is the explicit choice, not the fallback.
     */
    var enginePreference: EnginePreference
        get() = EnginePreference.fromStored(prefs.getString(KEY_ENGINE, null))
        set(value) = prefs.edit { putString(KEY_ENGINE, value.storedValue) }

    /**
     * The listener's timings (D-035, corrected by D-037). Settings rather than constants because these are
     * the numbers a person tunes by *using* the thing.
     *
     * Stored and edited as **seconds, as text**: a tunable value must not be limited to a handful of chips,
     * `2.5` is a legitimate answer, and `0` on the auto-stop means "never stop — I will close it myself".
     */
    var pauseSilenceSeconds: Float
        get() = prefs.getFloat(KEY_PAUSE_SILENCE_S, DEFAULT_PAUSE_SILENCE_S)
        set(value) = prefs.edit { putFloat(KEY_PAUSE_SILENCE_S, value) }

    /** Text-stability fallback for the unit boundary. Only used when the recogniser says nothing. */
    var stableSilenceSeconds: Float
        get() = prefs.getFloat(KEY_STABLE_SILENCE_S, DEFAULT_STABLE_SILENCE_S)
        set(value) = prefs.edit { putFloat(KEY_STABLE_SILENCE_S, value) }

    /** Silence after speech that ends the recording. **0 means it never stops on its own.** */
    var stopAfterSilenceSeconds: Float
        get() = prefs.getFloat(KEY_STOP_AFTER_SILENCE_S, DEFAULT_STOP_AFTER_SILENCE_S)
        set(value) = prefs.edit { putFloat(KEY_STOP_AFTER_SILENCE_S, value) }

    /** What the ASR session needs for one utterance. */
    fun listenerTimings(): ListenerTimings = ListenerTimings(
        // Floored on the way in: `0` legitimately means "never stop" for the auto-stop, and would mean
        // "cut at once" for the two boundary rules. A field that accepts both must not trust the typo.
        fallbackQuietSeconds = maxOf(MIN_BOUNDARY_SECONDS, stableSilenceSeconds).toDouble(),
        stopAfterSilenceSeconds = stopAfterSilenceSeconds.toDouble(),
    )

    /**
     * Which ASR model the local tier runs (Nemotron streaming or offline Whisper/Parakeet).
     */
    var asrModelId: String
        get() = prefs.getString(KEY_ASR_MODEL, null) ?: AsrModels.DEFAULT_MODEL_ID
        set(value) = prefs.edit { putString(KEY_ASR_MODEL, value) }

    val asrModel: AsrModelSpec
        get() = AsrModels.byId(asrModelId)

    /**
     * Which LiteRT-LM model is selected for cleanup.
     */
    var litertModelId: String
        get() {
            val saved = prefs.getString(KEY_LITERT_MODEL, null)
            return if (saved != null && LiteRtLmModels.ALL.any { it.id == saved }) {
                saved
            } else {
                LiteRtLmModels.DEFAULT.id
            }
        }
        set(value) = prefs.edit { putString(KEY_LITERT_MODEL, value) }

    val litertModel: LiteRtLmModelSpec
        get() = LiteRtLmModels.byId(litertModelId)

    /**
     * Preferred processor for LiteRT-LM (GPU or CPU).
     */
    var litertProcessor: LiteRtProcessor
        get() = when (prefs.getString(KEY_LITERT_PROCESSOR, null)) {
            LiteRtProcessor.CPU.id -> LiteRtProcessor.CPU
            else -> LiteRtProcessor.GPU
        }
        set(value) = prefs.edit { putString(KEY_LITERT_PROCESSOR, value.id) }

    var litertGpuSupported: Boolean
        get() = prefs.getBoolean(KEY_LITERT_GPU_SUPPORTED, true)
        set(value) = prefs.edit { putBoolean(KEY_LITERT_GPU_SUPPORTED, value) }

    var litertCompatibilityNote: String
        get() = prefs.getString(KEY_LITERT_COMPAT_NOTE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_LITERT_COMPAT_NOTE, value) }

    /**
     * Whether speculative decoding (Multi-Token Prediction / MTP) is enabled for supported LiteRT-LM models.
     */
    var litertSpeculativeDecoding: Boolean
        get() = prefs.getBoolean(KEY_LITERT_SPECULATIVE_DECODING, DEFAULT_LITERT_SPECULATIVE_DECODING)
        set(value) = prefs.edit { putBoolean(KEY_LITERT_SPECULATIVE_DECODING, value) }

    /**
     * Theme preference: System default, Light theme, or Dark theme.
     */
    var themeMode: AppThemeMode
        get() = when (prefs.getString(KEY_THEME_MODE, null)) {
            AppThemeMode.LIGHT.id -> AppThemeMode.LIGHT
            AppThemeMode.DARK.id -> AppThemeMode.DARK
            else -> AppThemeMode.SYSTEM
        }
        set(value) = prefs.edit { putString(KEY_THEME_MODE, value.id) }

    /**
     * Calls [onChange] whenever the app's theme mode changes. The floating pill is its own window
     * with its own `SmartVoiceTheme`, so nothing re-resolves its colours when the user picks a new
     * theme in the app — this is that signal. Re-subscribing replaces the previous observer.
     */
    fun observeThemeMode(onChange: () -> Unit) {
        removeThemeModeObserver()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME_MODE) onChange()
        }
        themeModeObserver = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeThemeModeObserver() {
        themeModeObserver?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        themeModeObserver = null
    }

    /** Held strongly: `SharedPreferences` keeps its listeners in a `WeakHashMap`. */
    private var themeModeObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null

    val cleanupRequestValue: String
        get() = if (cleanupEnabled) "full" else "off"

    /** Whether neural models are unloaded immediately when the screen turns off to save battery. */
    var unloadOnScreenOff: Boolean
        get() = prefs.getBoolean(KEY_UNLOAD_SCREEN_OFF, DEFAULT_UNLOAD_SCREEN_OFF)
        set(value) = prefs.edit { putBoolean(KEY_UNLOAD_SCREEN_OFF, value) }

    /**
     * Whether a capture holds the display awake for as long as the microphone is running. Default
     * on: dictation is hands-free, so without it the screen's own timeout can dim and lock the
     * device mid-sentence.
     */
    var keepScreenOnWhileRecording: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON_WHILE_RECORDING, DEFAULT_KEEP_SCREEN_ON_WHILE_RECORDING)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON_WHILE_RECORDING, value) }

    /** Whether neural models are unloaded immediately when the IME keyboard is closed. */
    var unloadOnKeyboardClose: Boolean
        get() = prefs.getBoolean(KEY_UNLOAD_KEYBOARD_CLOSE, DEFAULT_UNLOAD_KEYBOARD_CLOSE)
        set(value) = prefs.edit { putBoolean(KEY_UNLOAD_KEYBOARD_CLOSE, value) }

    /**
     * Inactivity timeout in minutes after which idle models are unloaded from RAM/NPU.
     * 0 means never auto-unload on inactivity.
     */
    var modelIdleTimeoutMinutes: Int
        get() = prefs.getInt(KEY_IDLE_TIMEOUT_MIN, DEFAULT_IDLE_TIMEOUT_MIN)
        set(value) = prefs.edit { putInt(KEY_IDLE_TIMEOUT_MIN, value) }

    /**
     * Number of sentences from the tail of the previous segment to rewrite and heal across pause cuts.
     * 0 disables rewriting (pure append-only mode). Default is 2.
     */
    var healingSentencesCount: Int
        get() = prefs.getInt(KEY_HEALING_SENTENCES_COUNT, DEFAULT_HEALING_SENTENCES_COUNT)
        set(value) = prefs.edit { putInt(KEY_HEALING_SENTENCES_COUNT, value) }

    /** Whether the Wispr Flow floating dictation pill overlay is enabled. */
    var floatingPillEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL_ENABLED, DEFAULT_FLOATING_PILL_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_PILL_ENABLED, value) }

    /** Whether the floating pill appears only when a software keyboard is open. */
    var floatingPillOnlyWhenKeyboardOpen: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL_ONLY_KEYBOARD, DEFAULT_FLOATING_PILL_ONLY_KEYBOARD)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_PILL_ONLY_KEYBOARD, value) }

    /** Whether the floating pill automatically commits on silence stop without requiring checkmark tap. */
    var floatingPillAutoCommit: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL_AUTO_COMMIT, DEFAULT_FLOATING_PILL_AUTO_COMMIT)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_PILL_AUTO_COMMIT, value) }

    /**
     * Whether pressing and holding the resting pill starts a quick capture that is injected at the
     * cursor on release, with no review card. Default on: the gesture is additive (tap and drag
     * still work exactly as before), so it needs a switch to turn it off, not to turn it on.
     */
    var quickDictateOnHold: Boolean
        get() = prefs.getBoolean(KEY_QUICK_DICTATE_ON_HOLD, DEFAULT_QUICK_DICTATE_ON_HOLD)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_DICTATE_ON_HOLD, value) }

    /**
     * How quickly the pill's post-flick glide bleeds off, per second. Higher friction = the pill
     * stops sooner. Exposed as a setting because "how slippery the pill feels" is a taste question
     * best settled by flicking it; the pill's travel distance is `releaseSpeed / this`.
     */
    var pillFlickFriction: Float
        get() = prefs.getFloat(KEY_PILL_FLICK_FRICTION, DEFAULT_PILL_FLICK_FRICTION)
        set(value) = prefs.edit { putFloat(KEY_PILL_FLICK_FRICTION, value) }

    /** Whether dictation history transcripts are saved locally. Default is true. */
    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY_ENABLED, DEFAULT_HISTORY_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_HISTORY_ENABLED, value) }

    /** Maximum number of history items retained locally. Default is 50. */
    var historyCapacity: Int
        get() = prefs.getInt(KEY_HISTORY_CAPACITY, DEFAULT_HISTORY_CAPACITY)
        set(value) = prefs.edit { putInt(KEY_HISTORY_CAPACITY, value) }

    /**
     * Where the user last left the pill, in pixels from the screen's END and BOTTOM edges
     * (the overlay window's gravity is `BOTTOM or END`). [PILL_POSITION_UNSET] means "never
     * dragged" — the pill then docks bottom-right at its default inset. The service clamps
     * the stored value to the screen on restore, so a change of display or rotation cannot
     * strand the pill off-screen.
     */
    var floatingPillX: Int
        get() = prefs.getInt(KEY_FLOATING_PILL_X, PILL_POSITION_UNSET)
        set(value) = prefs.edit { putInt(KEY_FLOATING_PILL_X, value) }

    var floatingPillY: Int
        get() = prefs.getInt(KEY_FLOATING_PILL_Y, PILL_POSITION_UNSET)
        set(value) = prefs.edit { putInt(KEY_FLOATING_PILL_Y, value) }

    /**
     * Whether the first-launch onboarding wizard has been finished (or dismissed) once.
     * Re-running it from Settings must **not** clear this: it exists so the wizard does
     * not reappear on every launch, not to gate the re-run entry point.
     */
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, DEFAULT_ONBOARDING_COMPLETED)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    companion object {
        /** Placeholder only — the real address is the home PC's LAN/Tailscale address and port. */
        const val DEFAULT_SERVER_URL = "http://192.0.2.100:8000"

        const val LANGUAGE_AUTO = "auto"

        private const val PREFS_NAME = "smart_voice"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CLEANUP = "cleanup_enabled"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ENGINE = "engine_preference"
        private const val KEY_ASR_MODEL = "asr_model_id"
        private const val KEY_LITERT_MODEL = "litert_model_id"
        private const val KEY_LITERT_PROCESSOR = "litert_processor"
        private const val KEY_LITERT_GPU_SUPPORTED = "litert_gpu_supported"
        private const val KEY_LITERT_COMPAT_NOTE = "litert_compat_note"
        private const val KEY_LITERT_SPECULATIVE_DECODING = "litert_speculative_decoding"
        private const val KEY_THEME_MODE = "theme_mode"
        // Seconds-valued keys, deliberately new names: the earlier millisecond keys hold Ints, and
        // reading one of those back as a Float throws. The old keys are simply abandoned.
        private const val KEY_PAUSE_SILENCE_S = "pause_silence_s"
        private const val KEY_STABLE_SILENCE_S = "stable_silence_s"
        private const val KEY_STOP_AFTER_SILENCE_S = "stop_after_silence_s"
        private const val KEY_UNLOAD_SCREEN_OFF = "unload_screen_off"
        private const val KEY_KEEP_SCREEN_ON_WHILE_RECORDING = "keep_screen_on_while_recording"
        private const val KEY_UNLOAD_KEYBOARD_CLOSE = "unload_keyboard_close"
        private const val KEY_IDLE_TIMEOUT_MIN = "model_idle_timeout_min"
        private const val KEY_HEALING_SENTENCES_COUNT = "healing_sentences_count"
        private const val KEY_FLOATING_PILL_ENABLED = "floating_pill_enabled"
        private const val KEY_FLOATING_PILL_ONLY_KEYBOARD = "floating_pill_only_keyboard"
        private const val KEY_FLOATING_PILL_AUTO_COMMIT = "floating_pill_auto_commit"
        private const val KEY_QUICK_DICTATE_ON_HOLD = "quick_dictate_on_hold"
        private const val KEY_PILL_FLICK_FRICTION = "pill_flick_friction"
        private const val KEY_FLOATING_PILL_X = "floating_pill_x"
        private const val KEY_FLOATING_PILL_Y = "floating_pill_y"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_HISTORY_CAPACITY = "history_capacity"

        const val DEFAULT_HISTORY_ENABLED = true
        const val DEFAULT_HISTORY_CAPACITY = 50
        const val DEFAULT_FLOATING_PILL_ENABLED = true
        const val DEFAULT_FLOATING_PILL_ONLY_KEYBOARD = true
        const val DEFAULT_FLOATING_PILL_AUTO_COMMIT = false
        const val DEFAULT_QUICK_DICTATE_ON_HOLD = true

        /** Stored pill position meaning "never dragged — use the default corner dock". */
        const val PILL_POSITION_UNSET = -1

        /**
         * A short glide from a typical flick: snappy, with just a little momentum. The Settings
         * slider spans [MIN_PILL_FLICK_FRICTION]..[MAX_PILL_FLICK_FRICTION]; higher stops sooner.
         * 30 is the value the user settled on while hand-testing the fling on the device (CHUNK-3B).
         */
        const val DEFAULT_PILL_FLICK_FRICTION = 30f
        const val MIN_PILL_FLICK_FRICTION = 5f
        const val MAX_PILL_FLICK_FRICTION = 60f
        const val DEFAULT_HEALING_SENTENCES_COUNT = 3
        const val DEFAULT_ONBOARDING_COMPLETED = false

        const val DEFAULT_LITERT_SPECULATIVE_DECODING = true
        const val DEFAULT_UNLOAD_SCREEN_OFF = true
        const val DEFAULT_KEEP_SCREEN_ON_WHILE_RECORDING = true
        const val DEFAULT_UNLOAD_KEYBOARD_CLOSE = true
        const val DEFAULT_IDLE_TIMEOUT_MIN = 2
        val IDLE_TIMEOUT_OPTIONS = listOf(0, 1, 2, 5, 10, 15)

        /**
         * Raised from the first tuned values (1.2 s / 1.8 s / 3.0 s) after real use: they read as far too
         * eager — a breath ended a unit, and a thought pause ended the recording (D-037).
         */
        const val DEFAULT_PAUSE_SILENCE_S = 2.0f
        const val DEFAULT_STABLE_SILENCE_S = 3.0f
        const val DEFAULT_STOP_AFTER_SILENCE_S = 6.0f

        /** What the text fields accept: seconds, at most a minute, and no negatives. */
        const val MAX_TIMING_SECONDS = 60f

        /** The smallest value that still means "a pause of some length" for the two boundary rules. */
        const val MIN_BOUNDARY_SECONDS = 0.2f
    }
}
