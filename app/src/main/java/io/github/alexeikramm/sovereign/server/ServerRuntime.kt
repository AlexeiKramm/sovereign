package io.github.alexeikramm.sovereign.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lifecycle of the server foreground service, as the phone UI sees it. */
enum class ServerLifecycle { STOPPED, STARTING, RUNNING }

/**
 * The current desktop utterance's stage, for the dashboard's badge row. `TRANSCRIBING` and
 * `CLEANING` are the UI's finer-grained reading of the protocol's `LISTENING`/`PROCESSING`
 * (PROTOCOL.md §7): the wire carries the coarser states, the dashboard splits them.
 *
 * `CLEANING` is deliberately **not** a phase like the others: the cleanup LLM runs on already-cut
 * units while the recogniser keeps streaming the next one, so cleaning overlaps transcribing rather
 * than following it. It is modelled by [ServerSnapshot.cleaning], and the row lights both at once.
 */
enum class ServerPipeline { IDLE, WARMING, LISTENING, TRANSCRIBING, CLEANING, ERROR }

/** One desktop currently connected to the phone. */
data class ServerClient(
    val deviceId: String,
    val name: String,
    val address: String?,
)

/**
 * Everything the Server screen renders: whether the server is up, who is connected, what the current
 * utterance is doing, and the last latencies the phone actually measured.
 *
 * The latency fields are sourced from real callbacks — `lastAsrMs`/`lastCleanupMs` come from the
 * per-commit `CommitPerf` the shared `DictationSession` produces, `committedWords` from the current
 * snapshot. `lastHeartbeatRealtimeMs` is when the last `PING` arrived (monotonic, `SystemClock`
 * domain): the phone cannot measure the client's round trip because `PROTOCOL.md §6` makes
 * `PING`/`PONG` one-directional (client → server → client), so the dashboard reports heartbeat age
 * (liveness) instead of a server-side RTT — that number is the desktop's to measure (Chunk 3).
 */
data class ServerSnapshot(
    val lifecycle: ServerLifecycle = ServerLifecycle.STOPPED,
    /** Last start failure, retained until the next start clears it; null on a clean stop. */
    val error: String? = null,
    /** `wss://<phone-lan-ip>:<port>/sovereign`, for the manual host:port fallback. */
    val endpoint: String? = null,
    val startedAtRealtimeMs: Long? = null,
    /** 0 means the session never auto-stops (manual). */
    val timeoutMinutes: Int? = null,
    /**
     * The absolute auto-stop deadline in the `SystemClock.elapsedRealtime()` domain, or null for a
     * manual session (D-136). The one value the Home row counts down from; an extension moves it.
     */
    val endsAtRealtimeMs: Long? = null,
    val clients: List<ServerClient> = emptyList(),
    /**
     * The capture phase; exclusive. `CLEANING` is never a phase — an LLM pass overlaps ASR, so it is
     * the independent [cleaning] flag and both can be active at once.
     */
    val phase: ServerPipeline = ServerPipeline.IDLE,
    /** An LLM cleanup pass is in flight; independent of [phase]. */
    val cleaning: Boolean = false,
    val lastAsrMs: Long? = null,
    val lastCleanupMs: Long? = null,
    val committedWords: Int = 0,
    val lastHeartbeatRealtimeMs: Long? = null,
) {
    /**
     * The stages to light in the dashboard's row. `CLEANING` may co-exist with `LISTENING` or
     * `TRANSCRIBING`; `IDLE` lights only when nothing else is active.
     */
    val stages: Set<ServerPipeline>
        get() = buildSet {
            if (cleaning) add(ServerPipeline.CLEANING)
            when (phase) {
                ServerPipeline.WARMING -> add(ServerPipeline.WARMING)
                ServerPipeline.LISTENING -> add(ServerPipeline.LISTENING)
                ServerPipeline.TRANSCRIBING -> add(ServerPipeline.TRANSCRIBING)
                ServerPipeline.ERROR -> add(ServerPipeline.ERROR)
                ServerPipeline.IDLE, ServerPipeline.CLEANING ->
                    if (!cleaning) add(ServerPipeline.IDLE)
            }
        }
}

