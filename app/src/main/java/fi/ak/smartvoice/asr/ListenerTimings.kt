package fi.ak.smartvoice.asr

/**
 * The listener's timings for one utterance, read from the app's settings at the start of it.
 *
 * They are settings rather than constants because they are the numbers a person tunes by *using* the
 * thing — "that pause was too short", "it stopped too early" — and until now every such adjustment cost
 * an APK build, a hand-install and a pasted report. One of them is a hard limit on a recogniser's
 * config (see [LocalAsrEngine.applyPauseSilence]) and one is a software rule, but from the outside both
 * are just "how long is a pause".
 */
data class ListenerTimings(
    /** Text unchanged for this long closes a cleanup unit, when the recogniser's own endpoint is quiet. */
    val unitStableSeconds: Double = DEFAULT_UNIT_STABLE_SECONDS,
    /** Silence after speech this long ends the recording. 0 disables the auto-stop entirely. */
    val stopAfterSilenceSeconds: Double = DEFAULT_STOP_AFTER_SILENCE_SECONDS,
) {
    companion object {
        const val DEFAULT_UNIT_STABLE_SECONDS = 1.8
        const val DEFAULT_STOP_AFTER_SILENCE_SECONDS = 3.0
    }
}
