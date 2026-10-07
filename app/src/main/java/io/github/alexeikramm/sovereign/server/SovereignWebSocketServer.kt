package io.github.alexeikramm.sovereign.server

import android.os.SystemClock
import android.util.Log
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer

/**
 * The pinned-TLS `wss://` endpoint (PROTOCOL.md §1): Java-WebSocket bound to `port` and serving the
 * certificate from [tls]. It owns one [SovereignServerEngine] per connection and is purely the
 * socket + framing layer — protocol decisions live in the engine (Chunk 1a) and session decisions in
 * the [CaptureHost].
 *
 * All engine work is marshalled onto [dispatcher] (the main dispatcher), because the engine mutates
 * shared dictation state and must see every message and session callback on one thread. Socket reads
 * arrive on Java-WebSocket's own threads; they post here.
 */
class SovereignWebSocketServer(
    private val boundPort: Int,
    tls: ServerTls.Identity,
    private val identity: ServerIdentity,
    private val pairing: PairingAuthority,
    private val capture: CaptureHost,
    private val dispatcher: CoroutineDispatcher,
    private val onConnectionCountChanged: (Int) -> Unit = {},
    /** Where the dashboard's connected-device list and heartbeat readout come from (Chunk 2a). */
    private val runtime: ServerRuntime = ServerRuntime.shared,
    /** The process-wide reservation source; its changes are broadcast as `SERVER_STATE` (CHUNK-8). */
    private val arbiter: SessionArbiter = SessionArbiter.shared,
) : WebSocketServer(InetSocketAddress(boundPort)) {

    private class Connection(val engine: SovereignServerEngine, val scope: CoroutineScope)

    private val connections = ConcurrentHashMap<WebSocket, Connection>()

    /** Own scope for frame handling, so it survives individual connection teardown. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Whether the port actually bound; the service waits on it before reporting RUNNING. */
    private val bindGate = BindGate()

    init {
        // SO_REUSEADDR so a restart can rebind the port while connections from the previous run are
        // still lingering (Java-WebSocket defaults it to false; a stop/start with a client attached
        // otherwise fails with BindException: Address already in use).
        setReuseAddr(true)
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(tls.sslContext))
        // CHUNK-8: broadcast the pipeline's availability to every connected desktop whenever the
        // process-wide arbiter changes. StateFlow emits the current value first, so this also covers
        // a restart's initial state; a freshly connected client is told again by its own engine
        // immediately after WELCOME (SovereignServerEngine.onHello).
        scope.launch {
            arbiter.holderFlow.collect { owner ->
                broadcast(ServerMessage(
                    type = MessageType.SERVER_STATE,
                    id = UUID.randomUUID().toString(),
                    ts = System.currentTimeMillis(),
                    payload = serverStatePayload(owner, identity),
                ))
            }
        }
    }

    override fun onStart() {
        bindGate.bound()
        Log.i(TAG, "WebSocket server listening on wss://0.0.0.0:$boundPort${ServerProtocol.PATH}")
    }

    /** Suspends until the port is bound, or throws the bind failure. */
    suspend fun awaitBound() = bindGate.await()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val connectionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = SovereignServerEngine(
            identity = identity,
            pairing = pairing,
            capture = capture,
            scope = connectionScope,
            sink = { message -> send(conn, message) },
            onAuthenticated = { device ->
                runtime.onClientConnected(device.deviceId, device.name, remoteAddress(conn))
            },
            onHeartbeat = { runtime.onHeartbeat(SystemClock.elapsedRealtime()) },
            availability = { serverStatePayload(arbiter.holder, identity) },
        )
        connections[conn] = Connection(engine, connectionScope)
        Log.i(TAG, "Connection opened (${connections.size} active)")
        onConnectionCountChanged(connections.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        scope.launch {
            val connection = connections[conn] ?: return@launch
            when (val disposition = ServerFrames.disposition(message)) {
                is ServerFrames.Disposition.Forward -> connection.engine.onMessage(disposition.message)
                ServerFrames.Disposition.Ignore -> Log.d(TAG, "Ignored an unknown message type")
                is ServerFrames.Disposition.Fail -> {
                    send(conn, disposition.error)
                    if (disposition.close) {
                        runCatching { conn.close(NORMAL_CLOSURE, disposition.error.type.wire) }
                    }
                }
            }
        }
    }

    /** Binary frames are reserved (PROTOCOL.md §1); v1 ignores them rather than guessing. */
    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        Log.d(TAG, "Ignored a binary frame (${message.remaining()} bytes)")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val connection = connections.remove(conn) ?: return
        Log.i(TAG, "Connection closed: code=$code reason=$reason remote=$remote (${connections.size} active)")
        onConnectionCountChanged(connections.size)
        runtime.onClientDisconnected(connection.engine.authenticatedDevice?.deviceId)
        scope.launch {
            connection.engine.onTransportClosed()
            connection.scope.cancel()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        // A null connection means a server-level fault; before onStart it is the bind failing.
        if (conn == null) {
            bindGate.failed(ex)
        }
        Log.w(TAG, "WebSocket error${conn?.remoteSocketAddress?.let { " ($it)" } ?: ""}", ex)
    }

    /** Tears the listener down and drops every connection. Safe to call once. */
    fun shutdown() {
        runCatching { stop(1000) }.onFailure { Log.w(TAG, "Server stop reported", it) }
        scope.cancel()
    }

    private fun send(conn: WebSocket, message: ServerMessage) {
        runCatching { conn.send(ServerProtocol.encode(message)) }
            .onFailure { Log.w(TAG, "Failed to send ${message.type.wire}", it) }
    }

    /** Sends one unsolicited frame to every open connection (CHUNK-8's `SERVER_STATE`). */
    private fun broadcast(message: ServerMessage) {
        if (connections.isEmpty()) return
        val frame = ServerProtocol.encode(message)
        connections.keys.forEach { conn ->
            runCatching { conn.send(frame) }
                .onFailure { Log.w(TAG, "Failed to broadcast ${message.type.wire}", it) }
        }
    }

    /** `ip:port` of the peer, for the dashboard's connected-device row. */
    private fun remoteAddress(conn: WebSocket): String? =
        conn.remoteSocketAddress?.let { "${it.address?.hostAddress ?: it.hostString}:${it.port}" }

    private companion object {
        const val TAG = "SovereignServer"
        const val NORMAL_CLOSURE = 1000
    }
}
