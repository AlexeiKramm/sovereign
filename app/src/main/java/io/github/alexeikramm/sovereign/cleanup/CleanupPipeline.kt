package io.github.alexeikramm.sovereign.cleanup

import io.github.alexeikramm.sovereign.engine.MonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The queue between the recogniser and the cursor: D-021's design, made real — cleanup runs per unit,
 * **concurrent with the ASR**, and a unit's text reaches the cursor only after its cleanup pass.
 *
 * Shape and why:
 * - **One worker**, so passes are serial: the runtime has one model handle and D-010 rule 3 allows one
 *   decode at a time. Concurrency here is between the *stages* (ASR on the CPU, cleanup on the NPU),
 *   not within a stage — which is what D-017 named as the case where overlap actually pays.
 * - **Submitting never blocks.** The ASR worker calls [submit] between frames, so it must not wait for
 *   an LLM; the channel is unbounded (D-021's gate: cleanup should sustain faster than realtime, but a
 *   backlog must never stall the recogniser).
 * - **A crashed pass fails closed.** Anything thrown while cleaning becomes a failed pass, so the unit's
 *   own text is committed and the utterance survives; a dead worker would silently stop committing text.
 */
data class CleanupUnit(
    val text: String,
    val seam: SeamSplit? = null,
)

class CleanupPipeline(
    private val engine: CleanupEngine,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    /** Read per unit, so a settings change applies without a rebuild. */
    private val rules: () -> CleanupPrompt.Variant,
    /** Number of sentences to rewrite across pause boundaries for seam healing. */
    private val healingSentencesCount: () -> Int = { 0 },
    /** Called on [dispatcher], in order, once per unit. */
    private val onCommitted: suspend (CleanupCommit) -> Unit,
) {

    private var queue: Channel<CleanupUnit>? = null
    private var worker: Job? = null

    /** The carried context and counters for the utterance in progress. */
    val state = UtteranceCleanupState()

    /** Total active inference milliseconds across all units in this utterance. */
    val totalActiveLlmMs: Long get() = state.totalActiveCleanMs

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
        engine.startSession(rules())
        val channel = Channel<CleanupUnit>(Channel.UNLIMITED)
        queue = channel
        worker = scope.launch(dispatcher) {
            for (request in channel) {
                val unit = request.text
                val healingCount = healingSentencesCount()
                val seam = request.seam ?: if (healingCount > 0 && state.carriedTail.isNotBlank()) {
                    SeamHealer.split(state.carriedTail, healingCount)
                } else null
                val hasOverlap = seam != null && seam.strippedOverlap.isNotBlank()
                val textToClean = if (hasOverlap) "${seam.strippedOverlap} ${unit.trim()}" else unit
                val contextForClean = if (hasOverlap) {
                    seam.anchorContext.takeIf { it.isNotBlank() }
                } else {
                    state.carriedTail.takeIf { it.isNotBlank() }
                }

                val passStart = MonotonicClock.nowMs()
                val pass = runCatching { engine.clean(textToClean, contextForClean, rules()) }
                    .getOrElse { error ->
                        CleanupPass.failed(
                            unit,
                            "cleanup crashed — ${error.message ?: error::class.java.simpleName}",
                        )
                    }
                val passMs = MonotonicClock.nowMs() - passStart
                val passWithActive = if (pass.activeMs <= 0L) pass.copy(activeMs = passMs) else pass

                // Fail-closed guard: if seam healing was used, but the output dropped text abnormally
                // (less than half of the overlap length), fail closed to avoid erasing user text
                val finalPass = if (hasOverlap && passWithActive.cleaned && passWithActive.text.length < seam.strippedOverlap.length / 2) {
                    CleanupPass.failed(unit, "cleanup output suspiciously short — failing closed", activeMs = passWithActive.activeMs)
                } else {
                    passWithActive
                }

                lastPass = finalPass
                val effectiveSeam = if (finalPass.cleaned) seam else null
                onCommitted(state.onPass(finalPass, effectiveSeam))

                // Proactive: rebuild the conversation once the cache is nearly full, here on the
                // cleanup worker while the ASR keeps producing the next segment. `startSession`
                // closes the old conversation first (never two caches at once) and prefills the new
                // system preface, so the next pass finds a warm, empty conversation — the rebuild
                // cost is paid off the critical path, not when a segment is waiting.
                if (ConversationRebuildPolicy.shouldRebuild(engine.tokenCount, engine.contextTokens)) {
                    engine.startSession(rules())
                }
            }
        }
    }

    /**
     * Hands one unit to the queue. Called from the ASR thread, between frames: `trySend` and a counter,
     * nothing that can wait for the model.
     *
     * @param unit The new text segment from ASR.
     * @param seam Optional seam split with overlap to heal across the pause boundary.
     */
    fun submit(unit: String, seam: SeamSplit? = null) {
        val channel = queue ?: return
        if (unit.isBlank()) return
        if (channel.trySend(CleanupUnit(unit, seam)).isSuccess) {
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
        engine.closeSession()
    }

    /** Drops the queue without cleaning it. Nothing is committed, which is what cancel means. */
    fun abort() {
        val wasActive = queue != null || worker != null
        queue?.close()
        queue = null
        worker?.cancel()
        worker = null
        submitted = 0
        lastPass = null
        if (wasActive) {
            engine.closeSession()
        }
        state.reset()
    }
}
