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
    fun `dictionary store adds corrections and terms sorted without duplicates and supports removal`() {
        val store = DictionaryStore(temp.root)
        assertEquals(0, store.load().size)

        store.addCorrection("Gemma")
        store.addCorrection("Parakeet", listOf("parakit", "parakeete"))
        store.addCorrection("Android")
        store.addCorrection("Gemma") // duplicate
        store.addCorrection("Parakeet", listOf("perakeet")) // merge another mishearing

        val words = store.getWords()
        assertEquals(listOf("Android", "Gemma", "Parakeet"), words)

        val entries = store.getEntries()
        assertEquals(3, entries.size)
        val parakeet = entries.first { it.correct == "Parakeet" }
        assertEquals(listOf("parakit", "parakeete", "perakeet"), parakeet.incorrect)

        store.removeEntry("Parakeet")
        assertEquals(listOf("Android", "Gemma"), store.getWords())

        store.clear()
        assertEquals(0, store.load().size)
    }

    @Test
    fun `legacy word and aliases files migrate to correct and incorrect`() {
        val legacy = """
            {
              "entries": [
                {"word": "Parakeet", "aliases": ["parakit", "parakeete"]},
                {"word": "Kotlin"}
              ],
              "words": ["Gemma", "Parakeet"]
            }
        """.trimIndent()
        java.io.File(temp.root, "dictionary.json").writeText(legacy, Charsets.UTF_8)

        val entries = DictionaryStore(temp.root).getEntries()
        assertEquals(listOf("Gemma", "Kotlin", "Parakeet"), entries.map { it.correct })
        assertEquals(
            listOf("parakit", "parakeete"),
            entries.first { it.correct == "Parakeet" }.incorrect,
        )
        assertEquals(emptyList<String>(), entries.first { it.correct == "Kotlin" }.incorrect)
    }

    @Test
    fun `rows expand to one per mishearing plus a bare term, and the last removal drops the entry`() {
        val store = DictionaryStore(temp.root)
        store.addCorrection("Parakeet", listOf("parakit", "parakeete"))
        store.addCorrection("Gemma")

        val rows = DictionaryRows.of(store.getEntries())
        assertEquals(
            listOf(
                DictionaryRow(incorrect = null, correct = "Gemma"),
                DictionaryRow(incorrect = "parakit", correct = "Parakeet"),
                DictionaryRow(incorrect = "parakeete", correct = "Parakeet"),
            ),
            rows,
        )

        // Removing one of two mishearings keeps the entry; removing the last drops it, so deleting
        // a row never leaves a surprise "always spell" line behind.
        store.removeCorrection("Parakeet", "parakit")
        assertEquals(listOf("parakeete"), store.getEntries().first { it.correct == "Parakeet" }.incorrect)
        store.removeCorrection("Parakeet", "parakeete")
        assertEquals(listOf("Gemma"), store.getWords())
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
        // Plan step 1 ("default fixes"): models rest for 2 minutes by default.
        assertEquals(2, AppSettings.DEFAULT_IDLE_TIMEOUT_MIN)
    }

    @Test
    fun `ui overhaul default fixes are pinned`() {
        // Plan step 1 set these; the dashboard and settings screens read them as fact.
        assertEquals(3, AppSettings.DEFAULT_HEALING_SENTENCES_COUNT)
        assertTrue(AppSettings.DEFAULT_LITERT_SPECULATIVE_DECODING)
        assertTrue(AppSettings.DEFAULT_FLOATING_PILL_ENABLED)
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
