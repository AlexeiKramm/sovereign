package io.github.alexeikramm.sovereign.data

import java.util.UUID

/**
 * One finalized utterance in the canonical metric vocabulary every surface shares (D-122/D-124).
 *
 * The word counts are never supplied directly: [FinalizedUtteranceRecorder] derives them from
 * [rawText]/[cleanedText] (or, on the desktop tier, from [remoteCorrections]) so no surface can
 * invent its own `wordsEdited` (decisions 1 and 4). [durationSeconds] is always the **audio length**
 * — the seconds actually spoken for a live capture, the file's length for a file (decision 3).
 *
 * The timing and outcome fields stay per-surface because only some surfaces can observe them:
 * [latencyMs] is the speech-stop→delivery turnaround (the pill alone), [wallClockMs] is the surface's
 * own wall-clock measure, and a caller reports [insertedSuccessfully] only when it saw the text land.
 */
data class FinalizedUtterance(
    val rawText: String,
    val cleanedText: String,
    /** Audio length in seconds (spoken seconds, or the file's length). */
    val durationSeconds: Double,
    /** The engine that produced it, shown in the stats list ("local" / "server"). */
    val engine: String,
    val asrModel: String,
    val llmModel: String? = null,
    /** Whether an LLM cleanup pass produced [cleanedText]; drives the diff-vs-verbatim choice. */
    val cleanedByLlm: Boolean = false,
    /** The remote (home-PC) tier's corrections count; when set it is the `wordsEdited` count. */
    val remoteCorrections: Int? = null,
    val insertedSuccessfully: Boolean = true,
    val asrMs: Long = 0L,
    val llmMs: Long = 0L,
    val wallClockMs: Long = 0L,
    val latencyMs: Long = 0L,
)

/**
 * The one place a finalized utterance becomes recorded output: a [DictationRecord] in history and an
 * [UtteranceRecord] in usage stats, from the same canonical metric set (CHUNK-5, D-123/D-124).
 *
 * D-122 made history a single writer; usage stats had the same drift — written inline in five places
 * with three `cleanedWords` definitions — and the desktop server wrote none, so a desktop dictation
 * never appeared in Usage stats. Now every surface (the pill, both transcribe paths and the desktop
 * server) calls [record]: **history disabled ⇒ no history record**, **a blank utterance is not a
 * record**, and the count, name and duration are decided here rather than per caller.
 */
object FinalizedUtteranceRecorder {

    /**
     * Records [utterance], returning the history record it wrote, or null when no history was written
     * (history disabled, or a blank utterance). Usage stats are written whenever [utterance] holds any
     * text — they are not gated by the history privacy setting, matching the previous behaviour.
     */
    fun record(
        historyStore: DictationHistoryStore,
        usageStatsStore: UsageStatsStore,
        historyEnabled: Boolean,
        capacity: Int,
        utterance: FinalizedUtterance,
        nowMs: Long = System.currentTimeMillis(),
    ): DictationRecord? {
        val raw = utterance.rawText.trim()
        val cleaned = utterance.cleanedText.trim()
        if (raw.isEmpty() && cleaned.isEmpty()) return null
        val edits = edits(utterance)

        usageStatsStore.record(
            durationSeconds = utterance.durationSeconds,
            rawWords = edits.rawWords,
            wordsEdited = edits.wordsEdited,
            engine = utterance.engine,
            deliveredWords = edits.deliveredWords,
            wordsRemoved = edits.wordsRemoved,
            wordsChanged = edits.wordsChanged,
            wordsAdded = edits.wordsAdded,
            asrDurationMs = utterance.asrMs,
            llmDurationMs = utterance.llmMs,
            wallClockMs = utterance.wallClockMs,
            latencyMs = utterance.latencyMs,
        )

        if (!historyEnabled) return null
        val record = DictationRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = nowMs,
            durationSeconds = utterance.durationSeconds,
            rawText = raw.ifEmpty { cleaned },
            cleanedText = cleaned.ifEmpty { raw },
            asrModel = utterance.asrModel,
            llmModel = utterance.llmModel,
            wordsEdited = edits.wordsEdited,
            insertedSuccessfully = utterance.insertedSuccessfully,
        )
        historyStore.record(record, maxCapacity = capacity)
        return record
    }

    /**
     * The canonical word-edit counts. A remote utterance reports only a corrections count (the phone
     * never sees the PC's raw text), which maps onto the same `wordsEdited` number (decision 4).
     */
    fun edits(utterance: FinalizedUtterance): WordDiffResult {
        val corrections = utterance.remoteCorrections
        if (corrections != null) return WordDiffCalculator.remote(utterance.cleanedText, corrections)
        return localEdits(utterance.rawText, utterance.cleanedText, utterance.cleanedByLlm)
    }

    /**
     * The local raw→cleaned diff, or a verbatim count when no cleanup pass touched the text. The
     * `cleaned != raw` term still diffs a text that differs only in ways cleanup made (so the same
     * words are counted however the caller reports `cleanedByLlm`), while identical text counts zero.
     */
    fun localEdits(rawText: String, cleanedText: String, cleanedByLlm: Boolean): WordDiffResult {
        val raw = rawText.trim()
        val cleaned = cleanedText.trim()
        return if (raw.isNotEmpty() && (cleanedByLlm || cleaned != raw)) {
            WordDiffCalculator.calculate(rawText = raw, cleanedText = cleaned)
        } else {
            WordDiffCalculator.verbatim(raw.ifEmpty { cleaned })
        }
    }
}
