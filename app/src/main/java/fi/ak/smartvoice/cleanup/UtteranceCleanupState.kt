package fi.ak.smartvoice.cleanup

import fi.ak.smartvoice.ime.PanelText

/** What one finished unit contributes to the utterance: the text for the cursor, and how it got there. */
data class CleanupCommit(
    val text: String,
    val cleaned: Boolean,
    val note: String,
    val prefix: String = " ",
)

/**
 * The carried context and the counters for one utterance — the part of the cleanup pipeline that is a
 * pure state machine, so its rules are tested on this machine rather than discovered at a cursor:
 *
 * - **Fail closed** (D-024): a pass that failed contributes the recogniser's own unit, verbatim. A
 *   mangled sentence is worse than an unpunctuated one, and a partial generation is never applied.
 * - **The tail is what was committed**, cleaned or not. Feeding only successful passes back would leave
 *   the model's context out of step with what the user is actually looking at — and on a unit where the
 *   pass failed, the raw text *is* the context.
 * - **Append-only, never revised** (D-032 rule 6): text is appended in the order units completed, and
 *   nothing already handed to the cursor is touched again. That is what makes the prefix cacheable and
 *   keeps text from wobbling on screen.
 */
class UtteranceCleanupState {

    private val committed = StringBuilder()

    /** Units finished, and how they got there. */
    var units: Int = 0
        private set

    var cleanedUnits: Int = 0
        private set

    var failedUnits: Int = 0
        private set

    /** The cleaned text so far: the context the next unit's pass is given (D-032 rule 5). */
    val carriedTail: String get() = committed.toString()

    /**
     * Starts from text that is already committed — units that went to the cursor before the cleanup stage
     * engaged, at the start of an utterance or while the model was still loading. Seeding it keeps the
     * model's context equal to what the user can see, which is rule 1 of this class applied to a partially
     * cleaned utterance.
     */
    fun seed(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) committed.append(trimmed)
    }

    fun onPass(pass: CleanupPass): CleanupCommit {
        val raw = if (pass.cleaned) pass.text else pass.unit
        units++
        if (pass.cleaned) cleanedUnits++ else failedUnits++
        val body = raw.trim()
        if (body.isEmpty()) {
            return CleanupCommit(text = "", cleaned = pass.cleaned, note = pass.note, prefix = "")
        }
        val prefix = if (committed.isEmpty()) "" else PanelText.unitPrefix(committed.toString(), raw)
        committed.append(prefix)
        committed.append(body)
        return CleanupCommit(text = body, cleaned = pass.cleaned, note = pass.note, prefix = prefix)
    }

    fun reset() {
        committed.clear()
        units = 0
        cleanedUnits = 0
        failedUnits = 0
    }
}
