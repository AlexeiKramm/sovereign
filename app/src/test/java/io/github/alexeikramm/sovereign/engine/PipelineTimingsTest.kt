package io.github.alexeikramm.sovereign.engine

import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.UtteranceCleanupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTimingsTest {

    @Test
    fun `sequential execution has zero concurrency savings`() {
        val timings = PipelineTimings(
            asrActiveMs = 5000L,
            llmActiveMs = 3000L,
            wallClockMs = 8000L,
            chunksCount = 3,
        )
        assertEquals(0L, timings.concurrencySavedMs)
        assertEquals(1.0, timings.concurrencySpeedup, 0.001)
        assertEquals("ASR: 5,000 ms · LLM: 3,000 ms · Wall: 8,000 ms", timings.formatSummary())
    }

    @Test
    fun `concurrent execution calculates saved time and speedup accurately`() {
        val timings = PipelineTimings(
            asrActiveMs = 8208L,
            llmActiveMs = 17313L,
            wallClockMs = 18721L,
            chunksCount = 6,
        )
        // Sequential sum = 8208 + 17313 = 25521
        // Concurrency saved = 25521 - 18721 = 6800 ms
        assertEquals(6800L, timings.concurrencySavedMs)
        assertEquals(25521.0 / 18721.0, timings.concurrencySpeedup, 0.001)
        assertTrue(timings.formatSummary().contains("ASR: 8,208 ms · LLM: 17,313 ms · Wall: 18,721 ms (saved 6,800 ms via overlap)"))
    }

    @Test
    fun `tracker accumulates multiple chunks correctly`() {
        val tracker = PipelineTimingTracker()
        tracker.start()

        tracker.recordAsrChunk(1200L)
        tracker.recordAsrChunk(1500L)
        tracker.recordAsrChunk(1300L)

        tracker.recordLlmChunk(2100L)
        tracker.recordLlmChunk(2300L)
        tracker.recordLlmChunk(2000L)

        val timings = tracker.stop()
        assertEquals(4000L, timings.asrActiveMs)
        assertEquals(6400L, timings.llmActiveMs)
        assertEquals(3, timings.chunksCount)
    }

    @Test
    fun `tracker supports overriding llm active ms from pipeline state`() {
        val tracker = PipelineTimingTracker()
        tracker.start()
        tracker.recordAsrChunk(1000L)

        val timings = tracker.stop(overrideLlmActiveMs = 4500L)
        assertEquals(1000L, timings.asrActiveMs)
        assertEquals(4500L, timings.llmActiveMs)
    }

    @Test
    fun `utterance cleanup state accumulates total active clean ms and resets`() {
        val state = UtteranceCleanupState()
        assertEquals(0L, state.totalActiveCleanMs)

        val pass1 = CleanupPass(
            unit = "first chunk",
            text = "First chunk.",
            cleaned = true,
            note = "ok",
            activeMs = 1500L,
        )
        state.onPass(pass1)
        assertEquals(1500L, state.totalActiveCleanMs)

        val pass2 = CleanupPass(
            unit = "second chunk",
            text = "Second chunk.",
            cleaned = true,
            note = "ok",
            activeMs = 2200L,
        )
        state.onPass(pass2)
        assertEquals(3700L, state.totalActiveCleanMs)

        state.reset()
        assertEquals(0L, state.totalActiveCleanMs)
    }
}
