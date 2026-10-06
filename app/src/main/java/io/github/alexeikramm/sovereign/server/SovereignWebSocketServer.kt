package io.github.alexeikramm.sovereign.server

import android.util.Log
import java.net.InetSocketAddress
import java.nio.ByteBuffer
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
) : WebSocketServer(InetSocketAddress(boundPort)) {

    private class Connection(val engine: SovereignServerEngine, val scope: CoroutineScope)

    private val connections = ConcurrentHashMap<WebSocket, Connection>()

    /** Own scope for frame handling, so it survives individual connection teardown. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(tls.sslContext))
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket server listening on wss://0.0.0.0:$boundPort${ServerProtocol.PATH}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val connectionScope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = SovereignServerEngine(
            identity = identity,
            pairing = pairing,
            capture = capture,
            scope = connectionScope,
            sink = { message -> send(conn, message) },
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
        scope.launch {
            connection.engine.onTransportClosed()
            connection.scope.cancel()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
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

    private companion object {
        const val TAG = "SovereignServer"
        const val NORMAL_CLOSURE = 1000
    }
}
