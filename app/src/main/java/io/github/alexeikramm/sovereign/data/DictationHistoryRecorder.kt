package io.github.alexeikramm.sovereign.data

import java.util.UUID

/**
 * The one place a finalized utterance becomes a [DictationRecord].
 *
 * History used to be written inline by each capture surface (the floating pill and the transcribe
 * screen, with slightly different rules each time) and the desktop server wrote nothing at all. This
 * owns the two invariants that must never diverge between surfaces — **history is off means nothing
 * is written**, and **an empty utterance is not a record** — plus the record's identity, timestamp
 * and capacity. Callers still supply the fields they genuinely own (model label, word count,
 * whether an insertion succeeded), because those differ legitimately: the remote engine has no local
 * model label, and the desktop's injection result is not observable on the phone.
 */
object DictationHistoryRecorder {

    /**
     * Records an utterance and returns the stored record, or null when nothing was written (history
     * disabled, or both texts blank once trimmed). The blank/whitespace-only text is never persisted
     * as a record, but a non-empty raw with empty cleaned (or vice versa) is.
     */
    fun record(
        store: DictationHistoryStore,
        enabled: Boolean,
        capacity: Int,
        rawText: String,
        cleanedText: String,
        durationSeconds: Double,
        asrModel: String,
        llmModel: String?,
        wordsCleaned: Int,
        insertedSuccessfully: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): DictationRecord? {
        if (!enabled) return null
        val raw = rawText.trim()
        val cleaned = cleanedText.trim()
        if (raw.isEmpty() && cleaned.isEmpty()) return null

        val record = DictationRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = nowMs,
            durationSeconds = durationSeconds,
            rawText = raw.ifEmpty { cleaned },
            cleanedText = cleaned.ifEmpty { raw },
            asrModel = asrModel,
            llmModel = llmModel,
            wordsCleaned = wordsCleaned,
            insertedSuccessfully = insertedSuccessfully,
        )
        store.record(record, maxCapacity = capacity)
        return record
    }

    /**
     * The word-diff-derived cleaned-word count for a local utterance — the metric the pill and the
     * desktop server record. [cleanedByLlm] says whether a cleanup pass touched the text, which
     * decides between a real raw→cleaned diff and a verbatim count.
     */
    fun wordsCleaned(rawText: String, cleanedText: String, cleanedByLlm: Boolean): Int {
        val raw = rawText.trim()
        val cleaned = cleanedText.trim()
        val diff = if (raw.isNotEmpty() && (cleanedByLlm || cleaned != raw)) {
            WordDiffCalculator.calculate(rawText = raw, cleanedText = cleaned)
        } else {
            WordDiffCalculator.verbatim(raw.ifEmpty { cleaned })
        }
        return diff.wordsCleaned
    }
}
