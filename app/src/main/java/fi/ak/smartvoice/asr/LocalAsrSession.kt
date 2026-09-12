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
) {

    private var queue: Channel<FloatArray>? = null
    private var feedJob: Job? = null

    /** Frames the queue could not take because the engine is behind. Reported, never ignored. */
    var droppedFrames: Int = 0
        private set

    val isOpen: Boolean get() = queue != null

    /** Opens an utterance: a fresh queue, ready to receive frames before the model is loaded. */
    fun begin() {
        abort()
        queue = Channel(capacity = FRAME_QUEUE_CAPACITY)
        droppedFrames = 0
    }

    /** Loads the model if needed and starts the drain loop. */
    suspend fun open(language: String): LocalAsrEngine.Availability {
        val queue = this.queue ?: error("begin() must be called before open()")

        val availability = withContext(dispatcher) { engine.load() }
        if (availability !is LocalAsrEngine.Availability.Loaded) {
            abort()
            return availability
        }

        feedJob = scope.launch(dispatcher) {
            engine.startSession(language)
            for (frames in queue) {
                val text = engine.accept(frames)
                if (text != null) {
                    onPartialWords(text.count { it == ' ' } + if (text.isBlank()) 0 else 1)
                }
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
     * Closes the queue, waits for the drain loop (so every frame has been fed), then flushes.
     * The returned text is the complete utterance: results are cumulative per stream.
     */
    suspend fun finish(): String {
        queue?.close()
        queue = null
        feedJob?.join()
        feedJob = null
        return withContext(dispatcher) { engine.finish() }
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
