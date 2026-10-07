package io.github.alexeikramm.sovereign.server

import kotlinx.coroutines.CompletableDeferred

/**
 * Reports whether a [SovereignWebSocketServer] actually bound its port.
 *
 * Java-WebSocket binds asynchronously inside its selector thread, so `start()` returning says nothing
 * about success — the outcome only arrives later as `onStart()` (bound) or `onError(null, …)`
 * (failed). Without this gate the service logged "Server started" and advertised over mDNS even when
 * the bind had failed, so the dashboard showed *running* while nothing was listening and every client
 * was refused. Found on a device: stopping and restarting the server while a client was connected
 * raised `BindException: Address already in use` (the listener socket was not yet released), yet the
 * phone still claimed to be serving.
 *
 * Kept Android-free so the transition logic is unit-tested on the JVM.
 */
class BindGate {

    private val result = CompletableDeferred<Unit>()

    /** The port is bound and the server is listening (`WebSocketServer.onStart`). */
    fun bound() {
        result.complete(Unit)
    }

    /** The server failed before it bound — e.g. the port was unavailable (`onError(null, …)`). */
    fun failed(error: Throwable) {
        result.completeExceptionally(error)
    }

    /**
     * Suspends until the outcome is known, then returns normally if bound or throws the startup
     * failure. A failure signalled after a successful bind (e.g. a later worker fatal) is ignored.
     */
    suspend fun await() = result.await()
}
