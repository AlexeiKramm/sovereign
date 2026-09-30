package fi.ak.smartvoice.engine

import java.util.Locale

/**
 * Monotonic millisecond clock that runs identically on Android devices and JVM unit tests.
 * Avoids android.os.SystemClock unmocked runtime exceptions in host tests.
 */
object MonotonicClock {
    fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Unified timing metrics across ASR and LLM cleanup pipeline stages.
 *
 * Tracks true active processing durations (from input receipt to output delivery per chunk,
 * summed across all chunks), the total wall-clock duration of the session, and the concurrency
 * overlap savings achieved by running ASR and LLM concurrently.
 */
data class PipelineTimings(
    val asrActiveMs: Long = 0L,
    val llmActiveMs: Long = 0L,
    val wallClockMs: Long = 0L,
    val chunksCount: Int = 1,
) {
    /**
     * Compute time saved by pipelining ASR and LLM concurrently rather than sequentially.
     * In a purely sequential pipeline: wallClockMs >= asrActiveMs + llmActiveMs.
     * When overlapping: wallClockMs < asrActiveMs + llmActiveMs.
     */
    val concurrencySavedMs: Long
        get() = maxOf(0L, (asrActiveMs + llmActiveMs) - wallClockMs)

    /**
     * Speedup factor achieved by concurrency compared to sequential execution:
     * (asrActiveMs + llmActiveMs) / wallClockMs
     */
    val concurrencySpeedup: Double
        get() {
            if (wallClockMs <= 0L) return 1.0
            val sequential = asrActiveMs + llmActiveMs
            return if (sequential > 0L) sequential.toDouble() / wallClockMs.toDouble() else 1.0
        }

    fun formatSummary(): String = buildString {
        append(String.format(Locale.US, "ASR: %,d ms · LLM: %,d ms", asrActiveMs, llmActiveMs))
        if (wallClockMs > 0L) {
            append(String.format(Locale.US, " · Wall: %,d ms", wallClockMs))
        }
        if (concurrencySavedMs > 0L) {
            append(String.format(Locale.US, " (saved %,d ms via overlap)", concurrencySavedMs))
        }
    }
}

/**
 * Reusable accumulator for tracking ASR and LLM chunk processing durations.
 * Used across file transcription, benchmarks, and dictation.
 */
class PipelineTimingTracker(
    private val clock: () -> Long = { MonotonicClock.nowMs() },
) {
    private var wallStartMs: Long = 0L
    private var wallEndMs: Long = 0L
    var asrActiveMs: Long = 0L
        private set
    var llmActiveMs: Long = 0L
        private set
    var chunksCount: Int = 0
        private set

    fun start() {
        wallStartMs = clock()
        wallEndMs = 0L
        asrActiveMs = 0L
        llmActiveMs = 0L
        chunksCount = 0
    }

    fun recordAsrChunk(durationMs: Long) {
        asrActiveMs += durationMs
        chunksCount++
    }

    fun recordLlmChunk(durationMs: Long) {
        llmActiveMs += durationMs
    }

    fun stop(overrideLlmActiveMs: Long? = null): PipelineTimings {
        if (wallEndMs == 0L) {
            wallEndMs = clock()
        }
        val wallClockMs = if (wallStartMs > 0L) wallEndMs - wallStartMs else 0L
        return PipelineTimings(
            asrActiveMs = asrActiveMs,
            llmActiveMs = overrideLlmActiveMs ?: llmActiveMs,
            wallClockMs = wallClockMs,
            chunksCount = maxOf(1, chunksCount),
        )
    }
}
