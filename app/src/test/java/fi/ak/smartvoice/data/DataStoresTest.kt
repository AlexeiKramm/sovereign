package fi.ak.smartvoice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import fi.ak.smartvoice.ui.stats.DevicePowerState
import org.junit.rules.TemporaryFolder

class DataStoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `dictionary store adds words and aliases sorted without duplicates and supports removal`() {
        val store = DictionaryStore(temp.root)
        assertEquals(0, store.load().size)

        store.addWord("Gemma")
        store.addEntry("Parakeet", listOf("parakit", "parakeete"))
        store.addWord("Android")
        store.addWord("Gemma") // duplicate
        store.addEntry("Parakeet", listOf("perakeet")) // merge alias

        val words = store.getWords()
        assertEquals(listOf("Android", "Gemma", "Parakeet"), words)

        val entries = store.getEntries()
        assertEquals(3, entries.size)
        val parakeet = entries.first { it.word == "Parakeet" }
        assertEquals(listOf("parakit", "parakeete", "perakeet"), parakeet.aliases)

        store.removeEntry("Parakeet")
        assertEquals(listOf("Android", "Gemma"), store.getWords())

        store.clear()
        assertEquals(0, store.load().size)
    }

    @Test
    fun `usage stats store records utterances and calculates metrics`() {
        val store = UsageStatsStore(temp.root)
        var initial = store.load()
        assertEquals(0, initial.totalUtterances)
        assertEquals(0.0, initial.totalAudioSeconds, 0.001)

        store.record(
            durationSeconds = 12.5,
            rawWords = 25,
            cleanedWords = 3,
            engine = "local",
            deliveredWords = 23,
            wordsRemoved = 2,
            wordsChanged = 1,
            wordsAdded = 0,
        )
        store.record(
            durationSeconds = 8.0,
            rawWords = 15,
            cleanedWords = 2,
            engine = "server",
            deliveredWords = 17,
            wordsRemoved = 0,
            wordsChanged = 2,
            wordsAdded = 2,
        )

        val updated = store.load()
        assertEquals(2, updated.totalUtterances)
        assertEquals(20.5, updated.totalAudioSeconds, 0.001)
        assertEquals(40, updated.totalRawWords)
        assertEquals(5, updated.totalCleanedWords)
        assertEquals(5, updated.totalWordsCleaned)
        assertEquals(40, updated.totalDeliveredWords)
        assertEquals(2, updated.totalWordsRemoved)
        assertEquals(3, updated.totalWordsChanged)
        assertEquals(2, updated.totalWordsAdded)
        assertEquals(2, updated.recentRecords.size)
        assertEquals(1.0, updated.typingMinutesSaved, 0.001) // 40 delivered words / 40 WPM = 1 min
    }

    @Test
    fun `profile store provides defaults and manages custom profiles`() {
        val store = ProfileStore(temp.root)
        val builtin = store.allProfiles()
        assertEquals(1, builtin.size)
        assertEquals("smart_clean", store.activeProfile().id)

        val custom = CleaningProfile(
            id = "custom_test",
            name = "Test Profile",
            description = "Test description",
            rules = "Do not modify words.",
            isBuiltin = false,
        )
        store.saveCustomProfile(custom)
        store.setActiveProfile("custom_test")

        assertEquals("custom_test", store.activeProfile().id)
        assertEquals("Test Profile", store.activeProfile().name)

        store.deleteCustomProfile("custom_test")
        assertEquals("smart_clean", store.activeProfile().id)
    }

    @Test
    fun `idle timeout options contain expected intervals and defaults`() {
        assertTrue(AppSettings.IDLE_TIMEOUT_OPTIONS.contains(0))
        assertTrue(AppSettings.IDLE_TIMEOUT_OPTIONS.contains(5))
        assertTrue(AppSettings.DEFAULT_UNLOAD_SCREEN_OFF)
        assertEquals(5, AppSettings.DEFAULT_IDLE_TIMEOUT_MIN)
    }

    @Test
    fun `device power state data class handles default and thermal values`() {
        val state = DevicePowerState(
            batteryPct = 85,
            isCharging = false,
            chargeType = "Discharging",
            batteryTempC = 31.5f,
            thermalStatus = "Cool / Normal",
            thermalHeadroom = 0.8f,
        )
        assertEquals(85, state.batteryPct)
        assertFalse(state.isCharging)
        assertEquals(31.5f, state.batteryTempC, 0.01f)
        assertEquals("Cool / Normal", state.thermalStatus)
    }
}
