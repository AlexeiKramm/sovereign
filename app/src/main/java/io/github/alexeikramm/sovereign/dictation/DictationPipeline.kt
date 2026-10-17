package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.asr.AsrEngine
import io.github.alexeikramm.sovereign.asr.BoundaryState
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.asr.LocalAsrSession
import io.github.alexeikramm.sovereign.cleanup.CleanupCommit
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPassTelemetry
import io.github.alexeikramm.sovereign.cleanup.CleanupPipeline
import io.github.alexeikramm.sovereign.cleanup.ConversationRebuildPolicy
import io.github.alexeikramm.sovereign.data.CleanupMode
import io.github.alexeikramm.sovereign.engine.MonotonicClock
import io.github.alexeikramm.sovereign.text.MarkdownLists
import io.github.alexeikramm.sovereign.text.PanelText
import io.github.alexeikramm.sovereign.text.UtteranceFinalizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One implementation of "speech in, settled text out": it owns the [LocalAsrSession] and the
 * [CleanupPipeline] and the routing between them, so the floating pill and the benchmark harness run
 * the same code instead of two copies that drift (D-080 §3).
 *
 * What it owns:
 * - the utterance text state — [committedText] (what will be delivered) and [rawText] (the ASR words
 *   before cleanup) — and every mutation of it, including the seam-healing overlap replacement and
 *   the terminal-punctuation pass;
 * - the cleanup-on/cleanup-off routing: with cleanup off, units go straight to [committedText];
 * - lifecycle: begin/open/finish/abort.
 *
 * What it deliberately does **not** own: Compose/UI state and the silence monitor. The service
 * becomes an adapter that mirrors [onText] into `previewText`/`rawText` and reads [boundaryState] to
 * decide when to stop. The engines' warm-up and residency stay in the service too.
 *
 * Threading mirrors the previous in-service code exactly: [onUnit] runs on the ASR worker and only
 * touches [committedText] through [mainDispatcher]; the cleanup commit runs on the cleanup worker and
 * does the same. Callers of [begin]/[finish]/[abort]/[replaceCommitted]/[reset] are already on the
 * main dispatcher (the service's scope).
 */
class DictationPipeline(
    asrEngine: AsrEngine,
    asrDispatcher: CoroutineDispatcher,
    private val cleanupEngine: CleanupEngine,
    private val cleanupDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    timings: () -> ListenerTimings,
    private val cleanupRules: () -> String,
    healingSentencesCount: () -> Int,
    /**
     * Rebuild the cleanup conversation after this many segments, even when the cache is not full
     * (0 = no turn limit). Read per unit; the pill leaves it at 0, the Cleanup Lab varies it.
     */
    maxTurns: () -> Int = { ConversationRebuildPolicy.NO_TURN_LIMIT },
    private val cleanupMode: () -> CleanupMode = { CleanupMode.PER_SEGMENT },
    /**
     * Called on [mainDispatcher] whenever the utterance's text changes — committed, raw, **or
     * provisional**. Provisional is part of the same contract on purpose: the service mirrors these
     * three strings into its Compose state, so a unit committing has to clear the provisional tail
     * in the same update, or the just-committed words linger as a grey tail until the next partial.
     */
    private val onText: (committed: String, raw: String, provisional: String) -> Unit,
    /** A cleanup pass failed; the service surfaces its advisory message. */
    private val onCleanupUnavailable: () -> Unit = {},
    /** ASR partial text, as it is recognised — not committed. Measurement hook for the harness. */
    private val onPartialText: (String) -> Unit = {},
    /** A unit was cut from the recogniser (the endpoint fired). Measurement hook for the harness. */
    private val onUnitCut: (String) -> Unit = {},
    /** A unit's settled text became visible. Measurement hook for the harness. */
    private val onUnitCommitted: (String) -> Unit = {},
    /** Per-pass conversation telemetry, in order. Measurement hook for the Cleanup Lab. */
    private val onCleanupPass: (CleanupPassTelemetry) -> Unit = {},
    /**
     * Grace before the cleanup session's preface is prefilled, so a caller's start-of-utterance UI
     * motion is not competing with the GPU-heavy prefill. The production session sets this; the
     * offline replay harnesses leave it at 0 (see `CleanupPipeline`).
     */
    private val cleanupSessionStartGraceMs: Long = 0L,
) {

    private val raw = StringBuilder()
    private val committed = StringBuilder()
    private var cleanupEngaged = false
    private var activeCleanupMode = CleanupMode.PER_SEGMENT
    private var utteranceLlmMs = 0L

    private val textLock = Any()
    private val pendingClean = ArrayDeque<String>()
    private var liveProvisional: String = ""

    /**
     * The unified uncommitted tail: cut units currently in-flight in cleanup ([pendingClean])
     * followed by any live streaming partial from the recogniser ([liveProvisional]).
     *
     * Mutated under [textLock]; [provisional] holds the published string for [onText] and
     * [provisionalText].
     */
    @Volatile
    private var provisional: String = ""

    /** True while one or more cut units are waiting for or undergoing LLM cleanup. */
    val hasPendingClean: Boolean
        get() = synchronized(textLock) { pendingClean.isNotEmpty() }

    private val session = LocalAsrSession(
        engine = asrEngine,
        dispatcher = asrDispatcher,
        scope = scope,
        onUnit = ::onUnit,
        timings = timings,
        onPartialText = ::onPartial,
    )

    private val cleanup = CleanupPipeline(
        engine = cleanupEngine,
        scope = scope,
        dispatcher = cleanupDispatcher,
        rules = cleanupRules,
        healingSentencesCount = healingSentencesCount,
        maxTurns = maxTurns,
        onCommitted = { commit -> onCleanupCommit(commit) },
        onPass = onCleanupPass,
        sessionStartGraceMs = cleanupSessionStartGraceMs,
    )

    /** The settled text — what cleanup has produced, or the raw words when cleanup is off. */
    val committedText: String get() = committed.toString()

    /** The ASR words for the whole utterance, before cleanup. */
    val rawText: String get() = raw.toString()

    /**
     * The provisional text of the unit currently being spoken — recognised, not yet committed. Empty
     * between units. The card shows it live, coloured by whether it is pending cleanup (grey) or
     * already final (white); [committedText] replaces it as each unit commits. **Blank for offline
     * models**, which emit nothing until a segment ends.
     */
    val provisionalText: String get() = provisional

    /** True when this utterance is routing through cleanup. */
    val isCleanupEngaged: Boolean get() = cleanupEngaged

    /** The text to deliver: the settled text, or the raw words if nothing settled. */
    val deliveryText: String get() = committed.toString().ifBlank { raw.toString() }

    val totalActiveAsrMs: Long get() = session.totalActiveAsrMs
    val totalActiveLlmMs: Long get() = if (activeCleanupMode == CleanupMode.PER_UTTERANCE) utteranceLlmMs else cleanup.totalActiveLlmMs
    val boundaryState: BoundaryState get() = session.boundaryState

    /**
     * Opens an utterance. [fresh] clears any text left from a previous one; [cleanupEnabled] decides
     * the routing and seeds cleanup with whatever text is already committed (so a resumed utterance
     * keeps its context — D-037).
     */
    fun begin(fresh: Boolean, cleanupEnabled: Boolean) {
        if (fresh) {
            raw.clear()
            committed.clear()
            clearProvisional()
            utteranceLlmMs = 0L
        }
        cleanupEngaged = cleanupEnabled
        activeCleanupMode = if (cleanupEnabled) cleanupMode() else CleanupMode.PER_SEGMENT
        if (cleanupEnabled && activeCleanupMode == CleanupMode.PER_SEGMENT) {
            cleanup.begin(committed.toString())
        }
    }

    /** Starts the ASR worker. Separate from [begin] so the service can warm the engines in between. */
    fun beginSession() {
        session.begin()
    }

    suspend fun open(language: String): LocalAsrEngine.Availability = session.open(language)

    fun onFrames(bytes: ByteArray, count: Int) = session.onFrames(bytes, count)

    /**
     * Offline-replay capture entry point: waits while the recogniser is behind instead of dropping the
     * frame. Used by the Cleanup Lab; the pill keeps the non-blocking [onFrames].
     */
    suspend fun onFramesSuspending(bytes: ByteArray, count: Int) = session.onFramesSuspending(bytes, count)

    /**
     * Float-sample replay entry point for the audio-file transcribe path, which decodes to
     * `FloatArray` directly. Same backpressure as the byte overload.
     */
    suspend fun onFramesSuspending(frame: FloatArray) = session.onFramesSuspending(frame)

    /** Frames the ASR queue dropped this utterance; 0 for a backpressured replay. */
    val droppedFrames: Int get() = session.droppedFrames

    /** Deepest the ASR frame queue got this utterance; 0 when the recogniser kept up (D-139). */
    val asrBacklogHighWater: Int get() = session.maxBacklogFrames

    /**
     * Ends the utterance: flush the ASR tail, drain cleanup, apply terminal punctuation. Returns the
     * settled text. The service's `finalizeUtterance` is now just this plus the recorder stop.
     */
    suspend fun finish(): String {
        val totalUtterance = session.finish()
        if (cleanupEngaged && activeCleanupMode == CleanupMode.PER_SEGMENT) {
            cleanup.drain()
        } else if (cleanupEngaged && activeCleanupMode == CleanupMode.PER_UTTERANCE) {
            val textToClean = synchronized(raw) { raw.toString().trim() }
                .ifBlank { totalUtterance.trim() }
            if (textToClean.isNotBlank()) {
                val passStart = MonotonicClock.nowMs()
                val pass = runCatching {
                    withContext(cleanupDispatcher) {
                        cleanupEngine.clean(unit = textToClean, rules = cleanupRules())
                    }
                }.getOrElse { error ->
                    CleanupPass.failed(
                        textToClean,
                        "cleanup crashed — ${error.message ?: error::class.java.simpleName}",
                    )
                }
                val passMs = (MonotonicClock.nowMs() - passStart).coerceAtLeast(1L)
                val passWithActive = if (pass.activeMs <= 0L) pass.copy(activeMs = passMs) else pass
                utteranceLlmMs = passWithActive.activeMs

                withContext(mainDispatcher) {
                    if (passWithActive.cleaned && passWithActive.text.isNotBlank()) {
                        committed.clear()
                        committed.append(passWithActive.text.trim())
                    } else {
                        committed.clear()
                        committed.append(textToClean)
                        onCleanupUnavailable()
                    }
                }

                onCleanupPass(
                    CleanupPassTelemetry(
                        index = 1,
                        unit = textToClean,
                        cleanedText = if (passWithActive.cleaned) passWithActive.text else textToClean,
                        cleaned = passWithActive.cleaned,
                        tokensBefore = 0,
                        tokensAfter = cleanupEngine.tokenCount,
                        turnsInConversation = 0,
                        rebuiltAfter = false,
                        rebuildReason = null,
                        activeMs = passWithActive.activeMs,
                        ttftMs = passWithActive.ttftMs,
                        decodeTimeMs = passWithActive.decodeTimeMs,
                        promptTokens = passWithActive.promptTokens,
                        generatedTokens = passWithActive.generatedTokens,
                        note = passWithActive.note,
                    ),
                )
                withContext(cleanupDispatcher) {
                    cleanupEngine.closeSession()
                }
            }
        } else {
            withContext(mainDispatcher) {
                if (committed.isBlank() && totalUtterance.isNotBlank()) {
                    committed.clear()
                    committed.append(totalUtterance)
                }
            }
        }
        withContext(mainDispatcher) {
            finalizePunctuation()
            // One-shot, end-of-capture Markdown hygiene: a list must not swallow following prose.
            // Deliberately here and not per segment, so a pause that lands mid-list-item is never cut
            // apart. The preview, delivery and history all read `committed`, so they stay consistent.
            val normalized = MarkdownLists.normalize(committed.toString())
            if (normalized != committed.toString()) {
                committed.setLength(0)
                committed.append(normalized)
            }
            clearProvisional()
            emit()
            if (activeCleanupMode == CleanupMode.PER_UTTERANCE) {
                onUnitCommitted(committed.toString())
            }
        }
        return committed.toString()
    }

    /** Drops the utterance without committing it. */
    fun abort() {
        session.abort()
        clearProvisional()
        if (cleanupEngaged) {
            if (activeCleanupMode == CleanupMode.PER_SEGMENT) {
                cleanup.abort()
            }
            cleanupEngaged = false
        }
    }

    /**
     * [abort], but waits for the ASR and cleanup workers to stop before returning. For callers that
     * release the engines next (the Cleanup Lab), where an in-flight decode must not race the unload.
     */
    suspend fun abortAndJoin() {
        session.abortAndJoin()
        clearProvisional()
        if (cleanupEngaged) {
            if (activeCleanupMode == CleanupMode.PER_SEGMENT) {
                cleanup.abortAndJoin()
            }
            cleanupEngaged = false
        }
    }

    /** Replaces the settled text wholesale (the pill's "reprocess full text" action). */
    fun replaceCommitted(text: String) {
        committed.clear()
        committed.append(text)
        emit()
    }

    /** Clears the utterance's text and routing flags without touching the engines. */
    fun reset() {
        raw.clear()
        committed.clear()
        cleanupEngaged = false
        utteranceLlmMs = 0L
        clearProvisional()
    }

    private fun updateProvisionalLocked(): String {
        val pendingJoined = PanelText.joinUnits(pendingClean.filter { it.isNotBlank() })
        val live = liveProvisional.trim()
        val combined = when {
            pendingJoined.isNotBlank() && live.isNotBlank() ->
                pendingJoined + PanelText.joinSeparator(pendingJoined, live) + live
            pendingJoined.isNotBlank() -> pendingJoined
            live.isNotBlank() -> live
            else -> ""
        }
        provisional = combined
        return combined
    }

    /**
     * Retires the provisional tail by appending [unit] to the committed text. Used when cleanup is
     * disengaged: the unit becomes final immediately on cut.
     */
    private fun commitUnit(unit: String) {
        // The separator comes from the shared rule, not a hardcoded space: a unit that starts with
        // punctuation attaches to the previous word instead of being pushed away from it.
        committed.append(PanelText.joinSeparator(committed.toString(), unit))
        committed.append(unit)
        synchronized(textLock) {
            pendingClean.clear()
            liveProvisional = ""
            updateProvisionalLocked()
        }
        emit()
        onUnitCommitted(committed.toString())
    }

    /** Runs on the ASR worker: accumulate raw, then route the unit to cleanup or straight to text. */
    private fun onUnit(unit: String) {
        if (unit.isBlank()) return
        onUnitCut(unit)
        synchronized(raw) {
            if (raw.isNotEmpty() && !raw.endsWith(" ")) raw.append(" ")
            raw.append(unit)
        }

        if (cleanupEngaged && activeCleanupMode == CleanupMode.PER_SEGMENT) {
            // Synchronously stage the unit into pendingClean and clear liveProvisional
            // BEFORE submitting to cleanup worker so the in-flight unit stays visible in gray
            // and cannot be lost to an immediate onCleanupCommit or subsequent partial.
            synchronized(textLock) {
                pendingClean.addLast(unit.trim())
                liveProvisional = ""
                updateProvisionalLocked()
            }
            scope.launch(mainDispatcher) { emit() }
            cleanup.submit(unit)
        } else if (cleanupEngaged && activeCleanupMode == CleanupMode.PER_UTTERANCE) {
            // Keep in provisional (gray) until the whole utterance finishes and is cleaned in one pass.
            synchronized(textLock) {
                pendingClean.addLast(unit.trim())
                liveProvisional = ""
                updateProvisionalLocked()
            }
            scope.launch(mainDispatcher) { emit() }
        } else {
            // No cleanup: the unit becomes committed text at once, so the tail is white immediately.
            scope.launch(mainDispatcher) { commitUnit(unit) }
        }
    }

    /** Runs on the ASR worker: hand the provisional text to the main thread and surface it. */
    private fun onPartial(text: String) {
        onPartialText(text)
        synchronized(textLock) {
            liveProvisional = text
            updateProvisionalLocked()
        }
        scope.launch(mainDispatcher) { emit() }
    }

    /** Runs on the cleanup worker: replace the seam overlap and commit the cleaned unit. */
    private suspend fun onCleanupCommit(commit: CleanupCommit) {
        withContext(mainDispatcher) {
            if (commit.overlapToDelete.isNotEmpty() && committed.endsWith(commit.overlapToDelete)) {
                committed.setLength(committed.length - commit.overlapToDelete.length)
            }
            committed.append(commit.prefix)
            committed.append(commit.text)
            synchronized(textLock) {
                pendingClean.removeFirstOrNull()
                updateProvisionalLocked()
            }
            emit()
            onUnitCommitted(committed.toString())
            if (cleanup.lastPass?.cleaned == false) onCleanupUnavailable()
        }
    }

    /** Drops any pending cleanup units and live provisional text. */
    private fun clearProvisional() {
        synchronized(textLock) {
            pendingClean.clear()
            liveProvisional = ""
            provisional = ""
        }
    }

    private fun finalizePunctuation() {
        val action = UtteranceFinalizer.resolve(committed)
        if (action == UtteranceFinalizer.Action.REPLACE_COMMA) {
            val trimLen = committed.trimEnd().length
            if (trimLen > 0) {
                committed.setLength(trimLen - 1)
                committed.append('.')
            }
        } else if (action == UtteranceFinalizer.Action.APPEND_PERIOD) {
            committed.append('.')
        }
    }

    private fun emit() = onText(committed.toString(), raw.toString(), provisional)
}
