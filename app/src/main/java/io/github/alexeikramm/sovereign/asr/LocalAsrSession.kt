package io.github.alexeikramm.sovereign.asr

import io.github.alexeikramm.sovereign.engine.MonotonicClock
import java.util.concurrent.atomic.AtomicInteger
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
    private val engine: AsrEngine,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val onPartialText: (String) -> Unit = {},
    /**
     * A **completed unit** — the text up to a pause (D-032). Called on the ASR worker, in order, and
     * including the final unit that [finish] produces from the flush, so a caller that commits per
     * unit commits the whole utterance exactly once and in order.
     */
    private val onUnit: (String) -> Unit = {},
    /** Called whenever recogniser output changes to update UI immediately without polling delay. */
    private val onBoundaryUpdated: (BoundaryState) -> Unit = {},
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
     * Frames enqueued but not yet handed to the engine. The live queue is **unbounded** (D-139): a
     * frame the recogniser has not consumed must wait, never be thrown away, because the raw capture
     * is never re-decoded and a dropped frame is a lost word. This counter only feeds the backlog
     * high-water mark below.
     */
    private val backlog = AtomicInteger(0)

    /**
     * Units emitted so far, so [finish] can hand the whole utterance to callers that display it
     * rather than committing it (the settings screen). Mutated only on [dispatcher].
     */
    private val utterance = UtteranceText()

    /**
     * The text of the unit most recently emitted. The recogniser keeps returning a cut segment's text
     * in [AsrEngine.accept] until the next segment's words arrive; that leftover is not a partial, so
     * the feed loop skips it by comparing against this. Mutated only on [dispatcher].
     */
    private var lastEmittedUnit: String = ""

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
     * Continuous silence duration and speech presence measured by Silero VAD.
     * Replaces naive RMS energy math so speech presence is determined by vocal tract activity.
     */
    private var heardSpeech: Boolean = false
    private var quietSeconds: Double = 0.0

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

    /**
     * The deepest the frame queue got this utterance. With an unbounded queue nothing is dropped, so
     * this is the number that says "the recogniser fell behind" — the one signal that would otherwise
     * be a silent loss. A healthy phone stays in the single digits.
     */
    @Volatile
    var maxBacklogFrames: Int = 0
        private set

    /** Total active ASR computation milliseconds spent processing audio frames in this session. */
    var totalActiveAsrMs: Long = 0L
        private set

    /** Opens an utterance: a fresh queue, ready to receive frames before the model is loaded. */
    fun begin() {
        abort()
        // Unbounded: the capture thread must never block (that would overflow `AudioRecord` and the
        // OS would drop audio), and a frame must never be discarded to bound memory. A 10-minute take
        // is ~38 MB of float frames at worst, and the service caps recording at 10 minutes.
        queue = Channel(capacity = Channel.UNLIMITED)
        backlog.set(0)
        maxBacklogFrames = 0
        droppedFrames = 0
        totalActiveAsrMs = 0L
        utterance.clear()
        unitCount = 0
        heardSpeech = false
        quietSeconds = 0.0
        boundary.reset()
        boundary.configureFallback(timings().fallbackQuietSeconds)
        boundaryState = boundary.snapshot(audioQuietFor = 0.0, heardAudio = false)
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
            engine.startSession(language, "")
            var lastText = ""
            for (frames in queue) {
                backlog.decrementAndGet()
                val acceptStart = MonotonicClock.nowMs()
                val text = engine.accept(frames, frames.size).orEmpty()
                // The recogniser keeps returning the just-cut segment's text until the next segment's
                // words arrive. That leftover is not a new partial — announcing it would re-show text
                // that has already been committed — so it is skipped here, where the cut is known.
                if (text.isNotBlank() && text.trim() != lastEmittedUnit) {
                    onPartialText(text)
                }
                val isSpeech = engine.isSpeechDetected()
                val frameDuration = if (frames.isNotEmpty()) frames.size.toDouble() / AsrModels.SAMPLE_RATE else 0.0
                if (isSpeech) {
                    heardSpeech = true
                    quietSeconds = 0.0
                } else {
                    quietSeconds += frameDuration
                }

                // The pause boundary (D-032). Evaluates Silero VAD endpoint and text stability fallback
                if (boundary.onFrame(frames.size, text, engine.isEndpoint(), quietSeconds)) {
                    val unit = engine.result()
                    engine.resetSegment()
                    boundary.markCut()
                    emitUnit(unit)
                }
                totalActiveAsrMs += (MonotonicClock.nowMs() - acceptStart)

                // The partial is published for the panel's preview only; it is never committed (D-041).
                val newState = boundary.snapshot(quietSeconds, heardSpeech, text)
                boundaryState = newState
                if (text != lastText) {
                    lastText = text
                    onBoundaryUpdated(newState)
                }
            }
        }
        return availability
    }

    /**
     * Capture-thread entry point. It only converts and enqueues, so it can never block the mic. The
     * queue is unbounded, so a frame is only "dropped" if the utterance has already ended.
     */
    fun onFrames(bytes: ByteArray, count: Int) {
        val queue = this.queue ?: return
        backlog.incrementAndGet()
        if (queue.trySend(AudioFrames.toFloat(bytes, count)).isFailure) {
            backlog.decrementAndGet()
            droppedFrames++
        } else {
            val depth = backlog.get()
            if (depth > maxBacklogFrames) maxBacklogFrames = depth
        }
    }

    /**
     * Suspending variant of [onFrames] for **offline replay**: the caller is not a real-time capture
     * thread and can afford backpressure, so a frame waits for queue space instead of being dropped.
     *
     * Live capture must never block (hence `trySend`), but an unpaced replay through [onFrames] would
     * overflow the bounded queue (512 frames ≈ 51 s) the moment the ASR worker falls behind — which is
     * exactly what silently truncated a 33-minute Cleanup Lab run to ~51 s of audio.
     */
    suspend fun onFramesSuspending(bytes: ByteArray, count: Int) {
        if (count <= 0) return
        onFramesSuspending(AudioFrames.toFloat(bytes, count))
    }

    /**
     * Suspending feed for callers that already hold `FloatArray` samples in `[-1, 1)` — the
     * audio-file replay, whose decoder produces floats and would otherwise pay a float→s16→float
     * round trip over the whole file. Same backpressure contract as the byte overload: the frame
     * waits for queue space instead of being dropped.
     */
    suspend fun onFramesSuspending(frame: FloatArray) {
        if (frame.isEmpty()) return
        val queue = this.queue ?: return
        if (queue.trySend(frame).isSuccess) return
        // Full: wait for the worker. A closed queue means the utterance ended mid-feed; count it so the
        // loss is visible rather than silent.
        try {
            queue.send(frame)
        } catch (_: Exception) {
            droppedFrames++
        }
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
            val finishStart = MonotonicClock.nowMs()
            val tail = engine.finish()
            totalActiveAsrMs += (MonotonicClock.nowMs() - finishStart)
            emitUnit(tail)
            utterance.toString()
        }
    }

    /** Emits a completed unit. Runs on [dispatcher]: [utterance] is not thread-safe by design. */
    private fun emitUnit(unit: String) {
        if (utterance.append(unit)) {
            unitCount++
            lastEmittedUnit = unit.trim()
            onUnit(unit.trim())
        }
    }

    /** Cancels without a result. The recogniser itself stays resident (D-010). */
    fun abort() {
        queue?.close()
        queue = null
        backlog.set(0)
        feedJob?.cancel()
        feedJob = null
        scope.launch(dispatcher) { engine.abort() }
    }

    /**
     * [abort], but waits for the feed worker to actually stop before returning. The Cleanup Lab
     * unloads the recogniser immediately after a cancelled run; releasing it while a decode is still
     * in flight on [dispatcher] races the native release.
     */
    suspend fun abortAndJoin() {
        queue?.close()
        queue = null
        backlog.set(0)
        val job = feedJob
        feedJob = null
        if (job != null) {
            job.cancel()
            job.join()
        }
        withContext(dispatcher) { engine.abort() }
    }
}
