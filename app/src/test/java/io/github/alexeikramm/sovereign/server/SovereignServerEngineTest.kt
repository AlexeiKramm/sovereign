package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
private val IDENTITY = ServerIdentity(DEVICE_ID, "Phone")

/**
 * The protocol/session brain (Chunk 1a) driven entirely by fakes: no Android, no sockets. These are
 * the flows whose corruption would surface as a wrong or duplicated word on the desktop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SovereignServerEngineTest {

    private class Harness(private val scope: TestScope) {
        val sent = mutableListOf<ServerMessage>()
        val capture = FakeCaptureHost()
        val pairing = FakePairingAuthority()
        var availabilityState = ServerStatePayload(ServerState.IDLE, "", "")
        val engine = SovereignServerEngine(
            IDENTITY,
            pairing,
            capture,
            scope,
            { sent += it },
            availability = { availabilityState },
        )

        fun send(type: MessageType, payload: MessagePayload) =
            engine.onMessage(ServerMessage(type, "in-${sent.size}", 0L, payload))

        fun last() = sent.last()
        fun ofType(type: MessageType) = sent.filter { it.type == type }

        @Suppress("UNCHECKED_CAST")
        fun <T : MessagePayload> lastPayload(type: MessageType): T =
            sent.last { it.type == type }.payload as T

        /** Drive pair → PAIR_DONE → HELLO, leaving the engine READY. */
        fun authenticate() {
            send(MessageType.PAIR_HELLO, PairHelloPayload(DEVICE_ID, "Desktop", ServerProtocol.VERSION))
            val nonce = lastPayload<PairChallengePayload>(MessageType.PAIR_CHALLENGE).serverNonce
            scope.advanceUntilIdle()
            val token = lastPayload<PairResponsePayload>(MessageType.PAIR_RESPONSE).token
            send(MessageType.PAIR_DONE, PairDonePayload(PairingProof.compute(token, nonce)))
            send(MessageType.HELLO, HelloPayload(token, DEVICE_ID, ServerProtocol.VERSION, emptyList()))
            // CHUNK-8: HELLO is answered with WELCOME followed immediately by the availability frame.
            assertEquals(
                listOf(MessageType.WELCOME, MessageType.SERVER_STATE),
                sent.takeLast(2).map { it.type },
            )
        }

        fun start(requestId: String = "req-1"): String {
            send(MessageType.START_DICTATION, StartDictationPayload(requestId, DictationMode.HOLD))
            return lastPayload<StartAckPayload>(MessageType.START_ACK).sessionId
        }
    }

    @Test
    fun `pair, authenticate, dictate and stop delivers exactly once`() = runTest {
        val h = Harness(this)
        h.authenticate()
        val sid = h.start()

        h.capture.listener!!.onState(PipelineState.WARMING)
        assertEquals(PipelineState.WARMING, h.lastPayload<StateChangedPayload>(MessageType.STATE_CHANGED).state)

        h.capture.emitProvisional("hello wor")
        h.capture.emitCommit("Hello world.", CommitPerf(120L, 300L))

        h.send(MessageType.STOP_DICTATION, StopDictationPayload(sid))
        advanceUntilIdle()

        val done = h.lastPayload<UtteranceDonePayload>(MessageType.UTTERANCE_DONE)
        assertEquals("final text", done.deliveryText)
        assertEquals("raw text", done.rawText)
        assertEquals(UtteranceDoneReason.FINAL, done.reason)
        assertEquals(ReleaseReason.STOPPED, h.lastPayload<SessionReleasedPayload>(MessageType.SESSION_RELEASED).reason)
        assertEquals(1, h.ofType(MessageType.UTTERANCE_DONE).size)

        // A retried STOP finds the session already gone; it must not produce a second done.
        h.send(MessageType.STOP_DICTATION, StopDictationPayload(sid))
        advanceUntilIdle()
        assertEquals(1, h.ofType(MessageType.UTTERANCE_DONE).size)
        assertEquals(ErrorCode.SESSION_UNKNOWN, h.lastPayload<ErrorPayload>(MessageType.ERROR).code)
    }

    @Test
    fun `commit text is the full snapshot and never appended`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.start()

        h.capture.emitCommit("one", CommitPerf(1L, 2L))
        h.capture.emitCommit("one two", CommitPerf(3L, 4L))

        val commits = h.ofType(MessageType.COMMIT_TEXT).map { it.payload as CommitTextPayload }
        assertEquals(listOf("one", "one two"), commits.map { it.text })
        assertEquals(listOf(1L, 2L), commits.map { it.seq })
    }

    @Test
    fun `provisional text is truncated to the protocol limit`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.start()

        h.capture.emitProvisional("x".repeat(ServerProtocol.MAX_PROVISIONAL_CHARS + 500))

        val provisional = h.lastPayload<ProvisionalTextPayload>(MessageType.PROVISIONAL_TEXT)
        assertEquals(ServerProtocol.MAX_PROVISIONAL_CHARS, provisional.text.length)
        assertEquals(1L, provisional.seq)
    }

    @Test
    fun `an arbiter held by the pill yields SESSION_BUSY`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.capture.beginOutcome = StartOutcome.Busy(SessionArbiter.Owner.PILL)

        h.send(MessageType.START_DICTATION, StartDictationPayload("req-1", DictationMode.HOLD))

        val busy = h.lastPayload<SessionBusyPayload>(MessageType.SESSION_BUSY)
        assertEquals(DEVICE_ID, busy.holderDeviceId)
        assertFalse(busy.canTakeover)
        assertTrue(h.ofType(MessageType.START_ACK).isEmpty())
    }

    @Test
    fun `a second start while busy is refused`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.start("req-1")

        h.send(MessageType.START_DICTATION, StartDictationPayload("req-2", DictationMode.HOLD))

        assertEquals(MessageType.SESSION_BUSY, h.last().type)
        assertEquals(1, h.ofType(MessageType.START_ACK).size)
    }

    @Test
    fun `the same requestId is idempotent`() = runTest {
        val h = Harness(this)
        h.authenticate()
        val sid = h.start("req-1")

        h.send(MessageType.START_DICTATION, StartDictationPayload("req-1", DictationMode.HOLD))

        val acks = h.ofType(MessageType.START_ACK).map { (it.payload as StartAckPayload).sessionId }
        assertEquals(listOf(sid, sid), acks)
        assertTrue(h.ofType(MessageType.SESSION_BUSY).isEmpty())
    }

    @Test
    fun `cancel releases without delivering text`() = runTest {
        val h = Harness(this)
        h.authenticate()
        val sid = h.start()

        h.send(MessageType.CANCEL_DICTATION, CancelDictationPayload(sid))

        assertEquals(ReleaseReason.CANCELLED, h.lastPayload<SessionReleasedPayload>(MessageType.SESSION_RELEASED).reason)
        assertTrue(h.ofType(MessageType.UTTERANCE_DONE).isEmpty())
        assertTrue(h.capture.cancelled)
    }

    @Test
    fun `an unknown session id is SESSION_UNKNOWN`() = runTest {
        val h = Harness(this)
        h.authenticate()

        h.send(MessageType.STOP_DICTATION, StopDictationPayload("nope"))

        assertEquals(ErrorCode.SESSION_UNKNOWN, h.lastPayload<ErrorPayload>(MessageType.ERROR).code)
    }

    @Test
    fun `session messages before HELLO are AUTH_REQUIRED`() = runTest {
        val h = Harness(this)

        h.send(MessageType.START_DICTATION, StartDictationPayload("req-1", DictationMode.HOLD))

        val error = h.lastPayload<ErrorPayload>(MessageType.ERROR)
        assertEquals(ErrorCode.AUTH_REQUIRED, error.code)
        assertTrue(error.fatal)
        assertTrue(h.ofType(MessageType.START_ACK).isEmpty())
    }

    @Test
    fun `a bad token is AUTH_FAIL BAD_TOKEN`() = runTest {
        val h = Harness(this)
        h.send(MessageType.HELLO, HelloPayload("wrong", DEVICE_ID, ServerProtocol.VERSION, emptyList()))
        assertEquals(AuthFailReason.BAD_TOKEN, h.lastPayload<AuthFailPayload>(MessageType.AUTH_FAIL).reason)
    }

    @Test
    fun `a protover mismatch is AUTH_FAIL PROTO_VERSION`() = runTest {
        val h = Harness(this)
        h.send(MessageType.HELLO, HelloPayload("t", DEVICE_ID, 2, emptyList()))
        assertEquals(AuthFailReason.PROTO_VERSION, h.lastPayload<AuthFailPayload>(MessageType.AUTH_FAIL).reason)
    }

    @Test
    fun `denied pairing fails and never issues a token`() = runTest {
        val h = Harness(this)
        h.pairing.decision = PairingDecision.DENIED

        h.send(MessageType.PAIR_HELLO, PairHelloPayload(DEVICE_ID, "desk", ServerProtocol.VERSION))
        advanceUntilIdle()

        assertEquals(PairFailReason.DENIED, h.lastPayload<PairFailPayload>(MessageType.PAIR_FAIL).reason)
        assertTrue(h.ofType(MessageType.PAIR_RESPONSE).isEmpty())
    }

    @Test
    fun `a locked-out authority refuses pairing before the dialog`() = runTest {
        val h = Harness(this)
        h.pairing.lockoutMs = 60_000L

        h.send(MessageType.PAIR_HELLO, PairHelloPayload(DEVICE_ID, "desk", ServerProtocol.VERSION))

        val fail = h.lastPayload<PairFailPayload>(MessageType.PAIR_FAIL)
        assertEquals(PairFailReason.LOCKED, fail.reason)
        assertEquals(0, fail.attemptsLeft)
        assertTrue(h.ofType(MessageType.PAIR_CHALLENGE).isEmpty())
    }

    @Test
    fun `a bad pairing proof is refused`() = runTest {
        val h = Harness(this)
        h.send(MessageType.PAIR_HELLO, PairHelloPayload(DEVICE_ID, "desk", ServerProtocol.VERSION))
        advanceUntilIdle()

        h.send(MessageType.PAIR_DONE, PairDonePayload("not-the-proof"))

        assertEquals(PairFailReason.DENIED, h.lastPayload<PairFailPayload>(MessageType.PAIR_FAIL).reason)
        assertEquals(SovereignServerEngine.Phase.AWAITING_PAIRING, h.engine.phase)
    }

    @Test
    fun `an authenticated client is told the current availability`() = runTest {
        val h = Harness(this)
        h.availabilityState = ServerStatePayload(ServerState.RESERVED, DEVICE_ID, "Phone")

        h.authenticate()

        val state = h.lastPayload<ServerStatePayload>(MessageType.SERVER_STATE)
        assertEquals(ServerState.RESERVED, state.state)
        assertEquals(DEVICE_ID, state.holderDeviceId)
        assertEquals("Phone", state.holderName)
    }

    @Test
    fun `ping is echoed as pong`() = runTest {
        val h = Harness(this)
        h.send(MessageType.PING, PingPayload(12345L))
        assertEquals(12345L, h.lastPayload<PongPayload>(MessageType.PONG).clientTs)
    }

    @Test
    fun `closing the transport cancels an active capture and ignores later frames`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.start()

        h.engine.onTransportClosed()

        assertTrue(h.capture.cancelled)
        assertEquals(SovereignServerEngine.Phase.CLOSED, h.engine.phase)
        h.send(MessageType.PING, PingPayload(1L))
        assertTrue(h.ofType(MessageType.PONG).isEmpty())
    }

    @Test
    fun `a capture error releases the session instead of wedging`() = runTest {
        val h = Harness(this)
        h.authenticate()
        h.start("req-1")

        // The host reports a warmup failure (it has already aborted and released the arbiter).
        h.capture.listener!!.onState(PipelineState.ERROR)

        assertEquals(ReleaseReason.ERROR, h.lastPayload<SessionReleasedPayload>(MessageType.SESSION_RELEASED).reason)
        // The session is gone, so a fresh start is accepted rather than refused as busy.
        h.start("req-2")
        assertEquals(2, h.ofType(MessageType.START_ACK).size)
        assertTrue(h.ofType(MessageType.SESSION_BUSY).isEmpty())
    }

    @Test
    fun `a throwing finish releases the session`() = runTest {
        val h = Harness(this)
        h.authenticate()
        val sid = h.start()
        h.capture.finishThrows = true

        h.send(MessageType.STOP_DICTATION, StopDictationPayload(sid))
        advanceUntilIdle()

        assertEquals(ReleaseReason.ERROR, h.lastPayload<SessionReleasedPayload>(MessageType.SESSION_RELEASED).reason)
        assertTrue(h.ofType(MessageType.UTTERANCE_DONE).isEmpty())
    }

    @Test
    fun `a repeat HELLO is ignored once ready`() = runTest {
        val h = Harness(this)
        h.authenticate()
        val welcomes = h.ofType(MessageType.WELCOME).size

        h.send(MessageType.HELLO, HelloPayload("whatever", DEVICE_ID, ServerProtocol.VERSION, emptyList()))

        assertEquals(welcomes, h.ofType(MessageType.WELCOME).size)
        assertTrue(h.ofType(MessageType.AUTH_FAIL).isEmpty())
    }

    @Test
    fun `a known but disabled client is refused with DISABLED`() = runTest {
        val h = Harness(this)
        // Pair the device, then switch it off in the registry, before it authenticates (D-133).
        h.send(MessageType.PAIR_HELLO, PairHelloPayload(DEVICE_ID, "Desktop", ServerProtocol.VERSION))
        val nonce = h.lastPayload<PairChallengePayload>(MessageType.PAIR_CHALLENGE).serverNonce
        advanceUntilIdle()
        val token = h.lastPayload<PairResponsePayload>(MessageType.PAIR_RESPONSE).token
        h.send(MessageType.PAIR_DONE, PairDonePayload(PairingProof.compute(token, nonce)))
        h.pairing.disable(DEVICE_ID)

        h.send(MessageType.HELLO, HelloPayload(token, DEVICE_ID, ServerProtocol.VERSION, emptyList()))

        assertEquals(AuthFailReason.DISABLED, h.lastPayload<AuthFailPayload>(MessageType.AUTH_FAIL).reason)
        assertTrue(h.ofType(MessageType.WELCOME).isEmpty())
        // It never reaches READY: a session message is still refused as AUTH_REQUIRED.
        h.send(MessageType.START_DICTATION, StartDictationPayload("req-1", DictationMode.HOLD))
        assertEquals(ErrorCode.AUTH_REQUIRED, h.lastPayload<ErrorPayload>(MessageType.ERROR).code)
    }

    // --- fakes --------------------------------------------------------------------------------

    private class FakeCaptureHost : CaptureHost {
        var listener: CaptureListener? = null
        var beginOutcome: StartOutcome = StartOutcome.Started
        var finishResult = CaptureResult("final text", "raw text")
        var finishThrows = false
        var cancelled = false

        override fun begin(listener: CaptureListener): StartOutcome {
            this.listener = listener
            return beginOutcome
        }

        override suspend fun finish(): CaptureResult {
            if (finishThrows) throw IllegalStateException("finish boom")
            return finishResult
        }

        override fun cancel() {
            cancelled = true
        }

        fun emitProvisional(text: String) = listener!!.onProvisional(text)
        fun emitCommit(snapshot: String, perf: CommitPerf) = listener!!.onCommit(snapshot, perf)
    }

    private class FakePairingAuthority(
        override val serverSpki: String = "spki",
    ) : PairingAuthority {
        var decision: PairingDecision = PairingDecision.APPROVED
        var lockoutMs: Long = 0L
        private val devices = LinkedHashMap<String, PairedDevice>()
        private var failures = 0
        private var counter = 0

        override suspend fun approve(deviceId: String, name: String): PairingDecision =
            if (lockoutMs > 0) PairingDecision.LOCKED else decision

        override fun issue(deviceId: String, name: String): PairedDevice {
            val device = PairedDevice(deviceId, name, "token-${counter++}")
            devices[deviceId] = device
            return device
        }

        override fun authenticate(deviceId: String, token: String): PairedDevice? =
            devices[deviceId]?.takeIf { it.enabled && it.token == token }

        override fun matchToken(deviceId: String, token: String): PairedDevice? =
            devices[deviceId]?.takeIf { it.token == token }

        /** Switch a paired device off, as Server settings does (D-133). */
        fun disable(deviceId: String) {
            devices[deviceId]?.let { devices[deviceId] = it.copy(enabled = false) }
        }

        override fun lockoutRemainingMs(): Long = lockoutMs

        override fun attemptsRemaining(): Int = (5 - failures).coerceAtLeast(0)

        override fun recordFailedProof() {
            failures += 1
            if (failures >= 5) lockoutMs = 60_000L
        }
    }
}
