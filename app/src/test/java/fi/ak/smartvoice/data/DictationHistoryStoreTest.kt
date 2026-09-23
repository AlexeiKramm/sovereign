package fi.ak.smartvoice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DictationHistoryStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `initially empty store returns empty records`() {
        val store = DictationHistoryStore(temp.root)
        val data = store.load()
        assertTrue(data.records.isEmpty())
    }

    @Test
    fun `record adds entry to front of history`() {
        val store = DictationHistoryStore(temp.root)
        val record1 = DictationRecord(
            id = "rec-1",
            timestampMs = 1000L,
            durationSeconds = 3.5,
            rawText = "hello world",
            cleanedText = "Hello, world.",
            asrModel = "Nemotron 3.5 (560ms)",
            llmModel = "Gemma 4 E2B",
            wordsCleaned = 1,
            insertedSuccessfully = true,
        )
        val record2 = DictationRecord(
            id = "rec-2",
            timestampMs = 2000L,
            durationSeconds = 5.0,
            rawText = "testing one two",
            cleanedText = "Testing 1, 2.",
            asrModel = "Nemotron 3.5 (560ms)",
            llmModel = "Gemma 4 E2B",
            wordsCleaned = 2,
            insertedSuccessfully = true,
        )

        store.record(record1)
        store.record(record2)

        val loaded = store.load().records
        assertEquals(2, loaded.size)
        // Newest should be first
        assertEquals("rec-2", loaded[0].id)
        assertEquals("Testing 1, 2.", loaded[0].cleanedText)
        assertEquals("rec-1", loaded[1].id)
        assertEquals("Hello, world.", loaded[1].cleanedText)
    }

    @Test
    fun `capacity limits history to maxCapacity and drops oldest records`() {
        val store = DictationHistoryStore(temp.root)
        val capacity = 5

        for (i in 1..10) {
            store.record(
                DictationRecord(
                    id = "id-$i",
                    timestampMs = i * 1000L,
                    durationSeconds = 2.0,
                    rawText = "utterance $i",
                    cleanedText = "Utterance $i.",
                    asrModel = "Nemotron",
                    llmModel = null,
                ),
                maxCapacity = capacity,
            )
        }

        val loaded = store.load().records
        assertEquals(capacity, loaded.size)
        // Most recent should be id-10 down to id-6
        assertEquals("id-10", loaded[0].id)
        assertEquals("id-9", loaded[1].id)
        assertEquals("id-8", loaded[2].id)
        assertEquals("id-7", loaded[3].id)
        assertEquals("id-6", loaded[4].id)
    }

    @Test
    fun `delete removes specific record by id`() {
        val store = DictationHistoryStore(temp.root)
        val r1 = DictationRecord("1", 100L, 1.0, "a", "A", "m")
        val r2 = DictationRecord("2", 200L, 2.0, "b", "B", "m")
        val r3 = DictationRecord("3", 300L, 3.0, "c", "C", "m")

        store.record(r1)
        store.record(r2)
        store.record(r3)

        store.delete("2")

        val loaded = store.load().records
        assertEquals(2, loaded.size)
        assertEquals(listOf("3", "1"), loaded.map { it.id })
    }

    @Test
    fun `clear deletes file and empties history`() {
        val store = DictationHistoryStore(temp.root)
        val r = DictationRecord("1", 100L, 1.0, "text", "Text", "m")
        store.record(r)
        assertEquals(1, store.load().records.size)

        store.clear()
        assertTrue(store.load().records.isEmpty())
        val file = File(temp.root, "dictation_history.json")
        assertTrue(!file.exists())
    }

    @Test
    fun `corrupted JSON file recovers gracefully with empty records`() {
        val file = File(temp.root, "dictation_history.json")
        file.writeText("invalid json content {{{")

        val store = DictationHistoryStore(temp.root)
        val data = store.load()
        assertTrue(data.records.isEmpty())
    }
}
