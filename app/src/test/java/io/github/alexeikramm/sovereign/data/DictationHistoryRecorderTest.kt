package io.github.alexeikramm.sovereign.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictationHistoryRecorderTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun store() = DictationHistoryStore(temp.root)

    @Test
    fun `disabled writes nothing`() {
        val store = store()
        val record = DictationHistoryRecorder.record(
            store = store,
            enabled = false,
            capacity = 50,
            rawText = "hello",
            cleanedText = "Hello.",
            durationSeconds = 1.0,
            asrModel = "model",
            llmModel = null,
            wordsCleaned = 1,
            insertedSuccessfully = true,
        )
        assertNull(record)
        assertTrue(store.load().records.isEmpty())
    }

    @Test
    fun `blank utterance writes nothing`() {
        val store = store()
        val record = DictationHistoryRecorder.record(
            store = store,
            enabled = true,
            capacity = 50,
            rawText = "   ",
            cleanedText = "",
            durationSeconds = 1.0,
            asrModel = "model",
            llmModel = null,
            wordsCleaned = 0,
            insertedSuccessfully = true,
        )
        assertNull(record)
        assertTrue(store.load().records.isEmpty())
    }

    @Test
    fun `raw only falls back to cleaned and vice versa`() {
        val store = store()
        val rec = DictationHistoryRecorder.record(
            store = store,
            enabled = true,
            capacity = 50,
            rawText = "  hi there  ",
            cleanedText = "",
            durationSeconds = 2.0,
            asrModel = "model",
            llmModel = null,
            wordsCleaned = 0,
            insertedSuccessfully = false,
        )
        assertNotNull(rec)
        val stored = store.load().records.single()
        assertEquals("hi there", stored.rawText)
        assertEquals("hi there", stored.cleanedText)
    }

    @Test
    fun `normal record round trips its fields`() {
        val store = store()
        val rec = DictationHistoryRecorder.record(
            store = store,
            enabled = true,
            capacity = 50,
            rawText = "hello world",
            cleanedText = "Hello, world.",
            durationSeconds = 3.5,
            asrModel = "Nemotron",
            llmModel = "Gemma",
            wordsCleaned = 2,
            insertedSuccessfully = true,
            nowMs = 1234L,
        )!!
        assertEquals(1234L, rec.timestampMs)
        assertEquals("Nemotron", rec.asrModel)
        assertEquals("Gemma", rec.llmModel)
        assertEquals(2, rec.wordsCleaned)
        assertTrue(store.load().records.single().insertedSuccessfully)
    }

    @Test
    fun `capacity is enforced`() {
        val store = store()
        repeat(5) { i ->
            DictationHistoryRecorder.record(
                store = store,
                enabled = true,
                capacity = 3,
                rawText = "utterance $i",
                cleanedText = "Utterance $i.",
                durationSeconds = 1.0,
                asrModel = "model",
                llmModel = null,
                wordsCleaned = 0,
                insertedSuccessfully = true,
                nowMs = i.toLong(),
            )
        }
        assertEquals(3, store.load().records.size)
    }

    @Test
    fun `wordsCleaned is zero for identical verbatim text and positive after a change`() {
        assertEquals(0, DictationHistoryRecorder.wordsCleaned("same text", "same text", cleanedByLlm = false))
        // Punctuation/casing alone is not a word change; removing fillers is.
        assertEquals(0, DictationHistoryRecorder.wordsCleaned("hello world", "Hello, world.", cleanedByLlm = true))
        assertTrue(DictationHistoryRecorder.wordsCleaned("um hello world uh", "Hello world.", cleanedByLlm = true) > 0)
    }
}
