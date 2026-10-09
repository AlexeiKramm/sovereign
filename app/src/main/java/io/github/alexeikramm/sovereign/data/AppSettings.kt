package io.github.alexeikramm.sovereign.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelSpec
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.engine.EnginePreference

enum class LiteRtProcessor(val id: String, val label: String, val note: String) {
    GPU("gpu", "GPU (OpenCL / Vulkan)", "Fastest performance (Recommended)"),
    CPU("cpu", "CPU", "Standard processor fallback"),
}

enum class AppThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
}

/**
 * The Material 3 colour roles offered for the provisional raw text. A curated set (not all 30+
 * roles) because the point is only "which token blends the raw text into the background", and a
 * short list is what makes that one glance. Resolved against the live scheme, so each role carries
 * its own dark and light value.
 */
enum class RawTextColorRole(val id: String, val label: String, val description: String) {
    ON_SURFACE("onSurface", "Text", "The primary text colour"),
    ON_SURFACE_VARIANT("onSurfaceVariant", "Muted text", "The secondary text colour"),
    OUTLINE("outline", "Outline", "Mid-strength grey"),
    OUTLINE_VARIANT("outlineVariant", "Faint outline", "Lowest-contrast grey"),
    PRIMARY("primary", "Accent", "The theme's primary accent"),
    SECONDARY("secondary", "Accent 2", "The theme's secondary accent"),
    TERTIARY("tertiary", "Accent 3", "The theme's tertiary accent");

    companion object {
        val DEFAULT = ON_SURFACE_VARIANT

        fun fromId(id: String?): RawTextColorRole =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
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
     * The primary seed ARGB color for Material 3 dynamic color generation (default #FF9248).
     */
    var themeSeedColor: Int
        get() = prefs.getInt(KEY_THEME_SEED_COLOR, DEFAULT_THEME_SEED_COLOR)
        set(value) = prefs.edit { putInt(KEY_THEME_SEED_COLOR, value) }

    /**
     * The M3 palette style variant ("VIBRANT", "TONAL_SPOT", "FIDELITY").
     */
    var themePaletteStyle: String
        get() = prefs.getString(KEY_THEME_PALETTE_STYLE, DEFAULT_THEME_PALETTE_STYLE).orEmpty()
        set(value) = prefs.edit { putString(KEY_THEME_PALETTE_STYLE, value) }

    /**
     * Whether to extract the dynamic color scheme from system wallpaper (Material You on API 31+).
     */
    var themeUseSystemDynamic: Boolean
        get() = prefs.getBoolean(KEY_THEME_USE_SYSTEM_DYNAMIC, DEFAULT_THEME_USE_SYSTEM_DYNAMIC)
        set(value) = prefs.edit { putBoolean(KEY_THEME_USE_SYSTEM_DYNAMIC, value) }

    var themeDepthMode: Boolean
        get() = prefs.getBoolean(KEY_THEME_DEPTH_MODE, DEFAULT_THEME_DEPTH_MODE)
        set(value) = prefs.edit { putBoolean(KEY_THEME_DEPTH_MODE, value) }

    var themeDarkBgTone: Float
        get() = prefs.getFloat(KEY_THEME_DARK_BG_TONE, DEFAULT_THEME_DARK_BG_TONE)
        set(value) = prefs.edit { putFloat(KEY_THEME_DARK_BG_TONE, value) }

    var themeDarkCardTone: Float
        get() = prefs.getFloat(KEY_THEME_DARK_CARD_TONE, DEFAULT_THEME_DARK_CARD_TONE)
        set(value) = prefs.edit { putFloat(KEY_THEME_DARK_CARD_TONE, value) }

    var themeDarkBorderTone: Float
        get() = prefs.getFloat(KEY_THEME_DARK_BORDER_TONE, DEFAULT_THEME_DARK_BORDER_TONE)
        set(value) = prefs.edit { putFloat(KEY_THEME_DARK_BORDER_TONE, value) }

    var themeLightBgTone: Float
        get() = prefs.getFloat(KEY_THEME_LIGHT_BG_TONE, DEFAULT_THEME_LIGHT_BG_TONE)
        set(value) = prefs.edit { putFloat(KEY_THEME_LIGHT_BG_TONE, value) }

    var themeLightBorderTone: Float
        get() = prefs.getFloat(KEY_THEME_LIGHT_BORDER_TONE, DEFAULT_THEME_LIGHT_BORDER_TONE)
        set(value) = prefs.edit { putFloat(KEY_THEME_LIGHT_BORDER_TONE, value) }

    /**
     * Calls [onChange] whenever any theme preference (mode, seed color, palette style, or dynamic flag)
     * changes. The floating pill is its own window with its own theme, so this signal updates it
     * immediately in real time.
     */
    fun observeTheme(onChange: () -> Unit) {
        removeThemeModeObserver()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in THEME_KEYS) onChange()
        }
        themeModeObserver = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Compatibility alias for [observeTheme]. */
    fun observeThemeMode(onChange: () -> Unit) = observeTheme(onChange)

    fun removeThemeModeObserver() {
        themeModeObserver?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        themeModeObserver = null
    }

    /** Held strongly: `SharedPreferences` keeps its listeners in a `WeakHashMap`. */
    private var themeModeObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Calls [onChange] whenever the edge anchoring preference changes.
     */
    fun observeEdgeAnchoring(onChange: () -> Unit) {
        removeEdgeAnchoringObserver()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FLOATING_PILL_EDGE_ANCHORING) onChange()
        }
        edgeAnchoringObserver = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeEdgeAnchoringObserver() {
        edgeAnchoringObserver?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        edgeAnchoringObserver = null
    }

