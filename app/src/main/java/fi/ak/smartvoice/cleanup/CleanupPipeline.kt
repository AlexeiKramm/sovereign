package fi.ak.smartvoice.cleanup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue between the recogniser and the cursor: D-021's design, made real — cleanup runs per unit,
 * **concurrent with the ASR**, and a unit's text reaches the cursor only after its cleanup pass.
 *
 * Shape and why:
 * - **One worker**, so passes are serial: the runtime has one model handle and D-010 rule 3 allows one
 *   decode at a time. Concurrency here is between the *stages* (ASR on the CPU, cleanup on the NPU),
 *   not within a stage — which is what D-017 named as the case where overlap actually pays.
 * - **Submitting never blocks.** The ASR worker calls [submit] between frames, so it must not wait for
 *   an LLM; the channel is unbounded and [queued] is published instead, because a backlog is a number to
 *   show (D-021's gate: cleanup must sustain faster than realtime) rather than a stall to introduce.
 * - **A crashed pass fails closed.** Anything thrown while cleaning becomes a failed pass, so the unit's
 *   own text is committed and the utterance survives; a dead worker would silently stop committing text.
 */
class CleanupPipeline(
    private val engine: CleanupEngine,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    /** Read per unit, so a settings change applies without a rebuild. */
    private val rules: () -> CleanupPrompt.Variant,
    /** Called on [dispatcher], in order, once per unit. */
    private val onCommitted: (CleanupCommit) -> Unit,
) {

    private var queue: Channel<String>? = null
    private var worker: Job? = null

    private val pending = AtomicInteger(0)

    /** Units waiting for their pass. Published because "the LLM is behind" must be visible. */
    val queued: Int get() = pending.get()

    /** The carried context and counters for the utterance in progress. */
    val state = UtteranceCleanupState()

    /** The last finished pass, for the status line's timings. */
    @Volatile
    var lastPass: CleanupPass? = null
        private set

    /** Units submitted this utterance, whether or not they have been cleaned yet. */
    @Volatile
    var submitted: Int = 0
        private set

    /**
     * Starts an utterance. [initialTail] is any text already committed for this utterance — cleanup can
     * engage *mid-utterance*, when the model finishes loading, so the tail must start from what the user
     * already has on screen rather than from nothing (D-037).
     */
    fun begin(initialTail: String = "") {
        abort()
        state.reset()
        state.seed(initialTail)
        lastPass = null
        submitted = 0
        val channel = Channel<String>(Channel.UNLIMITED)
        queue = channel
        worker = scope.launch(dispatcher) {
            for (unit in channel) {
                val pass = runCatching { engine.clean(unit, state.carriedTail, rules()) }
                    .getOrElse { error ->
                        CleanupPass.failed(
                            unit,
                            "cleanup crashed — ${error.message ?: error::class.java.simpleName}",
                        )
                    }
                lastPass = pass
                // The tail is read *before* the pass above and updated here, so a unit is always cleaned
                // against the text that preceded it and never against itself.
                onCommitted(state.onPass(pass))
                pending.decrementAndGet()
            }
        }
    }

    /**
     * Hands one unit to the queue. Called from the ASR thread, between frames: `trySend` and a counter,
     * nothing that can wait for the model.
     */
    fun submit(unit: String) {
        val channel = queue ?: return
        if (unit.isBlank()) return
        if (channel.trySend(unit).isSuccess) {
            pending.incrementAndGet()
            submitted++
        }
    }

    /**
     * Closes the queue and waits for every submitted unit to be cleaned and committed — the end of an
     * utterance, where the cost of the last unit's pass is paid (D-032: ~0.4-1.3 s for a few seconds of
     * speech, which is the accepted price of never showing the model a fragment).
     */
    suspend fun drain() {
        queue?.close()
        queue = null
        worker?.join()
        worker = null
        pending.set(0)
    }

    /** Drops the queue without cleaning it. Nothing is committed, which is what cancel means. */
    fun abort() {
        queue?.close()
        queue = null
        worker?.cancel()
        worker = null
        pending.set(0)
        submitted = 0
        lastPass = null
        state.reset()
    }
}
