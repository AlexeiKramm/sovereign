package fi.ak.smartvoice.asr

/**
 * Decides when a **pause-delimited unit** ends (D-032) and reports how long the speaker has been silent,
 * from the signals this project actually has:
 *
 * 1. **The recogniser's own endpoint detector** — a measured pause, and the preferred signal. It costs
 *    nothing (chunk 3 was going to read it anyway) and it works at the audio level.
 * 2. **Quiet audio *and* stable text** — the fallback, for the case where the artifact's endpoint
 *    detection does not fire at all. **Both**, because text stability alone is not a silence detector: a
 *    recogniser that lags behind its input stops producing text while the speaker is still talking, and
 *    cutting there chops a sentence in half mid-word — exactly the bug reported from the phone on
 *    2026-09-12 ("even if I'm talking, sometimes the segment just gets chopped"). The audio level
 *    ([AudioLevel]) is what distinguishes a pause from a slow decoder; the text says there is something to
 *    cut, the audio says the speaker actually stopped.
 *
 * [silenceFor] is tracked separately from the cut timer and is *not* reset by a cut, because it answers a
 * different question: a cut means "speech was heard recently", the silence timer means "the speaker has
 * stopped" — which is what ends the utterance (D-035). Sharing one timer between them made the auto-stop
 * wait for the pause threshold *plus* its own, which is exactly the kind of quiet off-by-a-second that
 * makes a setting feel wrong.
 *
 * Pure on purpose: it runs on numbers, so it is tested on this machine instead of being discovered at
 * someone's cursor.
 */
class UnitBoundary(
    /** How long the recogniser's text must be unchanged before text stability closes a unit. */
    stableSeconds: Double = ListenerTimings.DEFAULT_FALLBACK_QUIET_SECONDS,
    private val sampleRate: Int = SAMPLE_RATE,
) {

    private var stabilitySeconds: Double = stableSeconds
    private var audioSeconds = 0.0
    private var lastChangeAt = 0.0
    private var lastSpeechAt = 0.0
    private var lastSpeech = ""

    /** How much audio has been fed this utterance. */
    val seconds: Double get() = audioSeconds

    /** Seconds the text has been unchanged — the number to show when nothing has cut yet. */
    val stableFor: Double get() = audioSeconds - lastChangeAt

    /** Seconds since the last *new* speech text. Counts from the words, and a cut does not reset it. */
    val silenceFor: Double get() = if (lastSpeechAt > 0.0) audioSeconds - lastSpeechAt else 0.0

    /**
     * How long the audio had been quiet when the last unit was cut. The number that attributes a cut: a
     * cut with ~0 s of quiet while the text was stale is a stalled decoder, not a pause.
     */
    @Volatile
    var quietAtLastCut: Double = 0.0
        private set

    /** True once anything has actually been recognised: silence before speech must not stop a recording. */
    val hasSpeech: Boolean get() = lastSpeechAt > 0.0

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
    fun onFrame(
        sampleCount: Int,
        text: String,
        recogniserSaysEndpoint: Boolean,
        /** How long the *audio* has been quiet — from [AudioLevel], not inferred from the text. */
        audioQuietSeconds: Double,
    ): Boolean {
        if (sampleCount > 0) audioSeconds += sampleCount.toDouble() / sampleRate

        // Only *new speech* moves the clocks. A blank result — before speech, or in the moment after a
        // cut resets the stream — is not speech, and treating it as a change used to reset the silence
        // timer and delay the auto-stop by a whole threshold.
        val speech = text.trim()
        if (speech.isNotEmpty() && speech != lastSpeech) {
            lastSpeech = speech
            lastChangeAt = audioSeconds
            lastSpeechAt = audioSeconds
        }

        if (recogniserSaysEndpoint) {
            cutsByModel++
            quietAtLastCut = audioQuietSeconds
            return true
        }
        // Only text can be cut: silence before the speaker starts must not emit an empty unit. And the
        // audio must actually be quiet — a stalled decoder is not a pause (see the class comment).
        if (speech.isNotEmpty() && stableFor >= stabilitySeconds && audioQuietSeconds >= stabilitySeconds) {
            cutsByTextStability++
            quietAtLastCut = audioQuietSeconds
            return true
        }
        return false
    }

    /** Called by the caller right after a cut, so the stability timer restarts cleanly. */
    fun markCut() {
        lastChangeAt = audioSeconds
        lastSpeech = ""
    }

    /** A consistent reading for the UI thread, which must not poke at the worker's fields. */
    fun snapshot(
        audioQuietFor: Double,
        heardAudio: Boolean,
        partial: String = "",
    ): BoundaryState = BoundaryState(
        cutsByModel = cutsByModel,
        cutsByTextStability = cutsByTextStability,
        stableFor = stableFor,
        silenceFor = silenceFor,
        quietFor = audioQuietFor,
        heardAudio = heardAudio,
        quietAtLastCut = quietAtLastCut,
        partial = partial,
        hasSpeech = hasSpeech,
        seconds = seconds,
    )

    /** Called at the start of an utterance; the counters are per utterance. */
    fun reset() {
        audioSeconds = 0.0
        lastChangeAt = 0.0
        lastSpeechAt = 0.0
        lastSpeech = ""
        cutsByModel = 0
        cutsByTextStability = 0
        quietAtLastCut = 0.0
    }

    /** Applies the fallback threshold, which the settings can change between utterances. */
    fun configureFallback(seconds: Double) {
        stabilitySeconds = seconds
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** A reading of [UnitBoundary]'s state, safe to publish to another thread. */
data class BoundaryState(
    val cutsByModel: Int,
    val cutsByTextStability: Int,
    /** Text unchanged for this long. */
    val stableFor: Double,
    /** No *new* speech text for this long — a fact about the recogniser's output. */
    val silenceFor: Double,
    /** The **audio** has been quiet for this long — a fact about the room (D-038). */
    val quietFor: Double,
    val heardAudio: Boolean,
    /** Audio quiet at the moment the last unit was cut: the number that attributes a cut. */
    val quietAtLastCut: Double,
    /**
     * The recogniser's hypothesis for the unit being spoken. **Display only** — nothing here has been
     * committed, and the cursor only ever sees text through the commit path (D-021, D-041).
     */
    val partial: String = "",
    val hasSpeech: Boolean,
    val seconds: Double,
) {
    val cuts: Int get() = cutsByModel + cutsByTextStability
}
