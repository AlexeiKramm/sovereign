package fi.ak.smartvoice.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is small and hardcoded on purpose, which makes it exactly the kind of file that
 * rots quietly: a mistyped repository id or a precision that does not run on the NPU fails only on
 * the phone, and there is no adb on this machine. These tests pin the properties that make an entry
 * usable rather than the values themselves.
 */
class CleanupModelsTest {

    @Test
    fun `the catalogue is not empty and the default is in it`() {
        assertTrue(CleanupModels.ALL.isNotEmpty())
        assertTrue(CleanupModels.DEFAULT in CleanupModels.ALL)
    }

    @Test
    fun `every entry is Q4_0, because the NPU requires it`() {
        // Not a preference (D-029): another precision silently falls back to GPU/CPU, so a
        // non-Q4_0 entry would make the settings screen lie about what the phone will do.
        CleanupModels.ALL.forEach { model ->
            assertEquals("${model.label} precision", "Q4_0", model.precision)
        }
    }

    @Test
    fun `repository ids are org slash name and unique`() {
        val ids = CleanupModels.ALL.map { it.repository }
        assertEquals("ids must not repeat", ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertEquals("$id looks malformed", 1, id.count { it == '/' })
            assertFalse("$id must have no whitespace", id.any { it.isWhitespace() })
        }
    }

    @Test
    fun `every entry declares a size, so the screen never shows a blank`() {
        CleanupModels.ALL.forEach { model ->
            assertTrue("${model.label} size", model.approximateBytes > 0L)
            assertFalse(CleanupModels.sizeLabel(model.approximateBytes).isBlank())
        }
    }

    @Test
    fun `an unknown or unset preference falls back to the default`() {
        assertEquals(CleanupModels.DEFAULT, CleanupModels.byRepository(null))
        assertEquals(CleanupModels.DEFAULT, CleanupModels.byRepository(""))
        assertEquals(CleanupModels.DEFAULT, CleanupModels.byRepository("someone/typo-gguf"))
        CleanupModels.ALL.forEach { model ->
            assertEquals(model, CleanupModels.byRepository(model.repository))
        }
    }

    @Test
    fun `sizes read as GB above a billion, MB below, and never as zero`() {
        assertEquals("3.35 GB", CleanupModels.sizeLabel(3_350_000_000L))
        assertEquals("681 MB", CleanupModels.sizeLabel(681_000_000L))
        assertEquals("size unknown", CleanupModels.sizeLabel(0L))
        assertEquals("size unknown", CleanupModels.sizeLabel(-1L))
    }

    @Test
    fun `progress is the share of all bytes, and names the file still moving`() {
        val progress = CleanupModels.progressOf(
            listOf(
                FileBytes("first.gguf", downloaded = 500, total = 1_000),
                FileBytes("second.gguf", downloaded = 0, total = 1_000),
            ),
        )
        assertEquals(0.25f, progress.overall, 0.0001f)
        assertEquals(500L, progress.downloadedBytes)
        assertEquals(2_000L, progress.totalBytes)
        assertEquals("first.gguf", progress.fileName)
        assertEquals(0, progress.fileIndex)
        assertEquals(2, progress.totalFiles)
    }

    @Test
    fun `a finished download reads as complete, and an empty event list does not divide by zero`() {
        val done = CleanupModels.progressOf(
            listOf(FileBytes("only.gguf", downloaded = 10, total = 10)),
        )
        assertEquals(1f, done.overall, 0.0001f)
        assertEquals("only.gguf", done.fileName)

        val nothing = CleanupModels.progressOf(emptyList())
        assertEquals(0f, nothing.overall, 0.0001f)
        assertEquals(0, nothing.totalFiles)
        assertEquals("", nothing.fileName)
    }

    @Test
    fun `progress never reports more than everything`() {
        // The SDK sums byte counts per file; a resumed file can briefly report more downloaded than
        // total, and a progress bar above 1 crashes the indicator rather than merely looking wrong.
        val odd = CleanupModels.progressOf(
            listOf(FileBytes("weird.gguf", downloaded = 1_200, total = 1_000)),
        )
        assertEquals(1f, odd.overall, 0.0001f)
    }
}
