package fi.ak.smartvoice.data

import android.content.Context

/**
 * The one place the app's settings are read and written.
 *
 * Exists because both entry points need the server URL: `MainActivity` (where it is edited) and
 * the IME service (where it is used). Two copies of the preference key would drift, and the IME has
 * no screen to fix a wrong value from.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty()
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    companion object {
        /** Placeholder only — the real address is the home PC's LAN/Tailscale address and port. */
        const val DEFAULT_SERVER_URL = "http://192.0.2.100:8000"

        private const val PREFS_NAME = "smart_voice"
        private const val KEY_SERVER_URL = "server_url"
    }
}
