package fi.ak.smartvoice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupManagerTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `serialize and deserialize round-trip preserves all fields`() {
        val dictionary = listOf(
            DictionaryEntry(correct = "Kubernetes", incorrect = listOf("kube", "coober netties")),
            DictionaryEntry(correct = "PostgreSQL", incorrect = listOf("postgres")),
        )
        val customProfiles = listOf(
            CleaningProfile(
                id = "custom_tech",
                name = "Technical Writing",
                description = "Strict tech vocabulary and clean code formatting",
                rules = "Preserve technical accuracy.",
                isBuiltin = false,
            ),
        )
        val settings = SettingsBackup(
            serverUrl = "http://10.0.0.5:8000",
            cleanupEnabled = true,
            language = "en",
            enginePreference = "local",
            pauseSilenceSeconds = 2.5f,
            stableSilenceSeconds = 3.5f,
            stopAfterSilenceSeconds = 7.0f,
            asrModelId = "nemotron-3.5-streaming",
            litertModelId = "gemma-4-e2b-gpu",
            litertProcessor = "gpu",
            litertSpeculativeDecoding = true,
            themeMode = "dark",
            themeSeedColor = 0xFF1A73E8.toInt(),
            themePaletteStyle = "FIDELITY",
            themeUseSystemDynamic = true,
            unloadOnScreenOff = false,
            keepScreenOnWhileRecording = true,
            unloadOnKeyboardClose = false,
            modelIdleTimeoutMinutes = 5,
            healingSentencesCount = 4,
            floatingPillEnabled = true,
            floatingPillOnlyWhenKeyboardOpen = false,
            floatingPillAutoCommit = true,
            floatingPillEdgeAnchoring = false,
            quickDictateOnHold = false,
            pillFlickFriction = 42f,
            historyEnabled = true,
            historyCapacity = 25,
        )

        val original = AppBackup(
            schemaVersion = 1,
            exportedAtMs = 1727000000000L,
            appVersion = "1.23-backup",
            dictionary = dictionary,
            customProfiles = customProfiles,
            activeProfileId = "custom_tech",
            settings = settings,
        )

        val jsonString = BackupManager.serialize(original)
        assertTrue(jsonString.contains("Kubernetes"))
        assertTrue(jsonString.contains("Technical Writing"))
        assertTrue(jsonString.contains("custom_tech"))

        val deserializedResult = BackupManager.deserialize(jsonString)
        assertTrue(deserializedResult.isSuccess)
        val restored = deserializedResult.getOrThrow()

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.exportedAtMs, restored.exportedAtMs)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.activeProfileId, restored.activeProfileId)
        assertEquals(original.dictionary, restored.dictionary)
        assertEquals(original.customProfiles, restored.customProfiles)
        assertEquals(original.settings, restored.settings)
    }

    @Test
    fun `deserialize fails on blank or malformed input`() {
        assertFalse(BackupManager.deserialize("").isSuccess)
        assertFalse(BackupManager.deserialize("   ").isSuccess)
        assertFalse(BackupManager.deserialize("invalid json { [").isSuccess)
    }

    @Test
    fun `deserialize fails on unsupported future schema version`() {
        val json = """
            {
                "schemaVersion": 999,
                "exportedAtMs": 1000,
                "appVersion": "9.9.9",
                "dictionary": [],
                "customProfiles": [],
                "activeProfileId": "smart_clean",
                "settings": {}
            }
        """.trimIndent()

        val result = BackupManager.deserialize(json)
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unsupported backup schema version") == true)
    }

    @Test
    fun `stream export and import round-trip accurately`() {
        val backup = AppBackup(
            schemaVersion = 1,
            exportedAtMs = 5000L,
            appVersion = "1.23",
            dictionary = listOf(DictionaryEntry("SmartVoice", listOf("smart voice"))),
            customProfiles = emptyList(),
            activeProfileId = "smart_clean",
            settings = SettingsBackup(),
        )

        val out = ByteArrayOutputStream()
        BackupManager.exportToStream(out, backup)

        val inStream = ByteArrayInputStream(out.toByteArray())
        val importedResult = BackupManager.importFromStream(inStream)

        assertTrue(importedResult.isSuccess)
        assertEquals(backup, importedResult.getOrThrow())
    }

    @Test
    fun `createBackup filters out built-in profiles from custom list`() {
        val profileStore = ProfileStore(temp.root)
        val profileData = profileStore.load()
        val allProfiles = profileStore.allProfiles()
        assertTrue(allProfiles.any { it.isBuiltin })

        val backup = BackupManager.createBackup(
            dictionaryEntries = emptyList(),
            profileData = profileData,
            settingsBackup = SettingsBackup(),
            appVersion = "test",
            timestampMs = 12345L,
        )

        assertTrue(backup.customProfiles.none { it.isBuiltin })
    }

    @Test
    fun `restore accurately updates DictionaryStore and ProfileStore`() {
        val dictStore = DictionaryStore(temp.root)
        val profileStore = ProfileStore(temp.root)

        // Seed initial stores
        dictStore.addCorrection("OldTerm", listOf("old"))
        val initialProfile = CleaningProfile("custom_initial", "Initial", "desc", "rules", false)
        profileStore.saveCustomProfile(initialProfile)
        profileStore.setActiveProfile(initialProfile.id)

        // Prepare backup with different data
        val newEntries = listOf(
            DictionaryEntry("NewTerm", listOf("new mishearing")),
            DictionaryEntry("AlwaysSpellExact", emptyList()),
        )
        val restoredProfile = CleaningProfile("custom_restored", "Restored", "desc2", "rules2", false)
        val backup = AppBackup(
            schemaVersion = 1,
            exportedAtMs = 9999L,
            appVersion = "1.23",
            dictionary = newEntries,
            customProfiles = listOf(restoredProfile),
            activeProfileId = "custom_restored",
            settings = SettingsBackup(),
        )

        // Restore dictionary and profiles
        dictStore.replaceEntries(backup.dictionary)
        profileStore.restoreProfiles(backup.customProfiles, backup.activeProfileId)

        // Verify dictionary replaced
        val loadedDict = dictStore.load()
        assertEquals(2, loadedDict.size)
        assertEquals("AlwaysSpellExact", loadedDict[0].correct)
        assertEquals("NewTerm", loadedDict[1].correct)
        assertFalse(loadedDict.any { it.correct == "OldTerm" })

        // Verify profiles replaced
        val loadedProfiles = profileStore.allProfiles()
        assertTrue(loadedProfiles.any { it.id == "smart_clean" && it.isBuiltin })
        assertTrue(loadedProfiles.any { it.id == "conservative" && it.isBuiltin })
        assertTrue(loadedProfiles.any { it.id == "aggressive_rewrite" && it.isBuiltin })
        assertTrue(loadedProfiles.any { it.id == "custom_restored" && !it.isBuiltin })
        assertFalse(loadedProfiles.any { it.id == "custom_initial" })
        assertEquals("custom_restored", profileStore.activeProfile().id)
    }
}
