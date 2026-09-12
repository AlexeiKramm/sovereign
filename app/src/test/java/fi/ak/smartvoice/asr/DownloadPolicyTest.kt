package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    @Test
    fun `nothing downloaded means start from zero`() {
        assertEquals(0L, DownloadPolicy.resumeOffset(partialBytes = 0L, expectedBytes = 1_000L))
    }

    @Test
    fun `a partial file resumes at its own length`() {
        assertEquals(400L, DownloadPolicy.resumeOffset(partialBytes = 400L, expectedBytes = 1_000L))
    }

    @Test
    fun `an over-long partial file is not evidence to resume from`() {
        // A swapped artifact, or a server that sent a different body: appending here would build a
        // file that can never verify, so the honest answer is to start over.
        assertEquals(0L, DownloadPolicy.resumeOffset(partialBytes = 1_200L, expectedBytes = 1_000L))
    }

    @Test
    fun `an exactly complete partial file restarts instead of appending nothing`() {
        assertEquals(0L, DownloadPolicy.resumeOffset(partialBytes = 1_000L, expectedBytes = 1_000L))
    }

    @Test
    fun `a file is accepted only when length and digest both match`() {
        assertTrue(DownloadPolicy.accepted(10L, 10L, "ab".repeat(32), "AB".repeat(32)))
        assertFalse("truncated", DownloadPolicy.accepted(9L, 10L, "ab".repeat(32), "ab".repeat(32)))
        assertFalse("wrong artifact", DownloadPolicy.accepted(10L, 10L, "cd".repeat(32), "ab".repeat(32)))
    }

    @Test
    fun `overall progress spans the whole artifact, not one file`() {
        assertEquals(0f, DownloadPolicy.overallProgress(0L, 0L, 1_000L), 1e-6f)
        assertEquals(0.5f, DownloadPolicy.overallProgress(400L, 100L, 1_000L), 1e-6f)
        assertEquals(1f, DownloadPolicy.overallProgress(1_000L, 0L, 1_000L), 1e-6f)
    }

    @Test
    fun `progress never leaves zero to one even if the numbers lie`() {
        assertEquals(1f, DownloadPolicy.overallProgress(2_000L, 0L, 1_000L), 1e-6f)
        assertEquals(0f, DownloadPolicy.overallProgress(0L, 0L, 0L), 1e-6f)
    }
}
