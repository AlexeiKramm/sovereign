package io.github.alexeikramm.sovereign.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the canonical metric mapping at the one writer both outputs go through (CHUNK-5):
 * history and usage stats from one call, `wordsEdited = removed + changed + added`, and the
 * audio-length duration.
 */
class FinalizedUtteranceRecorderTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun history() = DictationHistoryStore(temp.root)
    private fun stats() = UsageStatsStore(temp.root)

    private fun utterance(
        rawText: String,
        cleanedText: String,
        cleanedByLlm: Boolean = false,
        remoteCorrections: Int? = null,
        durationSeconds: Double = 1.0,
    ) = FinalizedUtterance(
        rawText = rawText,
        cleanedText = cleanedText,
        durationSeconds = durationSeconds,
        engine = "local",
        asrModel = "Nemotron",
        llmModel = if (cleanedByLlm) "Gemma" else null,
        cleanedByLlm = cleanedByLlm,
        remoteCorrections = remoteCorrections,
        insertedSuccessfully = true,
    )

    @Test
    fun `one call writes the same count to history and usage stats`() {
        val history = history()
        val stats = stats()
        FinalizedUtteranceRecorder.record(
            historyStore = history,
            usageStatsStore = stats,
            historyEnabled = true,
            capacity = 50,
            utterance = utterance("um hello world uh", "Hello world.", cleanedByLlm = true, durationSeconds = 3.5),
            nowMs = 1234L,
        )

        val record = history.load().records.single()
        assertEquals(1234L, record.timestampMs)
        assertEquals(3.5, record.durationSeconds, 0.001)
        assertEquals("Gemma", record.llmModel)
        assertEquals(2, record.wordsEdited)

        val usage = stats.load()
        assertEquals(1, usage.totalUtterances)
        assertEquals(2, usage.totalWordsEdited)
        assertEquals(3.5, usage.totalAudioSeconds, 0.001)
        assertEquals(2, usage.recentRecords.single().wordsEdited)
        // The two stores agree on the count for the same utterance.
        assertEquals(record.wordsEdited, usage.recentRecords.single().wordsEdited)
    }

    @Test
    fun `history disabled still writes usage stats but writes no record`() {
        val history = history()
        val stats = stats()
        val record = FinalizedUtteranceRecorder.record(
            historyStore = history,
            usageStatsStore = stats,
            historyEnabled = false,
            capacity = 50,
            utterance = utterance("hello", "Hello.", durationSeconds = 1.0),
        )
        assertNull(record)
        assertTrue(history.load().records.isEmpty())
        assertEquals(1, stats.load().totalUtterances)
    }

    @Test
    fun `blank utterance writes neither history nor stats`() {
        val history = history()
        val stats = stats()
        val record = FinalizedUtteranceRecorder.record(
            historyStore = history,
            usageStatsStore = stats,
            historyEnabled = true,
            capacity = 50,
            utterance = utterance("   ", "", durationSeconds = 1.0),
        )
        assertNull(record)
        assertTrue(history.load().records.isEmpty())
        assertEquals(0, stats.load().totalUtterances)
    }

    @Test
    fun `raw only falls back to cleaned and vice versa`() {
        val history = history()
        val record = FinalizedUtteranceRecorder.record(
            historyStore = history,
            usageStatsStore = stats(),
            historyEnabled = true,
            capacity = 50,
            utterance = utterance("  hi there  ", "", durationSeconds = 2.0),
        )
        assertNotNull(record)
        val stored = history.load().records.single()
        assertEquals("hi there", stored.rawText)
        assertEquals("hi there", stored.cleanedText)
    }

    @Test
    fun `capacity is enforced`() {
        val history = history()
        repeat(5) { i ->
            FinalizedUtteranceRecorder.record(
                historyStore = history,
                usageStatsStore = stats(),
                historyEnabled = true,
                capacity = 3,
                utterance = utterance("utterance $i", "Utterance $i.", cleanedByLlm = true),
                nowMs = i.toLong(),
            )
        }
        assertEquals(3, history.load().records.size)
    }

    @Test
    fun `wordsEdited counts removals changes and insertions`() {
        assertEquals(0, FinalizedUtteranceRecorder.localEdits("same text", "same text", cleanedByLlm = false).wordsEdited)
        // Punctuation/casing alone is not a word change; removing fillers is.
        assertEquals(0, FinalizedUtteranceRecorder.localEdits("hello world", "Hello, world.", cleanedByLlm = true).wordsEdited)
        assertEquals(2, FinalizedUtteranceRecorder.localEdits("um hello world uh", "Hello world.", cleanedByLlm = true).wordsEdited)
        // An insertion is an edit too, under the canonical definition (D-124 decision 1).
        assertEquals(2, FinalizedUtteranceRecorder.localEdits("In spite of", "In spite of it all.", cleanedByLlm = true).wordsEdited)
    }

    @Test
    fun `remote corrections map onto wordsEdited`() {
        val stats = stats()
        FinalizedUtteranceRecorder.record(
            historyStore = history(),
            usageStatsStore = stats,
            historyEnabled = true,
            capacity = 50,
            utterance = FinalizedUtterance(
                rawText = "Hello there friend",
                cleanedText = "Hello there friend",
                durationSeconds = 4.0,
                engine = "server",
                asrModel = "Server",
                remoteCorrections = 3,
                insertedSuccessfully = false,
            ),
        )
        val record = stats.load().recentRecords.single()
        assertEquals(3, record.wordsEdited)
        assertEquals(3, record.wordsChanged)
        assertEquals(3, record.rawWords) // tokenized delivered text
    }
}
