package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.dictation.SessionArbiter

/**
 * Capture lifecycle events the server engine subscribes to for one utterance.
 *
 * The **host** owns `WARMING` and `LISTENING` (it warms the engines and opens the recogniser); the
 * **engine** owns `PROCESSING` and `IDLE`, which bracket `finish()`. So a host must not emit those
 * two itself.
 */
interface CaptureListener {
    /** A pipeline state the capture moved through. */
    fun onState(state: PipelineState)

    /** Live partial of the unit in flight; **non-cumulative**. Blank for offline models. */
    fun onProvisional(text: String)

    /**
     * A full **snapshot** of committed text (PROTOCOL.md §6). It may repeat or rewrite the previous
     * snapshot (seam healing), so the receiver replaces — the engine never appends.
     */
    fun onCommit(snapshot: String, perf: CommitPerf)
}

/** Outcome of [CaptureHost.begin]. */
sealed interface StartOutcome {
    /** The arbiter was claimed and capture is starting (warming asynchronously). */
    data object Started : StartOutcome

    /** Another capture path already holds the process-wide arbiter (D-106). */
    data class Busy(val holder: SessionArbiter.Owner) : StartOutcome

    /** The microphone or an engine could not come up, so no capture started. */
    data class Failed(val reason: String) : StartOutcome
}

/** The settled text of a finished utterance. */
data class CaptureResult(val deliveryText: String, val rawText: String)

/**
 * The capture operations the server engine drives, expressed as a port so the engine stays pure-JVM
 * and unit-testable (Chunk 1a) while the real [io.github.alexeikramm.sovereign.dictation.DictationSession]
 * backs it in production (Chunk 1b).
 *
 * Implementations **must not** construct an engine, recorder or dispatcher of their own: there is one
 * shared engine set and one process-wide [SessionArbiter] (D-106).
 */
interface CaptureHost {

    /**
     * Claims the arbiter and starts an utterance, warming the engines asynchronously. Reports
     * readiness through [listener] (`WARMING` → `LISTENING`) **after this call returns** — never
     * re-entrantly, so the engine can emit `START_ACK` before any state frame.
     *
     * Non-blocking: the caller answers `START_ACK` immediately and streams state afterwards.
     */
    fun begin(listener: CaptureListener): StartOutcome

    /** Ends the utterance (stop recorder, drain ASR + cleanup) and returns the final text. */
    suspend fun finish(): CaptureResult

    /** Drops the utterance without delivering it. Idempotent. */
    fun cancel()
}
