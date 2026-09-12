package fi.ak.smartvoice.asr

/**
 * Decides when a **pause-delimited unit** ends (D-032), from the two signals this project actually
 * has, and counts which one fired:
 *
 * 1. **The recogniser's own endpoint detector** — the measured pause, and the preferred signal. It
 *    costs nothing (chunk 3 was going to read it anyway) and it knows about trailing silence at the
 *    audio level.
 * 2. **Text stability** — the fallback. On the device, 2026-09-12, a dictation with deliberate pauses
 *    produced *no* unit boundary at all: text landed only when the user tapped stop. Either the pause
 *    was shorter than the rule's 2.4 s of trailing silence, or this artifact's endpoint detection does
 *    not fire — and from inside the app the two look identical. A fallback that needs no model support
 *    is what makes the feature work either way, and the counters below are what make the next report
 *    say which one it was.
 *
 * Pure on purpose: the arithmetic runs on numbers, so it is unit-tested on this machine instead of
 * being discovered at someone's cursor.
 */
/** A reading of [UnitBoundary]'s state, safe to publish to another thread. */
data class BoundaryState(
    val cutsByModel: Int,
    val cutsByTextStability: Int,
    val stableFor: Double,
    val seconds: Double,
) {
    val cuts: Int get() = cutsByModel + cutsByTextStability
}

class UnitBoundary(
    /** How long the recogniser's text must be unchanged before text stability closes a unit. */
    private val stableSeconds: Double = DEFAULT_STABLE_SECONDS,
    private val sampleRate: Int = SAMPLE_RATE,
) {

    private var audioSeconds = 0.0
    private var lastChangeAt = 0.0
    private var lastText = ""

    /** How much audio has been fed this utterance. */
    val seconds: Double get() = audioSeconds

    /** Seconds the text has been unchanged — the number to show when nothing is firing. */
    val stableFor: Double get() = audioSeconds - lastChangeAt

    /** Units closed because the recogniser's endpoint rules fired. */
    var cutsByModel: Int = 0
        private set

    /** Units closed by the stability fallback, i.e. the recogniser never said "endpoint". */
    var cutsByTextStability: Int = 0
        private set

    val cuts: Int get() = cutsByModel + cutsByTextStability

    /**
     * One frame of audio. Returns true when this frame closes a unit — the caller then reads the
     * segment's text, resets the recogniser's stream and calls [markCut].
     */
    fun onFrame(sampleCount: Int, text: String, recogniserSaysEndpoint: Boolean): Boolean {
        if (sampleCount > 0) audioSeconds += sampleCount.toDouble() / sampleRate
        if (text != lastText) {
            lastText = text
            lastChangeAt = audioSeconds
        }
        if (recogniserSaysEndpoint) {
            cutsByModel++
            return true
        }
        // Only text can be cut: silence before the speaker starts must not emit an empty unit.
        if (text.isNotBlank() && stableFor >= stableSeconds) {
            cutsByTextStability++
            return true
        }
        return false
    }

    /** Called by the caller right after a cut, so the stability timer restarts cleanly. */
    fun markCut() {
        lastChangeAt = audioSeconds
        lastText = ""
    }

    /** A consistent reading for the UI thread, which must not poke at the worker's fields. */
    fun snapshot(): BoundaryState = BoundaryState(
        cutsByModel = cutsByModel,
        cutsByTextStability = cutsByTextStability,
        stableFor = stableFor,
        seconds = seconds,
    )

    /** Called at the start of an utterance; the counters are per utterance. */
    fun reset() {
        audioSeconds = 0.0
        lastChangeAt = 0.0
        lastText = ""
        cutsByModel = 0
        cutsByTextStability = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * Longer than the recogniser's pause threshold on purpose, so the model's own signal wins when
         * it works and this only takes over when it does not.
         */
        const val DEFAULT_STABLE_SECONDS = 1.8
    }
}
