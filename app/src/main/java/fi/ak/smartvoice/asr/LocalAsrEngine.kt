package fi.ak.smartvoice.asr

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * The on-device streaming recogniser (D-020): feed it frames, ask it for the text.
 *
 * Written concretely for the streaming transducer rather than against a two-engine abstraction —
 * that boundary was proposed and rejected in D-020, because a swap surface only pays for itself if
 * both engines are kept, and the point of that decision is to keep one. The server tier is not a
 * second *ASR engine*: it is a different transport with its own request shape, and it stays where
 * it is (`net/TranscribeClient.kt`).
 *
 * Threading contract, and it is strict: **one thread at a time, always.** A streaming session is
 * inherently serial — frames must arrive in order and results are cumulative — so hashing the
 * calls across threads would be a bug, not an optimisation. Callers run the whole session on the
 * same worker, feed frames from there, and only the UI is posted back to the main thread.
 *
 * Memory contract (D-010): the recogniser is created **on first use, not at process start**, and
 * is then kept resident across utterances because a cold load of a 657 MB encoder is the expensive
 * thing. [unload] exists for the system-pressure signals; nothing here unloads on a timer.
 */
class LocalAsrEngine(
    private val modelDirectory: File,
    private val numThreads: Int = DEFAULT_THREADS,
    pauseSilenceSeconds: Float = DEFAULT_PAUSE_SILENCE_SECONDS,
) {

    /**
     * The pause that ends a cleanup unit (D-032), in seconds of trailing silence after speech.
     *
     * It is a **setting** now, but not a live one: these rules live in the recogniser's own config, so
     * retuning them means rebuilding the recogniser — one cold load (1.9 s measured on this phone), paid
     * on the next utterance, which the status line then reports. That is a fair price for tuning a
     * number by using the thing, and it is stated rather than hidden.
     */
    @Volatile
    var pauseSilenceSeconds: Float = pauseSilenceSeconds
        private set

    /** Applies a new pause threshold. Unloads the recogniser if it actually changed. */
    fun applyPauseSilence(seconds: Float) {
        if (seconds == pauseSilenceSeconds) return
        pauseSilenceSeconds = seconds
        unload()
    }

    sealed interface Availability {
        /** The model files are not on the device yet. Nothing to load. */
        data object NotInstalled : Availability

        /** Installed but not loaded — the state after process start (lazy load, D-010). */
        data object NotLoaded : Availability

        /** Loaded and usable. Sticky until [unload]. */
        data object Loaded : Availability

        /** Installed but the runtime refused it. The reason is shown to the user, never swallowed. */
        data class Unavailable(val reason: String) : Availability
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var failure: String? = null

    /**
     * Wall time of the last successful [load], in ms.
     *
     * D-010 rule 2 is written against a number — "if the measured cold load exceeds ~1.5 s, promote
     * to prefetching while the keyboard is visible" — so the number has to exist somewhere the user
     * can see it. It is reported in the status line of the utterance that paid for it and on the
     * settings screen, not hidden in a log.
     */
    @Volatile
    var lastLoadMillis: Long = 0L
        private set

    val isSessionActive: Boolean get() = stream != null

    fun availability(): Availability = when {
        failure != null -> Availability.Unavailable(failure!!)
        recognizer != null -> Availability.Loaded
        !AsrModels.isInstalled(modelDirectory) -> Availability.NotInstalled
        else -> Availability.NotLoaded
    }

    /**
     * Creates the recogniser if it is not already there. Idempotent and sticky.
     *
     * Called from a worker: this reads ~681 MB off disk and builds three ONNX sessions, so it is
     * seconds of work on first call and must never run on the main thread. A failure here is
     * cached and reported rather than retried on every utterance.
     */
    fun load(): Availability {
        recognizer?.let { return Availability.Loaded }
        failure?.let { return Availability.Unavailable(it) }
        if (!AsrModels.isInstalled(modelDirectory)) return Availability.NotInstalled

        return try {
            val startedAt = SystemClock.elapsedRealtime()
            val config = OnlineRecognizerConfig(
                // 16 kHz mono, 80-bin features: the artifact's expected front end.
                featConfig = FeatureConfig(sampleRate = AsrModels.SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDirectory, "encoder.int8.onnx").absolutePath,
                        decoder = File(modelDirectory, "decoder.int8.onnx").absolutePath,
                        joiner = File(modelDirectory, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(modelDirectory, "tokens.txt").absolutePath,
                    numThreads = numThreads,
                    provider = "cpu",
                    // modelType deliberately left empty. The CPU path in the runtime does not
                    // dispatch on it (it detects the NeMo transducer from the decoder's output
                    // count), and the runtime's own reference config for the Nemotron streaming
                    // artifacts also leaves it empty. Setting it is for the QNN provider only.
                    modelType = "",
                ),
                // The endpoint detector is now load-bearing twice over, and it is why D-032 needs no
                // new dependency: it *is* "the text up to a VAD silence". [isEndpoint] marks the pause
                // that ends a cleanup unit, and the same signal is what chunk 3 will read for
                // stop-on-silence.
                //
                // The numbers are the runtime's own defaults, read out of the artifact's constant pool
                // on 2026-09-12 and written here so that D-032's "first values, tuned on the device"
                // is one edit rather than a library default nobody can see:
                //   rule1 — speech has been decoded, then this much silence → the thought boundary.
                //   rule2 — nothing decoded yet, this much silence → an empty utterance.
                //   rule3 — past this length, rule2's silence also ends the segment: D-032's safety
                //           valve, and note it still needs a pause, so genuinely unbroken speech stays
                //           one unit and is cleaned once at stop (the cost D-032 accepted).
                enableEndpoint = true,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = pauseSilenceSeconds,
                        minUtteranceLength = 0f,
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = EMPTY_SILENCE_SECONDS,
                        minUtteranceLength = 0f,
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0f,
                        minUtteranceLength = SAFETY_VALVE_SECONDS,
                    ),
                ),
            )
            val created = OnlineRecognizer(assetManager = null, config = config)
            recognizer = created
            lastLoadMillis = SystemClock.elapsedRealtime() - startedAt
            Availability.Loaded
        } catch (t: Throwable) {
            // UnsatisfiedLinkError is a link problem, OOM is the model not fitting: both are
            // terminal for the local tier on this device, so they are remembered, not retried.
            val reason = t.message ?: t::class.java.simpleName
            failure = reason
            Availability.Unavailable(reason)
        }
    }

    /**
     * Opens an utterance. [language] is the app's own setting value — `auto`, `fi`, `en`, `ru` —
     * and is passed straight through: the multilingual artifact resolves it against its own
     * language-prompt table, normalising case, `_`→`-` and `fi-FI`→`fi`, and falls back to auto
     * (with a runtime warning) for anything it does not know. Translating here would only add a
     * second place that can disagree with the model.
     */
    fun startSession(language: String, hotwords: String = "") {
        val active = load()
        check(active is Availability.Loaded) { "local engine not loaded: $active" }
        abort()

        val created = recognizer!!.createStream(hotwords)
        created.setOption("language", language)
        stream = created
    }

    /**
     * Feeds frames and returns the current partial text (null when the engine has nothing new).
     * Callers that do not display partials still call this every frame: decoding is what makes the
     * text advance, and v1's rule (D-021) is about not *showing* raw text, not about not decoding.
     */
    fun accept(samples: FloatArray, count: Int = samples.size): String? {
        val active = stream ?: return null
        active.acceptWaveform(samples, AsrModels.SAMPLE_RATE)
        drain()
        return recognizer?.getResult(active)?.text
    }

    /**
     * The text of the segment in progress — cumulative, and final once [isEndpoint] fires and the
     * segment is closed with [resetSegment].
     */
    fun result(): String {
        val active = stream ?: return ""
        return recognizer?.getResult(active)?.text.orEmpty()
    }

    /**
     * Closes the current segment and starts a fresh one on the same stream — D-032's unit boundary.
     *
     * This is not optional once text is committed per unit: without it the recogniser keeps revising
     * the hypothesis it already produced (the streaming decoder's text is cumulative), and text the
     * user has already seen would silently change under them. Resetting trades the recogniser's own
     * cross-pause context for finality, which is the right trade because the *cleanup* stage carries
     * the text context instead (D-032 rule 5).
     */
    fun resetSegment() {
        val active = stream ?: return
        recognizer?.reset(active)
    }

    /** True when the model's own endpoint rules say the speaker stopped (chunk 3's stop signal). */
    fun isEndpoint(): Boolean {
        val active = stream ?: return false
        val engine = recognizer ?: return false
        return engine.isEndpoint(active)
    }

    /**
     * Ends the utterance and returns the complete transcript.
     *
     * The flush is the tail D-020 is about: `inputFinished()` tells the encoder no more frames are
     * coming, and the loop decodes what is left — one chunk plus the flush, not a whole segment.
     * The iteration cap is a guard against a runtime bug turning a stuck stream into a frozen
     * phone; hitting it is reported by the count, not hidden.
     */
    fun finish(): String {
        val active = stream ?: return ""
        val engine = recognizer ?: return ""
        active.inputFinished()

        var decodes = 0
        while (engine.isReady(active) && decodes < MAX_FLUSH_DECODES) {
            engine.decode(active)
            decodes++
        }
        val text = engine.getResult(active).text
        active.release()
        stream = null
        return text
    }

    /** Cancels without a result: the stream is dropped and released, so nothing is committed. */
    fun abort() {
        stream?.release()
        stream = null
    }

    fun unload() {
        abort()
        recognizer?.release()
        recognizer = null
        failure = null
    }

    private fun drain() {
        val active = stream ?: return
        val engine = recognizer ?: return
        while (engine.isReady(active)) {
            engine.decode(active)
        }
    }

    companion object {
        /**
         * 4 of the target device's 8 cores (D-009). The ~10x realtime figure that justified the
         * streaming model was measured with 8 threads, so this is a deliberate under-claim: the
         * IME shares the device with whatever app is in front, and contention on the prime cores
         * is what makes dictation feel slow. Tunable, and measured properly in chunk 4.5.
         */
        const val DEFAULT_THREADS = 4

        private const val FEATURE_DIM = 80

        /**
         * D-032's pause: how much silence after speech ends a cleanup unit.
         *
         * The runtime's own default is 2.4 s; 1.2 s is the tuned starting point, and it is a setting
         * (`AppSettings.pauseSilenceMs`) rather than a constant because this is a number a person tunes
         * by speaking. `UnitBoundary`'s stability fallback covers artifacts whose endpoint detection
         * does not fire at all, and its counters say which signal is doing the cutting.
         */
        const val DEFAULT_PAUSE_SILENCE_SECONDS = 1.2f

        /** Silence with nothing decoded — an utterance that never started. */
        const val EMPTY_SILENCE_SECONDS = 1.4f

        /** Past this much speech, a pause also ends the segment: D-032's safety valve. */
        const val SAFETY_VALVE_SECONDS = 20.0f

        /** See [finish]: a boundary, not a budget. */
        private const val MAX_FLUSH_DECODES = 512
    }
}
