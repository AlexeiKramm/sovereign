package io.github.alexeikramm.sovereign.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cumulative ASR seam-revision counters (D-140). They accumulate over a long period and are the
 * evidence for whether revisions ever matter, so their arithmetic and their tolerance of a corrupt
 * file both need to be pinned.
 */
class AsrRevisionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `recording accumulates revisions and diverged characters`() {
        val store = AsrRevisionStore(temp.root)
        assertEquals(AsrRevisionData(), store.load())

        store.record(revisions = 2, divergedChars = 7)
        store.record(revisions = 1, divergedChars = 3)

        assertEquals(AsrRevisionData(revisions = 3, divergedChars = 10), store.load())
    }

    @Test
    fun `a zero record is a no-op`() {
        val store = AsrRevisionStore(temp.root)
        store.record(revisions = 0, divergedChars = 0)
        assertEquals(AsrRevisionData(), store.load())
    }

    @Test
    fun `a corrupt file loads as empty rather than throwing`() {
        val store = AsrRevisionStore(temp.root)
        store.record(revisions = 1, divergedChars = 1)
        java.io.File(temp.root, "asr_revision_stats.json").writeText("{ not json")

        assertEquals(AsrRevisionData(), store.load())
    }

    @Test
    fun `clear resets the counters`() {
        val store = AsrRevisionStore(temp.root)
        store.record(revisions = 5, divergedChars = 12)
        store.clear()
        assertEquals(AsrRevisionData(), store.load())
    }
}