/**
 * The Chunk-1 deliverable the Chunk-2 dashboard was specified to depend on: a process-wide,
 * observable view of the running server. The service and its transport push into it; the Server
 * screen and Home card collect from it.
 *
 * It holds **no Android dependency** so its transitions are unit-tested on the JVM, and it is
 * lock-free (`MutableStateFlow`) because publishers run on the main dispatcher while the UI collects.
 * A fresh instance is used in tests; production shares [ServerRuntime.shared].
 */
class ServerRuntime {

    private val _state = MutableStateFlow(ServerSnapshot())
    val state: StateFlow<ServerSnapshot> = _state.asStateFlow()

    fun snapshot(): ServerSnapshot = _state.value

    /** A start has been requested. Resets everything, including any previous failure. */
    fun onStarting() {
        _state.value = ServerSnapshot(lifecycle = ServerLifecycle.STARTING)
    }

    /** The WebSocket endpoint is up. */
    fun onRunning(endpoint: String?, startedAtRealtimeMs: Long, timeoutMinutes: Int) {
        _state.value = ServerSnapshot(
            lifecycle = ServerLifecycle.RUNNING,
            endpoint = endpoint,
            startedAtRealtimeMs = startedAtRealtimeMs,
            timeoutMinutes = timeoutMinutes,
            endsAtRealtimeMs = ServerSessionTiming.deadlineFrom(startedAtRealtimeMs, timeoutMinutes),
        )
    }

    /**
     * The auto-stop deadline moved (a `＋ 30 min` from the notification). Null returns the session to
     * "manual" (never auto-stops).
     */
    fun onDeadlineMoved(endsAtRealtimeMs: Long?) {
        _state.update { it.copy(endsAtRealtimeMs = endsAtRealtimeMs) }
    }

    /**
     * A start attempt failed. The service then tears itself down, so [onStopped] follows; the error
     * is retained so the dashboard can still explain why the server is not up.
     */
    fun onFailed(message: String) {
        _state.update { it.copy(lifecycle = ServerLifecycle.STOPPED, error = message) }
    }

    /** The service is gone. Clears the transient session view but keeps the last error, if any. */
    fun onStopped() {
        _state.update { ServerSnapshot(lifecycle = ServerLifecycle.STOPPED, error = it.error) }
    }

    /** A desktop authenticated (`WELCOME`). Reconnecting replaces that device's previous entry. */
    fun onClientConnected(deviceId: String, name: String, address: String?) {
        _state.update { snapshot ->
            snapshot.copy(
                clients = snapshot.clients.filterNot { it.deviceId == deviceId } +
                    ServerClient(deviceId, name, address),
            )
        }
    }

    fun onClientDisconnected(deviceId: String?) {
        if (deviceId == null) return
        _state.update { snapshot ->
            snapshot.copy(clients = snapshot.clients.filterNot { it.deviceId == deviceId })
        }
    }

    /** Sets the exclusive capture phase. */
    fun setPhase(phase: ServerPipeline) {
        _state.update { it.copy(phase = phase) }
    }

    /** An LLM cleanup pass starts/stops; independent of the phase, so it can overlap. */
    fun setCleaning(active: Boolean) {
        _state.update { it.copy(cleaning = active) }
    }

    /** A desktop utterance opened: reset the per-utterance view. The stage is set by the host. */
    fun onCaptureStarted() {
        _state.update {
            it.copy(lastAsrMs = null, lastCleanupMs = null, committedWords = 0)
        }
    }

    /** A full committed snapshot plus this unit's compute share; drives the telemetry tiles. */
    fun onTelemetry(asrMs: Long, cleanupMs: Long, committedText: String) {
        _state.update {
            it.copy(
                lastAsrMs = if (asrMs > 0L || it.lastAsrMs == null) asrMs else it.lastAsrMs,
                lastCleanupMs = if (cleanupMs > 0L || it.lastCleanupMs == null) cleanupMs else it.lastCleanupMs,
                committedWords = countWords(committedText),
            )
        }
    }

    /** A `PING` arrived; `atRealtimeMs` is in the `SystemClock.elapsedRealtime()` domain. */
    fun onHeartbeat(atRealtimeMs: Long) {
        _state.update { it.copy(lastHeartbeatRealtimeMs = atRealtimeMs) }
    }

    private fun countWords(text: String): Int =
        if (text.isBlank()) 0 else text.trim().split(WHITESPACE).size

    companion object {
        /** The one process-wide runtime the UI and the service both use. */
        val shared = ServerRuntime()

        private val WHITESPACE = Regex("\\s+")
    }
}
