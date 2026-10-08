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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer

/**
 * The drafts the transport serves with. Uses the 2-arg `Draft_6455(extensions, maxFrameSize)` overload,
 * NOT the 3-arg `(extensions, protocols, maxFrameSize)` one: `Draft_6455.acceptHandshakeAsServer`
 * requires a matched protocol AND a matched extension, and the 2-arg overload supplies the default
 * `Protocol("")` that matches any client. An explicit empty protocol list returns `NOT_MATCHED` for
 * every handshake, so the server answers every upgrade with "404 WebSocket Upgrade Failure"
 * (`WebSocketImpl.closeConnectionDueToWrongHandshake`) — a live-but-dead listener. Only the frame-size
 * cap is overridden; extensions/protocols stay at their defaults. Internal so a host test can pin the
 * handshake contract: a broken draft still *constructs*, so construction alone proves nothing.
 */
internal fun serverDrafts(): List<Draft> = listOf(Draft_6455(emptyList(), ServerProtocol.MAX_FRAME_BYTES))

/**
 * The pinned-TLS `wss://` endpoint (PROTOCOL.md §1): Java-WebSocket bound to `port` and serving the
 * certificate from [tls]. It owns one [SovereignServerEngine] per connection and is purely the
 * socket + framing layer — protocol decisions live in the engine (Chunk 1a) and session decisions in
 * the [CaptureHost].
 *
 * All engine work is marshalled onto [dispatcher] (the main dispatcher), because the engine mutates
 * shared dictation state and must see every message and session callback on one thread. Frame
 * *parsing* happens on the socket thread instead, so a decode flood cannot queue work on main.
 *
 * Untrusted-LAN hardening (Chunk 9C): the transport caps frame size, live and pending connections,
 * and per-connection message rate, and reaps idle connections — all before the protocol engine ever
 * runs. TLS + token auth still gate every protocol action.
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
) : WebSocketServer(
    InetSocketAddress(boundPort),
    serverDrafts(),
) {

    private class Connection(
        val engine: SovereignServerEngine,
        val scope: CoroutineScope,
    ) {
        val limiter = MessageRateLimiter()

        /** `elapsedRealtime` of the last inbound frame, for the idle sweep. */
        @Volatile
        var lastFrameMs: Long = SystemClock.elapsedRealtime()
    }

    private val connections = ConcurrentHashMap<WebSocket, Connection>()

    /** Guards the connection cap's check-then-insert: onOpen runs on per-connection worker threads. */
    private val connectionLock = Any()

    /** Own scope for frame handling, so it survives individual connection teardown. */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Whether the port actually bound; the service waits on it before reporting RUNNING. */
    private val bindGate = BindGate()

    init {
        // SO_REUSEADDR so a restart can rebind the port while connections from the previous run are
        // still lingering (Java-WebSocket defaults it to false; a stop/start with a client attached
        // otherwise fails with BindException: Address already in use).
        setReuseAddr(true)
        // Bound the handshake backlog so a TLS-connect flood cannot pile up un-handshaken sockets.
        setMaxPendingConnections(MAX_PENDING_CONNECTIONS)
        // The bounded Draft_6455 goes through the super constructor: the server passes its own drafts
        // to the factory's createWebSocket, so this is what actually stops Java-WebSocket from
        // allocating a multi-gigabyte frame before ServerProtocol's 256 KiB check can run.
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
        // Reap connections that stop sending frames. The real client PINGs every 5 s, so a link
        // silent for IDLE_TIMEOUT_MS is dead — and if it held a session, closing it frees the arbiter
        // via onClose/onTransportClosed instead of leaving the microphone held for minutes.
        scope.launch {
            while (isActive) {
                delay(IDLE_SWEEP_MS)
                val now = SystemClock.elapsedRealtime()
                connections.forEach { (conn, connection) ->
                    if (now - connection.lastFrameMs > IDLE_TIMEOUT_MS) {
                        Log.i(TAG, "Closing an idle connection (no frame in ${IDLE_TIMEOUT_MS}ms)")
                        runCatching { conn.close(CloseFrame.POLICY_VALIDATION, "idle") }
                    }
                }
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
        synchronized(connectionLock) {
            if (connections.size >= MAX_CONNECTIONS) {
                Log.w(TAG, "Refusing connection: cap of $MAX_CONNECTIONS reached")
                connectionScope.cancel()
                runCatching { conn.close(CloseFrame.TRY_AGAIN_LATER, "server busy") }
                return
            }
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
        }
        Log.i(TAG, "Connection opened (${connections.size} active)")
        onConnectionCountChanged(connections.size)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val connection = connections[conn] ?: return
        if (!admit(conn, connection)) return

        // Decode on this (socket) thread; only the protocol engine is marshalled onto main.
        val disposition = ServerFrames.disposition(message)
        scope.launch {
            when (disposition) {
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
        val connection = connections[conn] ?: return
        if (!admit(conn, connection)) return
        Log.d(TAG, "Ignored a binary frame (${message.remaining()} bytes)")
    }

    /**
     * Applies the per-connection rate limit for one inbound frame and updates its idle clock. Returns
     * false when the frame was rate-limited (answered [ErrorCode.RATE_LIMITED]) or the connection was
     * closed for abuse. The reply is sent inline on the socket thread — queueing it on the main
     * dispatcher would reintroduce the flood the limiter exists to stop.
     */
    private fun admit(conn: WebSocket, connection: Connection): Boolean {
        connection.lastFrameMs = SystemClock.elapsedRealtime()
        return when (connection.limiter.check()) {
            MessageRateLimiter.Decision.ALLOW -> true
            MessageRateLimiter.Decision.CLOSE -> {
                Log.w(TAG, "Closing a rate-abusive connection")
                runCatching { conn.close(CloseFrame.POLICY_VALIDATION, "rate limited") }
                false
            }
            MessageRateLimiter.Decision.LIMIT -> {
                send(conn, rateLimitedError())
                false
            }
        }
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

    private fun rateLimitedError(): ServerMessage = ServerMessage(
        type = MessageType.ERROR,
        id = UUID.randomUUID().toString(),
        ts = System.currentTimeMillis(),
        payload = ErrorPayload(ErrorCode.RATE_LIMITED, "too many messages", fatal = false),
    )

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

        /** Live authenticated/unauthenticated connections; a handful of paired desktops is the norm. */
        const val MAX_CONNECTIONS = 8

        /** Un-handshaken sockets allowed to pile up before the listener refuses more. */
        const val MAX_PENDING_CONNECTIONS = 16

        /** A connection silent this long (>> the 5 s client PING) is treated as dead. */
        const val IDLE_TIMEOUT_MS = 20_000L
        const val IDLE_SWEEP_MS = 5_000L
    }
}
