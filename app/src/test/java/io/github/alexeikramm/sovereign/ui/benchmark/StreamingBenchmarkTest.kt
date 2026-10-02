package io.github.alexeikramm.sovereign.ui.benchmark

import io.github.alexeikramm.sovereign.asr.AsrModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBenchmarkTest {

    @Test
    fun `matrix varies pause and cold for both cleanup settings`() {
        val cells = StreamingMatrix.cells(listOf(AsrModels.NEMOTRON))

        assertTrue("needs cleanup-on cells", cells.any { it.cleanup })
        assertTrue("needs cleanup-off cells", cells.any { !it.cleanup })
        assertEquals(StreamingMatrix.PAUSES.toSet(), cells.filter { it.cleanup }.map { it.pauseSeconds }.toSet())
        assertEquals(StreamingMatrix.PAUSES.toSet(), cells.filter { !it.cleanup }.map { it.pauseSeconds }.toSet())
        assertTrue("needs cold cells", cells.any { it.cold })
        assertTrue("needs warm cells", cells.any { !it.cold })
    }

    @Test
    fun `healing only appears when cleanup is on`() {
        val cells = StreamingMatrix.cells(listOf(AsrModels.NEMOTRON))

        assertEquals(
            StreamingMatrix.HEALINGS.toSet(),
            cells.filter { it.cleanup }.map { it.healingSentences }.toSet(),
        )
        assertTrue(cells.filter { !it.cleanup }.all { it.healingSentences == 0 })
    }

    @Test
    fun `matrix scales with the number of models`() {
        val one = StreamingMatrix.cells(listOf(AsrModels.NEMOTRON)).size
        val two = StreamingMatrix.cells(listOf(AsrModels.NEMOTRON, AsrModels.PARAKEET_TDT_V3)).size
        assertEquals(one * 2, two)
    }

    @Test
    fun `gaps returns consecutive differences in time order`() {
        assertEquals(listOf(100L, 250L, 50L), StreamingMetrics.gaps(listOf(0L, 100L, 350L, 400L)))
        // Out-of-order input is sorted first, so no negative gap can slip through.
        assertEquals(listOf(100L, 250L, 50L), StreamingMetrics.gaps(listOf(350L, 100L, 0L, 400L)))
        assertEquals(emptyList<Long>(), StreamingMetrics.gaps(emptyList()))
    }

    @Test
    fun `mean and median are stable on empty and even lists`() {
        assertEquals(0.0, StreamingMetrics.mean(emptyList()), 0.0)
        assertEquals(2.5, StreamingMetrics.mean(listOf(1L, 2L, 3L, 4L)), 0.0)
        assertEquals(0L, StreamingMetrics.median(emptyList()))
        assertEquals(2L, StreamingMetrics.median(listOf(3L, 1L, 2L)))
        assertEquals(2L, StreamingMetrics.median(listOf(4L, 2L, 1L, 3L)))
    }

    @Test
    fun `describeCadence summarises non-empty cadences`() {
        assertEquals("—", StreamingMetrics.describeCadence(emptyList()))
        val summary = StreamingMetrics.describeCadence(listOf(100L, 300L))
        assertTrue(summary.contains("200 ms"))
        assertTrue(summary.contains("300 ms"))
    }
}
