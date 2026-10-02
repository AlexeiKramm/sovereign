package io.github.alexeikramm.sovereign.ui.benchmark

import io.github.alexeikramm.sovereign.asr.AsrModelSpec

/**
 * One cell of the streaming benchmark matrix: which recogniser, whether cleanup runs, the endpoint
 * pause, the seam-healing window, and whether the run starts cold (engines unloaded) or warm.
 *
 * Healing is only meaningful when cleanup is on, so a cleanup-off cell carries [healingSentences] 0.
 */
data class StreamingCell(
    val asrModel: AsrModelSpec,
    val cleanup: Boolean,
    val pauseSeconds: Float,
    val healingSentences: Int,
    val cold: Boolean,
) {
    val label: String
        get() = buildString {
            append(asrModel.label)
            append(if (cleanup) " + cleanup" else " + raw")
            append(" · pause ${pauseSeconds}s")
            if (cleanup) append(" · heal $healingSentences")
            append(if (cold) " · cold" else " · warm")
        }
}

/** The measured outcome of one cell — the numbers the harness exists to produce. */
data class StreamingCellResult(
    val cell: StreamingCell,
    val fixtureId: String,
    val audioDurationSec: Float,
    val unitCount: Int,
    /** Capture start → first visible text. */
    val timeToFirstTextMs: Long,
    /** First visible text minus when that unit's words first existed in the ASR stream. */
    val deadAirMs: Long,
    /** Endpoint gating: when the first unit was cut minus when its words existed. */
    val endpointGatingMs: Long,
    /** Cleanup lag: when the first unit became visible minus when it was cut. */
    val cleanupLagMs: Long,
    /** Gaps between visible text changes (today: cleaned commits). */
    val visibleCadenceMs: List<Long>,
    /** Gaps between cleanup commits (diverges from visible only if raw preview ships). */
    val commitCadenceMs: List<Long>,
    /** Last audio frame → settled text: the "I stopped talking" latency. */
    val tailLatencyMs: Long,
    val asrActiveMs: Long,
    val llmActiveMs: Long,
    val wallMs: Long,
    val committedText: String,
    val rawText: String,
)

/**
 * The sweep. Pause applies to the recogniser's endpointing whether or not cleanup runs; healing only
 * exists when cleanup runs. Cold/warm is first-class because a short utterance is dominated by model
 * load (see `PLAN.md` §Matrix).
 */
object StreamingMatrix {
    val PAUSES: List<Float> = listOf(0.5f, 0.75f, 1.0f)
    val HEALINGS: List<Int> = listOf(2, 3, 4)

    fun cells(models: List<AsrModelSpec>, cleanupOptions: List<Boolean> = listOf(true, false)): List<StreamingCell> {
        val out = mutableListOf<StreamingCell>()
        for (model in models) {
            for (cleanup in cleanupOptions) {
                for (pause in PAUSES) {
                    for (cold in listOf(false, true)) {
                        if (cleanup) {
                            for (healing in HEALINGS) {
                                out.add(StreamingCell(model, true, pause, healing, cold))
                            }
                        } else {
                            out.add(StreamingCell(model, false, pause, 0, cold))
                        }
                    }
                }
            }
        }
        return out
    }
}

/** Pure list arithmetic for the results table; kept here so it is tested without a device. */
object StreamingMetrics {

    /** Consecutive differences of [timestamps] (sorted first, so a reorder cannot produce a negative). */
    fun gaps(timestamps: List<Long>): List<Long> =
        timestamps.sorted().zipWithNext { a, b -> b - a }

    fun mean(values: List<Long>): Double = if (values.isEmpty()) 0.0 else values.average()

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** A compact "n gaps, median x ms, max y ms" summary for the table. */
    fun describeCadence(values: List<Long>): String =
        if (values.isEmpty()) "—" else "${values.size} · med ${median(values)} ms · max ${values.maxOrNull() ?: 0} ms"
}
