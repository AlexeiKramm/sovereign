package fi.ak.smartvoice.asr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One utterance on the local tier: the frame queue and the single ASR worker, in one place.
 *
 * It exists because **two** surfaces dictate — the IME panel and the settings/diagnostics screen —
 * and both need the same three things done the same way: frames handed across the thread boundary
 * without blocking the capture thread, a streaming session that stays serial, and a flush at the
 * end that is actually awaited. Two copies of that logic would drift, and the failure mode would
 * be intermittent dropped audio, which is the worst thing to debug on a phone.
 *
 * Lifecycle is deliberately split in two:
 * - [begin] is **synchronous**. The caller must be able to attach the capture sink before the
 *   first frame arrives, and loading the model (seconds on first use) must not sit in between.
 * - [open] then loads the model if needed and starts draining the queue.
 *
 * Threading: [onFrames] is called **from the capture thread** and does nothing but convert and
 * enqueue. Everything else runs on [dispatcher], which must be a single-thread dispatcher — a
 * streaming session is serial by contract (D-020), and two threads feeding one stream is a bug.
 */
class LocalAsrSession(
    private val engine: LocalAsrEngine,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onPartialWords: (Int) -> Unit = {},
    /**
     * A **completed unit** — the text up to a pause (D-032). Called on the ASR worker, in order, and
     * including the final unit that [finish] produces from the flush, so a caller that commits per
     * unit commits the whole utterance exactly once and in order.
     */
    private val onUnit: (String) -> Unit = {},
    /**
     * The listener's timings, read at the start of each utterance so a settings change takes effect on
     * the next one instead of requiring a new build. Nothing here is read mid-utterance: a threshold
     * that changes under a running utterance would make its own boundaries inexplicable.
     */
    private val timings: () -> ListenerTimings = { ListenerTimings() },
) {

    private var queue: Channel<FloatArray>? = null
    private var feedJob: Job? = null

    /**
     * Units emitted so far, so [finish] can hand the whole utterance to callers that display it
     * rather than committing it (the settings screen). Mutated only on [dispatcher].
     */
    private val utterance = UtteranceText()

    /** Units emitted this utterance, for the status line and the tests of the commit path. */
    @Volatile
    var unitCount: Int = 0
        private set

    /**
     * The pause detector (D-032). Its counters are surfaced because "no text appeared at my pauses"
     * has two very different causes — a pause shorter than the threshold, or an artifact whose
     * endpoint detection does not fire — and the user is the one holding the evidence.
     */
    private val boundary = UnitBoundary()

    /**
     * The audio-level measurement (D-038). It is what separates "the speaker paused" from "the recogniser
     * is behind": text that stops growing means either, and only the audio can tell them apart.
     */
    private val audioLevel = AudioLevel()

    /**
     * The boundary's counters, published for the UI thread after every frame. The worker owns the
     * real state; this is the read-only copy the status line shows, and it is what turns "no text
     * appeared at my pauses" into either "the model never said endpoint" or "nothing was cut yet".
     */
    @Volatile
    var boundaryState: BoundaryState = boundary.snapshot(audioQuietFor = 0.0, heardAudio = false)
        private set

    /** Frames the queue could not take because the engine is behind. Reported, never ignored. */
    var droppedFrames: Int = 0
        private set

    val isOpen: Boolean get() = queue != null

    /** Opens an utterance: a fresh queue, ready to receive frames before the model is loaded. */
    fun begin() {
        abort()
        queue = Channel(capacity = FRAME_QUEUE_CAPACITY)
        droppedFrames = 0
        utterance.clear()
        unitCount = 0
        boundary.reset()
        audioLevel.reset()
        boundary.configureFallback(timings().fallbackQuietSeconds)
        boundaryState = boundary.snapshot(audioLevel.quietFor, audioLevel.heardSpeech)
    }

    /** The timings this utterance is running with, for the status line and the report. */
    var activeTimings: ListenerTimings = ListenerTimings()
        private set

    /** Loads the model if needed and starts the drain loop. */
    suspend fun open(language: String): LocalAsrEngine.Availability {
        val queue = this.queue ?: error("begin() must be called before open()")
        activeTimings = timings()

        val availability = withContext(dispatcher) { engine.load() }
        if (availability !is LocalAsrEngine.Availability.Loaded) {
            abort()
            return availability
        }

        feedJob = scope.launch(dispatcher) {
            engine.startSession(language)
            for (frames in queue) {
                val text = engine.accept(frames).orEmpty()
                if (text.isNotBlank()) {
                    onPartialWords(text.count { it == ' ' } + 1)
                }
                // The pause boundary (D-032). Two signals: the recogniser's own endpoint detector,
                // and text stability as the fallback — see UnitBoundary for why both exist.
                val quietFor = audioLevel.onFrame(frames)
                if (boundary.onFrame(frames.size, text, engine.isEndpoint(), quietFor)) {
                    val unit = engine.result()
                    engine.resetSegment()
                    boundary.markCut()
                    emitUnit(unit)
                }
                boundaryState = boundary.snapshot(quietFor, audioLevel.heardSpeech)
            }
        }
        return availability
    }

    /**
     * Capture-thread entry point. `trySend` rather than `send`: blocking here drops microphone
     * frames at the source, and a counter the UI can show beats a silent stall.
     */
    fun onFrames(bytes: ByteArray, count: Int) {
        val queue = this.queue ?: return
        if (queue.trySend(AudioFrames.toFloat(bytes, count)).isFailure) droppedFrames++
    }

    /**
     * Closes the queue, waits for the drain loop (so every frame has been fed), flushes, and emits the
     * final unit through [onUnit]. The returned text is the complete utterance, units and tail joined.
     */
    suspend fun finish(): String {
        queue?.close()
        queue = null
        feedJob?.join()
        feedJob = null
        return withContext(dispatcher) {
            // The tail D-020 measured at 39-120 ms: the flush, then whatever the last segment still
            // held. It is emitted like any other unit so there is one commit path, not two.
            emitUnit(engine.finish())
            utterance.toString()
        }
    }

    /** Emits a completed unit. Runs on [dispatcher]: [utterance] is not thread-safe by design. */
    private fun emitUnit(unit: String) {
        if (utterance.append(unit)) {
            unitCount++
            onUnit(unit.trim())
        }
    }

    /** Cancels without a result. The recogniser itself stays resident (D-010). */
    fun abort() {
        queue?.close()
        queue = null
        feedJob?.cancel()
        feedJob = null
        scope.launch(dispatcher) { engine.abort() }
    }

    companion object {
        /**
         * A few seconds of audio even at the recorder's ~1 s read size. Big enough to absorb the
         * first-load pause, small enough that "the engine cannot keep up" surfaces as a number
         * instead of as growing memory.
         */
        private const val FRAME_QUEUE_CAPACITY = 64
    }
}
