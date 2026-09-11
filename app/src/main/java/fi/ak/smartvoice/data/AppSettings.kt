package fi.ak.smartvoice.data

import android.content.Context
import androidx.core.content.edit

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
    }
}
