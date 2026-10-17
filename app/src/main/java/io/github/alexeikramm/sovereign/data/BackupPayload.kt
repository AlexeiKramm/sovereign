package io.github.alexeikramm.sovereign.data

import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
import io.github.alexeikramm.sovereign.engine.EnginePreference
import kotlinx.serialization.Serializable

/**
 * Portable representation of configurable user settings for export and import.
 */
@Serializable
data class SettingsBackup(
    val serverUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val cleanupEnabled: Boolean = true,
    val language: String = AppSettings.LANGUAGE_AUTO,
    val enginePreference: String = EnginePreference.LOCAL.storedValue,
    val pauseSilenceSeconds: Float = AppSettings.DEFAULT_PAUSE_SILENCE_S,
    val stableSilenceSeconds: Float = AppSettings.DEFAULT_STABLE_SILENCE_S,
    val stopAfterSilenceSeconds: Float = AppSettings.DEFAULT_STOP_AFTER_SILENCE_S,
    val asrModelId: String = AsrModels.DEFAULT_MODEL_ID,
    val litertModelId: String = LiteRtLmModels.DEFAULT.id,
    val litertProcessor: String = LiteRtProcessor.GPU.id,
    val litertSpeculativeDecoding: Boolean = AppSettings.DEFAULT_LITERT_SPECULATIVE_DECODING,
    val litertContextTokens: Int = LiteRtContextWindow.DEFAULT.tokens,
    val themeMode: String = AppThemeMode.SYSTEM.id,
    val themeSeedColor: Int = AppSettings.DEFAULT_THEME_SEED_COLOR,
    val themePaletteStyle: String = AppSettings.DEFAULT_THEME_PALETTE_STYLE,
    val themeUseSystemDynamic: Boolean = AppSettings.DEFAULT_THEME_USE_SYSTEM_DYNAMIC,
    val unloadOnScreenOff: Boolean = AppSettings.DEFAULT_UNLOAD_SCREEN_OFF,
    val keepScreenOnWhileRecording: Boolean = AppSettings.DEFAULT_KEEP_SCREEN_ON_WHILE_RECORDING,
    val unloadOnKeyboardClose: Boolean = AppSettings.DEFAULT_UNLOAD_KEYBOARD_CLOSE,
    val modelIdleTimeoutMinutes: Int = AppSettings.DEFAULT_IDLE_TIMEOUT_MIN,
    val healingSentencesCount: Int = AppSettings.DEFAULT_HEALING_SENTENCES_COUNT,
    val maxTurns: Int = AppSettings.DEFAULT_MAX_TURNS,
    val floatingPillEnabled: Boolean = AppSettings.DEFAULT_FLOATING_PILL_ENABLED,
    val floatingPillOnlyWhenKeyboardOpen: Boolean = AppSettings.DEFAULT_FLOATING_PILL_ONLY_KEYBOARD,
    val floatingPillAutoCommit: Boolean = AppSettings.DEFAULT_FLOATING_PILL_AUTO_COMMIT,
    val floatingPillEdgeAnchoring: Boolean = AppSettings.DEFAULT_FLOATING_PILL_EDGE_ANCHORING,
    val quickDictateOnHold: Boolean = AppSettings.DEFAULT_QUICK_DICTATE_ON_HOLD,
    val pillFlickFriction: Float = AppSettings.DEFAULT_PILL_FLICK_FRICTION,
    val historyEnabled: Boolean = AppSettings.DEFAULT_HISTORY_ENABLED,
    val historyCapacity: Int = AppSettings.DEFAULT_HISTORY_CAPACITY,
)

/**
 * Root export/import bundle containing user dictionary, custom cleaning profiles, and app settings.
 */
@Serializable
data class AppBackup(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val dictionary: List<DictionaryEntry> = emptyList(),
    val customProfiles: List<CleaningProfile> = emptyList(),
    val activeProfileId: String = "smart_clean",
    val settings: SettingsBackup = SettingsBackup(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
