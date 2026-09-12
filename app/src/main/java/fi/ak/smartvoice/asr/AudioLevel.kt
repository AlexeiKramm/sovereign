package fi.ak.smartvoice.asr

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How loud the input is, and how long it has been quiet — the audio-level answer to "has the speaker
 * stopped?".
 *
 * It exists because the text-stability fallback could not answer that question, and the difference is a
 * bug found on the phone (2026-09-12): *"even if I'm talking, sometimes the segment just gets chopped"*.
 * A recogniser that lags behind its input looks **exactly like silence** when you only watch its output —
 * the text stops growing because the decoder is behind, not because the speaker stopped — so a rule that
 * cuts on "text unchanged for N seconds" cuts a sentence in half mid-word. Watching the audio instead of
 * the recogniser's output is what tells the two apart.
 *
 * The rule is relative on purpose, so it needs no calibration per room or per phone: a frame is quiet when
 * its level is a small fraction of the recent *speech* level. The reference rises instantly (one loud
 * syllable sets it) and decays over seconds, so a quiet passage half a minute later is still judged against
 * what speech looks like here. An absolute floor keeps a quiet room from making everything "speech".
 *
 * Pure: it runs on numbers, so the arithmetic and the decision are tested on this machine.
 */
class AudioLevel(
    private val sampleRate: Int = SAMPLE_RATE,
    private val quietFraction: Double = QUIET_FRACTION,
    private val minQuietRms: Double = MIN_QUIET_RMS,
    private val minReference: Double = MIN_REFERENCE,
    private val decaySeconds: Double = DECAY_SECONDS,
) {

    private var seconds = 0.0
    private var quietSeconds = 0.0
    private var reference = 0.0
    private var loudest = 0.0

    /** Seconds of continuous quiet so far. Resets to zero on any frame that counts as speech. */
    val quietFor: Double get() = quietSeconds

    /** Audio has been measured this utterance at all. */
    val hasAudio: Boolean get() = seconds > 0.0

    /** Something loud enough to be speech has been heard — the gate for the auto-stop. */
    val heardSpeech: Boolean get() = loudest > minQuietRms

    /** The reference (speech) level, for the report. */
    val level: Double get() = reference

    /** Feeds one frame of `[-1, 1)` samples; returns [quietFor]. */
    fun onFrame(samples: FloatArray): Double {
        val duration = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0
        seconds += duration
        val rms = rms(samples)
        reference = maxOf(rms, reference * exp(-duration / decaySeconds))
        loudest = maxOf(loudest, rms)
        // The floor applies to the *reference*, not just to the quiet threshold: in a very quiet room the
        // measured level is room tone, and judging "is this quiet?" against room tone would call a soft
        // voice speech and a pause nothing at all.
        val threshold = maxOf(maxOf(reference, minReference) * quietFraction, minQuietRms)
        if (rms < threshold) quietSeconds += duration else quietSeconds = 0.0
        return quietSeconds
    }

    fun reset() {
        seconds = 0.0
        quietSeconds = 0.0
        reference = 0.0
        loudest = 0.0
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * A frame is quiet below this fraction of the recent speech level. First value, like every other
         * threshold in this project: generous, because calling speech "quiet" is what chops a sentence,
         * and the model's own endpoint remains the primary signal either way.
         */
        const val QUIET_FRACTION = 0.15

        /** Below this, it is silence whatever the reference says — a room, not a voice. */
        const val MIN_QUIET_RMS = 0.003

        /** The reference never falls below this, so a very quiet input still has a floor to compare to. */
        const val MIN_REFERENCE = 0.01

        /** How fast the speech reference decays: a long time, because pauses are the interesting events. */
        const val DECAY_SECONDS = 10.0

        /** Root mean square of a frame of `[-1, 1)` samples. */
        fun rms(samples: FloatArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (sample in samples) sum += sample.toDouble() * sample
            return sqrt(sum / samples.size)
        }
    }
}
