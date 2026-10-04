package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.asr.AsrEngine
import io.github.alexeikramm.sovereign.asr.BoundaryState
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.asr.LocalAsrSession
import io.github.alexeikramm.sovereign.cleanup.CleanupCommit
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPipeline
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
    cleanupEngine: CleanupEngine,
    cleanupDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    timings: () -> ListenerTimings,
    cleanupRules: () -> String,
    healingSentencesCount: () -> Int,
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
) {

    private val raw = StringBuilder()
    private val committed = StringBuilder()
    private var cleanupEngaged = false

    /**
     * The recogniser's live partial for the unit in flight. Set by [onPartial], retired by
     * [commitUnit] — the grey/white tail lives exactly as long as its unit has not been committed.
     */
    @Volatile
    private var provisional: String = ""

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
        onCommitted = { commit -> onCleanupCommit(commit) },
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
    val totalActiveLlmMs: Long get() = cleanup.totalActiveLlmMs
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
        }
        cleanupEngaged = cleanupEnabled
        if (cleanupEnabled) cleanup.begin(committed.toString())
    }

    /** Starts the ASR worker. Separate from [begin] so the service can warm the engines in between. */
    fun beginSession() {
        session.begin()
    }

    suspend fun open(language: String): LocalAsrEngine.Availability = session.open(language)

    fun onFrames(bytes: ByteArray, count: Int) = session.onFrames(bytes, count)

    /**
     * Ends the utterance: flush the ASR tail, drain cleanup, apply terminal punctuation. Returns the
     * settled text. The service's `finalizeUtterance` is now just this plus the recorder stop.
     */
    suspend fun finish(): String {
        val totalUtterance = session.finish()
        if (cleanupEngaged) {
            cleanup.drain()
        } else {
            withContext(mainDispatcher) {
                if (committed.isBlank() && totalUtterance.isNotBlank()) {
                    committed.clear()
                    committed.append(totalUtterance)
                    emit()
                }
            }
        }
        withContext(mainDispatcher) { finalizePunctuation() }
        return committed.toString()
    }

    /** Drops the utterance without committing it. */
    fun abort() {
        session.abort()
        clearProvisional()
        if (cleanupEngaged) {
            cleanup.abort()
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
        clearProvisional()
    }

    /**
     * Retires the provisional tail by appending [unit] to the committed text. This is the **one**
     * place provisional is cleared: the grey tail lives for exactly as long as its unit has not yet
     * been committed, so it is replaced by the committed text in the *same* update — never cleared at
     * cut time (which left a blank gap while cleanup ran) and never lingering after a commit.
     */
    private fun commitUnit(unit: String) {
        if (committed.isNotEmpty() && !committed.endsWith(" ") && !unit.startsWith(" ")) {
            committed.append(" ")
        }
        committed.append(unit)
        clearProvisional()
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
        scope.launch(mainDispatcher) { emit() }

        if (cleanupEngaged) {
            // The grey tail stays visible while cleanup runs; `onCleanupCommit` replaces it.
            cleanup.submit(unit)
        } else {
            // No cleanup: the unit becomes committed text at once, so the tail is white immediately.
            scope.launch(mainDispatcher) { commitUnit(unit) }
        }
    }

    /** Runs on the ASR worker: hand the provisional text to the main thread and surface it. */
    private fun onPartial(text: String) {
        onPartialText(text)
        scope.launch(mainDispatcher) {
            provisional = text
            emit()
        }
    }

    /** Runs on the cleanup worker: replace the seam overlap and commit the cleaned unit. */
    private suspend fun onCleanupCommit(commit: CleanupCommit) {
        withContext(mainDispatcher) {
            if (commit.overlapToDelete.isNotEmpty() && committed.endsWith(commit.overlapToDelete)) {
                committed.setLength(committed.length - commit.overlapToDelete.length)
            }
            committed.append(commit.prefix)
            commitUnit(commit.text)
            if (cleanup.lastPass?.cleaned == false) onCleanupUnavailable()
        }
    }

    /** Drops the provisional text carried between the last partial and the unit's cut. */
    private fun clearProvisional() {
        provisional = ""
    }

    private fun finalizePunctuation() {
        val action = UtteranceFinalizer.resolve(committed)
        if (action == UtteranceFinalizer.Action.REPLACE_COMMA) {
            val trimLen = committed.trimEnd().length
            if (trimLen > 0) {
                committed.setLength(trimLen - 1)
                committed.append('.')
                emit()
            }
        } else if (action == UtteranceFinalizer.Action.APPEND_PERIOD) {
            committed.append('.')
            emit()
        }
    }

    private fun emit() = onText(committed.toString(), raw.toString(), provisional)
}
