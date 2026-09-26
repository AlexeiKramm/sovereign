package fi.ak.smartvoice.data

import fi.ak.smartvoice.engine.EnginePreference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Headless engine for generating, serializing, validating, and restoring configuration backups.
 */
object BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Constructs an [AppBackup] representing the current live configuration.
     */
    fun createBackup(
        dictionaryEntries: List<DictionaryEntry>,
        profileData: ProfileData,
        settings: AppSettings,
        appVersion: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): AppBackup = createBackup(
        dictionaryEntries = dictionaryEntries,
        profileData = profileData,
        settingsBackup = createSettingsBackup(settings),
        appVersion = appVersion,
        timestampMs = timestampMs,
    )

    fun createBackup(
        dictionaryEntries: List<DictionaryEntry>,
        profileData: ProfileData,
        settingsBackup: SettingsBackup,
        appVersion: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): AppBackup {
        val customOnly = profileData.customProfiles.filterNot { it.isBuiltin }
        return AppBackup(
            schemaVersion = AppBackup.CURRENT_SCHEMA_VERSION,
            exportedAtMs = timestampMs,
            appVersion = appVersion,
            dictionary = dictionaryEntries,
            customProfiles = customOnly,
            activeProfileId = profileData.activeProfileId,
            settings = settingsBackup,
        )
    }

    fun createSettingsBackup(settings: AppSettings): SettingsBackup {
        return SettingsBackup(
            serverUrl = settings.serverUrl,
            cleanupEnabled = settings.cleanupEnabled,
            language = settings.language,
            enginePreference = settings.enginePreference.storedValue,
            pauseSilenceSeconds = settings.pauseSilenceSeconds,
            stableSilenceSeconds = settings.stableSilenceSeconds,
            stopAfterSilenceSeconds = settings.stopAfterSilenceSeconds,
            asrModelId = settings.asrModelId,
            litertModelId = settings.litertModelId,
            litertProcessor = settings.litertProcessor.id,
            litertSpeculativeDecoding = settings.litertSpeculativeDecoding,
            themeMode = settings.themeMode.id,
            themeSeedColor = settings.themeSeedColor,
            themePaletteStyle = settings.themePaletteStyle,
            themeUseSystemDynamic = settings.themeUseSystemDynamic,
            unloadOnScreenOff = settings.unloadOnScreenOff,
            keepScreenOnWhileRecording = settings.keepScreenOnWhileRecording,
            unloadOnKeyboardClose = settings.unloadOnKeyboardClose,
            modelIdleTimeoutMinutes = settings.modelIdleTimeoutMinutes,
            healingSentencesCount = settings.healingSentencesCount,
            floatingPillEnabled = settings.floatingPillEnabled,
            floatingPillOnlyWhenKeyboardOpen = settings.floatingPillOnlyWhenKeyboardOpen,
            floatingPillAutoCommit = settings.floatingPillAutoCommit,
            quickDictateOnHold = settings.quickDictateOnHold,
            pillFlickFriction = settings.pillFlickFriction,
            historyEnabled = settings.historyEnabled,
            historyCapacity = settings.historyCapacity,
        )
    }

    /**
     * Serializes [backup] into a formatted JSON string.
     */
    fun serialize(backup: AppBackup): String = json.encodeToString(backup)

    /**
     * Deserializes and validates a JSON string into [AppBackup].
     */
    fun deserialize(jsonString: String): Result<AppBackup> = runCatching {
        val trimmed = jsonString.trim()
        require(trimmed.isNotBlank()) { "Backup file is empty" }
        val parsed = json.decodeFromString<AppBackup>(trimmed)
        require(parsed.schemaVersion in 1..AppBackup.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schema version: ${parsed.schemaVersion}"
        }
        parsed
    }

    /**
     * Writes [backup] as UTF-8 JSON to [outputStream].
     */
    fun exportToStream(outputStream: OutputStream, backup: AppBackup) {
        val jsonText = serialize(backup)
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(jsonText)
            writer.flush()
        }
    }

    /**
     * Reads and parses [AppBackup] from [inputStream].
     */
    fun importFromStream(inputStream: InputStream): Result<AppBackup> = runCatching {
        val jsonText = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        deserialize(jsonText).getOrThrow()
    }

    /**
     * Restores state into [dictionaryStore], [profileStore], and [settings].
     */
    fun restore(
        backup: AppBackup,
        dictionaryStore: DictionaryStore,
        profileStore: ProfileStore,
        settings: AppSettings,
    ) {
        // 1. Dictionary
        dictionaryStore.replaceEntries(backup.dictionary)

        // 2. Profiles
        profileStore.restoreProfiles(backup.customProfiles, backup.activeProfileId)

        // 3. Settings
        restoreSettings(settings, backup.settings)
    }

    fun restoreSettings(settings: AppSettings, s: SettingsBackup) {
        settings.serverUrl = s.serverUrl
        settings.cleanupEnabled = s.cleanupEnabled
        settings.language = s.language
        settings.enginePreference = EnginePreference.fromStored(s.enginePreference)
        settings.pauseSilenceSeconds = s.pauseSilenceSeconds
        settings.stableSilenceSeconds = s.stableSilenceSeconds
        settings.stopAfterSilenceSeconds = s.stopAfterSilenceSeconds
        settings.asrModelId = s.asrModelId
        settings.litertModelId = s.litertModelId
        settings.litertProcessor = when (s.litertProcessor) {
            LiteRtProcessor.CPU.id -> LiteRtProcessor.CPU
            else -> LiteRtProcessor.GPU
        }
        settings.litertSpeculativeDecoding = s.litertSpeculativeDecoding
        settings.themeMode = when (s.themeMode) {
            AppThemeMode.LIGHT.id -> AppThemeMode.LIGHT
            AppThemeMode.DARK.id -> AppThemeMode.DARK
            else -> AppThemeMode.SYSTEM
        }
        settings.themeSeedColor = s.themeSeedColor
        settings.themePaletteStyle = s.themePaletteStyle
        settings.themeUseSystemDynamic = s.themeUseSystemDynamic
        settings.unloadOnScreenOff = s.unloadOnScreenOff
        settings.keepScreenOnWhileRecording = s.keepScreenOnWhileRecording
        settings.unloadOnKeyboardClose = s.unloadOnKeyboardClose
        settings.modelIdleTimeoutMinutes = s.modelIdleTimeoutMinutes
        settings.healingSentencesCount = s.healingSentencesCount
        settings.floatingPillEnabled = s.floatingPillEnabled
        settings.floatingPillOnlyWhenKeyboardOpen = s.floatingPillOnlyWhenKeyboardOpen
        settings.floatingPillAutoCommit = s.floatingPillAutoCommit
        settings.quickDictateOnHold = s.quickDictateOnHold
        settings.pillFlickFriction = s.pillFlickFriction
        settings.historyEnabled = s.historyEnabled
        settings.historyCapacity = s.historyCapacity
    }
}
