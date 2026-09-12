package fi.ak.smartvoice.data

import android.content.Context
import androidx.core.content.edit
import fi.ak.smartvoice.asr.ListenerTimings
import fi.ak.smartvoice.cleanup.CleanupModels
import fi.ak.smartvoice.engine.EnginePreference

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
     * The listener's timings (D-035). Settings rather than constants because these are the numbers a
     * person tunes by using the thing, and on this project every adjustment otherwise costs an APK
     * build, a hand-install and a pasted report.
     */
    var pauseSilenceMs: Int
        get() = prefs.getInt(KEY_PAUSE_SILENCE_MS, DEFAULT_PAUSE_SILENCE_MS)
        set(value) = prefs.edit { putInt(KEY_PAUSE_SILENCE_MS, value) }

    /** Text-stability fallback for the unit boundary. Only used when the recogniser says nothing. */
    var stableSilenceMs: Int
        get() = prefs.getInt(KEY_STABLE_SILENCE_MS, DEFAULT_STABLE_SILENCE_MS)
        set(value) = prefs.edit { putInt(KEY_STABLE_SILENCE_MS, value) }

    /** Silence after speech that ends the recording. 0 means it never stops on its own. */
    var stopAfterSilenceMs: Int
        get() = prefs.getInt(KEY_STOP_AFTER_SILENCE_MS, DEFAULT_STOP_AFTER_SILENCE_MS)
        set(value) = prefs.edit { putInt(KEY_STOP_AFTER_SILENCE_MS, value) }

    /** What the ASR session needs for one utterance. */
    fun listenerTimings(): ListenerTimings = ListenerTimings(
        unitStableSeconds = stableSilenceMs / 1000.0,
        stopAfterSilenceSeconds = stopAfterSilenceMs / 1000.0,
    )

    /**
     * Which cleanup model the local tier runs (D-029 item 3's catalogue). Stored as the repository
     * id, not an index: reordering or extending the catalogue must not silently switch the model.
     */
    var cleanupModelId: String
        get() = prefs.getString(KEY_CLEANUP_MODEL, null) ?: CleanupModels.DEFAULT.repository
        set(value) = prefs.edit { putString(KEY_CLEANUP_MODEL, value) }

    val cleanupRequestValue: String
        get() = if (cleanupEnabled) "full" else "off"

    companion object {
        /** Placeholder only — the real address is the home PC's LAN/Tailscale address and port. */
        const val DEFAULT_SERVER_URL = "http://192.0.2.100:8000"

        const val LANGUAGE_AUTO = "auto"

        /** What the language chip cycles through: auto first, then the languages actually used. */
        val LANGUAGE_CYCLE = listOf(LANGUAGE_AUTO, "fi", "en", "ru")

        private const val PREFS_NAME = "smart_voice"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CLEANUP = "cleanup_enabled"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ENGINE = "engine_preference"
        private const val KEY_CLEANUP_MODEL = "cleanup_model_id"
        private const val KEY_PAUSE_SILENCE_MS = "pause_silence_ms"
        private const val KEY_STABLE_SILENCE_MS = "stable_silence_ms"
        private const val KEY_STOP_AFTER_SILENCE_MS = "stop_after_silence_ms"

        /** The tuned starting point: the runtime's default was 2.4 s, which read as "never" in practice. */
        const val DEFAULT_PAUSE_SILENCE_MS = 1200

        const val DEFAULT_STABLE_SILENCE_MS = 1800

        const val DEFAULT_STOP_AFTER_SILENCE_MS = 3000

        /** Offered by the settings screen, shortest first. */
        val PAUSE_SILENCE_CHOICES_MS = listOf(800, 1200, 1600, 2400, 4000)

        val STABLE_SILENCE_CHOICES_MS = listOf(1200, 1800, 3000)

        /** 0 is "off": the recording ends when the user says so. */
        val STOP_AFTER_SILENCE_CHOICES_MS = listOf(0, 2000, 3000, 5000)
    }
}