    private var edgeAnchoringObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Calls [onChange] whenever the cleanup preference changes — from the pill's own menu, the Home
     * status card, Settings, or a backup restore. The pill is a separate window, so it does not see
     * those writes by itself; without this it would draw the wrong cleanup state (and the wrong
     * raw-text colour) until it was restarted.
     */
    fun observeCleanupEnabled(onChange: () -> Unit) {
        removeCleanupEnabledObserver()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CLEANUP) onChange()
        }
        cleanupEnabledObserver = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeCleanupEnabledObserver() {
        cleanupEnabledObserver?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        cleanupEnabledObserver = null
    }

    private var cleanupEnabledObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null

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
     * How long the desktop server keeps serving before it stops itself (the dashboard's 1 h / 2 h /
     * manual selector). Minutes; 0 means it never auto-stops. Deliberately **not** part of
     * `SettingsBackup`: a server session is device-local and a stale timer restored from a backup
     * would silently cap a session elsewhere.
     */
    var serverTimeoutMinutes: Int
        get() = prefs.getInt(KEY_SERVER_TIMEOUT_MIN, DEFAULT_SERVER_TIMEOUT_MIN)
        set(value) = prefs.edit { putInt(KEY_SERVER_TIMEOUT_MIN, value) }

    /**
     * Whether the Server active dashboard holds the display awake while it is open. Default on. This
     * is a *convenience*: the server foreground service survives screen-off either way (D-105); the
     * dashboard only avoids Doze/deep-sleep jitter and gives the phone a dim place to sit.
     */
    var serverKeepScreenAwake: Boolean
        get() = prefs.getBoolean(
            KEY_SERVER_KEEP_SCREEN_AWAKE,
            DEFAULT_SERVER_KEEP_SCREEN_AWAKE,
        )
        set(value) = prefs.edit { putBoolean(KEY_SERVER_KEEP_SCREEN_AWAKE, value) }

    /**
     * Whether the Server active dashboard dims the display after a spell with no touch. Default on:
     * the screen is meant to be left on, so a hard per-window brightness drop saves AMOLED power
     * without turning it off. See `ui/server/ServerIdleDim`.
     */
    var serverDimWhenIdle: Boolean
        get() = prefs.getBoolean(KEY_SERVER_DIM_WHEN_IDLE, DEFAULT_SERVER_DIM_WHEN_IDLE)
        set(value) = prefs.edit { putBoolean(KEY_SERVER_DIM_WHEN_IDLE, value) }

    /**
     * Number of sentences from the tail of the previous segment to rewrite and heal across pause cuts.
     * 0 disables rewriting (pure append-only mode). Default is 2.
     */
    var healingSentencesCount: Int
        get() = prefs.getInt(KEY_HEALING_SENTENCES_COUNT, DEFAULT_HEALING_SENTENCES_COUNT)
        set(value) = prefs.edit { putInt(KEY_HEALING_SENTENCES_COUNT, value) }

    /**
     * Rebuild the cleanup conversation after this many segments even when the KV cache is not near
     * full. 0 means no turn limit: one conversation until the cache triggers a rebuild, which is the
     * old behaviour. Default 2 — a small cleanup model drifts as its context fills (Cleanup Lab,
     * D-101), so a short cap holds filler-removal quality on long dictations.
     */
    var maxTurns: Int
        get() = prefs.getInt(KEY_MAX_TURNS, DEFAULT_MAX_TURNS)
        set(value) = prefs.edit { putInt(KEY_MAX_TURNS, value) }

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

    /** Whether the resting pill snaps horizontally to hug the nearest screen edge. */
    var floatingPillEdgeAnchoring: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_PILL_EDGE_ANCHORING, DEFAULT_FLOATING_PILL_EDGE_ANCHORING)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_PILL_EDGE_ANCHORING, value) }

    /**
     * Whether pressing and holding the resting pill starts a quick capture that is injected at the
     * cursor on release, with no review card. Default on: the gesture is additive (tap and drag
     * still work exactly as before), so it needs a switch to turn it off, not to turn it on.
     */
    var quickDictateOnHold: Boolean
        get() = prefs.getBoolean(KEY_QUICK_DICTATE_ON_HOLD, DEFAULT_QUICK_DICTATE_ON_HOLD)
        set(value) = prefs.edit { putBoolean(KEY_QUICK_DICTATE_ON_HOLD, value) }

    /** Whether haptic feedback is triggered when starting and releasing Hold-To-Dictate. */
    var hapticFeedbackOnHold: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK_ON_HOLD, DEFAULT_HAPTIC_FEEDBACK_ON_HOLD)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC_FEEDBACK_ON_HOLD, value) }

    /**
     * Whether the live raw ASR text is shown in the expanded card while the user speaks, replaced
     * per unit by the cleaned text. **Streaming models only** — the offline recognisers emit nothing
     * until a segment ends, so the toggle is a no-op for them. Default on: the feature exists to
     * fill the ~20 s dead air a user sees on continuous speech (raw-preview work order, D-083).
     */
    var streamingPreviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_STREAMING_PREVIEW_ENABLED, DEFAULT_STREAMING_PREVIEW_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_STREAMING_PREVIEW_ENABLED, value) }

    /**
     * Which Material 3 colour role the provisional raw text uses. Stored by name ([RawTextColorRole])
     * so a palette change cannot silently invalidate the choice. The role is read against the live
     * scheme, so dark and light themes each get that role's own value.
     */
    var streamingPreviewColorRole: RawTextColorRole
        get() = RawTextColorRole.fromId(prefs.getString(KEY_STREAMING_PREVIEW_COLOR_ROLE, null))
        set(value) = prefs.edit { putString(KEY_STREAMING_PREVIEW_COLOR_ROLE, value.id) }

    /** Opacity applied to [streamingPreviewColorRole]; lower makes the raw text recede further. */
    var streamingPreviewOpacity: Float
        get() = prefs.getFloat(KEY_STREAMING_PREVIEW_OPACITY, DEFAULT_STREAMING_PREVIEW_OPACITY)
        set(value) = prefs.edit { putFloat(KEY_STREAMING_PREVIEW_OPACITY, value) }

    /**
     * How quickly the pill's post-flick glide bleeds off, per second. Higher friction = the pill
     * stops sooner. Exposed as a setting because "how slippery the pill feels" is a taste question
     * best settled by flicking it; the pill's travel distance is `releaseSpeed / this`.
     */
    var pillFlickFriction: Float
        get() = prefs.getFloat(KEY_PILL_FLICK_FRICTION, DEFAULT_PILL_FLICK_FRICTION)
        set(value) = prefs.edit { putFloat(KEY_PILL_FLICK_FRICTION, value) }

    /**
     * Vertical momentum follow-through attenuation during edge snap (0.0 .. 1.0).
     * 0.0 means horizontal-only snap; 1.0 means unattenuated momentum carryover.
     */
    var pillSnapAttenuation: Float
        get() = prefs.getFloat(KEY_PILL_SNAP_ATTENUATION, DEFAULT_PILL_SNAP_ATTENUATION)
        set(value) = prefs.edit { putFloat(KEY_PILL_SNAP_ATTENUATION, value) }

    /**
     * Stiffness for the edge snap spring (100f .. 1500f).
     * Lower is softer and accelerates more gently; higher snaps faster.
     */
    var pillSnapStiffness: Float
        get() = prefs.getFloat(KEY_PILL_SNAP_STIFFNESS, DEFAULT_PILL_SNAP_STIFFNESS)
        set(value) = prefs.edit { putFloat(KEY_PILL_SNAP_STIFFNESS, value) }

    /**
     * Damping ratio for the edge snap spring (0.5 .. 1.5).
     * 1.0 = critically damped (no bounce/overshoot); < 1.0 bounces; > 1.0 overdamped.
     */
    var pillSnapDamping: Float
        get() = prefs.getFloat(KEY_PILL_SNAP_DAMPING, DEFAULT_PILL_SNAP_DAMPING)
        set(value) = prefs.edit { putFloat(KEY_PILL_SNAP_DAMPING, value) }

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
        private const val KEY_THEME_SEED_COLOR = "theme_seed_color"
        private const val KEY_THEME_PALETTE_STYLE = "theme_palette_style"
        private const val KEY_THEME_USE_SYSTEM_DYNAMIC = "theme_use_system_dynamic"
        private const val KEY_THEME_DEPTH_MODE = "theme_depth_mode"
        private const val KEY_THEME_DARK_BG_TONE = "theme_dark_bg_tone"
        private const val KEY_THEME_DARK_CARD_TONE = "theme_dark_card_tone"
        private const val KEY_THEME_DARK_BORDER_TONE = "theme_dark_border_tone"
        private const val KEY_THEME_LIGHT_BG_TONE = "theme_light_bg_tone"
        private const val KEY_THEME_LIGHT_BORDER_TONE = "theme_light_border_tone"

        private val THEME_KEYS = setOf(
            KEY_THEME_MODE,
            KEY_THEME_SEED_COLOR,
            KEY_THEME_PALETTE_STYLE,
            KEY_THEME_USE_SYSTEM_DYNAMIC,
            KEY_THEME_DEPTH_MODE,
            KEY_THEME_DARK_BG_TONE,
            KEY_THEME_DARK_CARD_TONE,
            KEY_THEME_DARK_BORDER_TONE,
            KEY_THEME_LIGHT_BG_TONE,
            KEY_THEME_LIGHT_BORDER_TONE,
        )

        const val DEFAULT_THEME_SEED_COLOR: Int = 0xFF00B4D8.toInt()
        const val DEFAULT_THEME_PALETTE_STYLE: String = "TONAL_SPOT"
        const val DEFAULT_THEME_USE_SYSTEM_DYNAMIC: Boolean = false
        const val DEFAULT_THEME_DEPTH_MODE: Boolean = true
        const val DEFAULT_THEME_DARK_BG_TONE: Float = 8.0f
        const val DEFAULT_THEME_DARK_CARD_TONE: Float = 16.0f
        const val DEFAULT_THEME_DARK_BORDER_TONE: Float = 25.0f
        const val DEFAULT_THEME_LIGHT_BG_TONE: Float = 91.5f
        const val DEFAULT_THEME_LIGHT_BORDER_TONE: Float = 82.0f
        // Seconds-valued keys, deliberately new names: the earlier millisecond keys hold Ints, and
        // reading one of those back as a Float throws. The old keys are simply abandoned.
        private const val KEY_PAUSE_SILENCE_S = "pause_silence_s"
        private const val KEY_STABLE_SILENCE_S = "stable_silence_s"
        private const val KEY_STOP_AFTER_SILENCE_S = "stop_after_silence_s"
        private const val KEY_UNLOAD_SCREEN_OFF = "unload_screen_off"
        private const val KEY_KEEP_SCREEN_ON_WHILE_RECORDING = "keep_screen_on_while_recording"
        private const val KEY_UNLOAD_KEYBOARD_CLOSE = "unload_keyboard_close"
        private const val KEY_IDLE_TIMEOUT_MIN = "model_idle_timeout_min"
        private const val KEY_SERVER_TIMEOUT_MIN = "server_timeout_min"
        private const val KEY_SERVER_KEEP_SCREEN_AWAKE = "server_keep_screen_awake"
        private const val KEY_SERVER_DIM_WHEN_IDLE = "server_dim_when_idle"
        private const val KEY_HEALING_SENTENCES_COUNT = "healing_sentences_count"
        private const val KEY_MAX_TURNS = "cleanup_max_turns"
        private const val KEY_FLOATING_PILL_ENABLED = "floating_pill_enabled"
        private const val KEY_FLOATING_PILL_ONLY_KEYBOARD = "floating_pill_only_keyboard"
        private const val KEY_FLOATING_PILL_AUTO_COMMIT = "floating_pill_auto_commit"
        private const val KEY_QUICK_DICTATE_ON_HOLD = "quick_dictate_on_hold"
        private const val KEY_HAPTIC_FEEDBACK_ON_HOLD = "haptic_feedback_on_hold"
        private const val KEY_PILL_FLICK_FRICTION = "pill_flick_friction"
        private const val KEY_PILL_SNAP_ATTENUATION = "pill_snap_attenuation"
        private const val KEY_PILL_SNAP_STIFFNESS = "pill_snap_stiffness"
        private const val KEY_PILL_SNAP_DAMPING = "pill_snap_damping"
        private const val KEY_FLOATING_PILL_X = "floating_pill_x"
        private const val KEY_FLOATING_PILL_Y = "floating_pill_y"
        private const val KEY_FLOATING_PILL_EDGE_ANCHORING = "floating_pill_edge_anchoring"
        private const val KEY_STREAMING_PREVIEW_ENABLED = "streaming_preview_enabled"
        private const val KEY_STREAMING_PREVIEW_COLOR_ROLE = "streaming_preview_color_role"
        private const val KEY_STREAMING_PREVIEW_OPACITY = "streaming_preview_opacity"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_HISTORY_CAPACITY = "history_capacity"

        const val DEFAULT_HISTORY_ENABLED = true
        const val DEFAULT_HISTORY_CAPACITY = 50
        const val DEFAULT_FLOATING_PILL_ENABLED = true
        const val DEFAULT_FLOATING_PILL_ONLY_KEYBOARD = true
        const val DEFAULT_FLOATING_PILL_AUTO_COMMIT = false
        const val DEFAULT_FLOATING_PILL_EDGE_ANCHORING = true
        const val DEFAULT_QUICK_DICTATE_ON_HOLD = true
        const val DEFAULT_HAPTIC_FEEDBACK_ON_HOLD = true

        const val DEFAULT_STREAMING_PREVIEW_ENABLED = true
        const val DEFAULT_STREAMING_PREVIEW_OPACITY = 0.55f
        const val MIN_STREAMING_PREVIEW_OPACITY = 0.1f
        const val MAX_STREAMING_PREVIEW_OPACITY = 1.0f

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

        const val DEFAULT_PILL_SNAP_ATTENUATION = 0.25f
        const val MIN_PILL_SNAP_ATTENUATION = 0.0f
        const val MAX_PILL_SNAP_ATTENUATION = 1.0f

        const val DEFAULT_PILL_SNAP_STIFFNESS = 400f
        const val MIN_PILL_SNAP_STIFFNESS = 100f
        const val MAX_PILL_SNAP_STIFFNESS = 1500f

        const val DEFAULT_PILL_SNAP_DAMPING = 1.0f
        const val MIN_PILL_SNAP_DAMPING = 0.5f
        const val MAX_PILL_SNAP_DAMPING = 1.5f
        const val DEFAULT_HEALING_SENTENCES_COUNT = 3
        /** Turn cap for the cleanup conversation; 0 = no limit. See [AppSettings.maxTurns]. */
        const val DEFAULT_MAX_TURNS = 2
        const val DEFAULT_ONBOARDING_COMPLETED = false

        const val DEFAULT_LITERT_SPECULATIVE_DECODING = true
        const val DEFAULT_UNLOAD_SCREEN_OFF = true
        const val DEFAULT_KEEP_SCREEN_ON_WHILE_RECORDING = true
        const val DEFAULT_UNLOAD_KEYBOARD_CLOSE = true
        const val DEFAULT_IDLE_TIMEOUT_MIN = 2
        val IDLE_TIMEOUT_OPTIONS = listOf(0, 1, 2, 5, 10, 15)

        /** The desktop server's default session cap (2 h) and the dashboard's option list. */
        const val DEFAULT_SERVER_TIMEOUT_MIN = 120
        val SERVER_TIMEOUT_OPTIONS = listOf(60, 120, 0)

        /** The Server active dashboard holds the display awake by default; see the property. */
        const val DEFAULT_SERVER_KEEP_SCREEN_AWAKE = true

        /** The Server active dashboard dims when idle by default; see the property. */
        const val DEFAULT_SERVER_DIM_WHEN_IDLE = true

        /**
         * Boundary timings. D-037 raised the first tuned values (1.2 s / 1.8 s / 3.0 s) because a
         * breath ended a unit — a real constraint while cleanup seams were still visible. Seam
         * healing (D-065) and per-unit pipelining have since removed that need, so S17 round 1 set
         * the user-facing values afresh: "Pause sensitivity" 1.0 s and auto-stop 8.0 s. The streaming
         * matrix (D-090) then showed 1.0 s buys no cleanup quality over 0.5 s and is ~0.5 s slower to
         * first visible text, so "Pause sensitivity" is lowered to 0.5 s. [DEFAULT_STABLE_SILENCE_S]
         * is unchanged — it is the endpointing fallback, not a user-facing control.
         * Auto-stop silence defaults to 60.0 s so pauses in thought during continuous dictation do
         * not unexpectedly terminate recording.
         */
        const val DEFAULT_PAUSE_SILENCE_S = 0.5f
        const val DEFAULT_STABLE_SILENCE_S = 3.0f
        const val DEFAULT_STOP_AFTER_SILENCE_S = 60.0f

        /** What the text fields accept: seconds, at most 5 minutes (300 s), and no negatives. */
        const val MAX_TIMING_SECONDS = 300f

        /** The smallest value that still means "a pause of some length" for the two boundary rules. */
        const val MIN_BOUNDARY_SECONDS = 0.2f
    }
}
