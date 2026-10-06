package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport-level frame policy (Chunk 1b): which inbound frames are forwarded, ignored, or
 * answered with a fatal `ERROR` (PROTOCOL.md §1/§3/§8). Pure JVM — no socket.
 */
class ServerFramesTest {

    private fun failFor(text: String): ServerFrames.Disposition.Fail =
        ServerFrames.disposition(text) as ServerFrames.Disposition.Fail

    private fun errorOf(text: String): ErrorPayload =
        failFor(text).error.payload as ErrorPayload

    @Test
    fun `a valid envelope is forwarded`() {
        val encoded = ServerProtocol.encode(
            ServerMessage(MessageType.PING, "id-1", 0L, PingPayload(123L)),
        )

        val disposition = ServerFrames.disposition(encoded)

        assertTrue(disposition is ServerFrames.Disposition.Forward)
        val message = (disposition as ServerFrames.Disposition.Forward).message
        assertEquals(MessageType.PING, message.type)
        assertEquals(123L, (message.payload as PingPayload).clientTs)
    }

    @Test
    fun `an unknown type is ignored, not rejected`() {
        val text = """{"v":1,"type":"FUTURE_THING","id":"x","ts":0,"payload":{}}"""
        assertEquals(ServerFrames.Disposition.Ignore, ServerFrames.disposition(text))
    }

    @Test
    fun `a version mismatch is fatal and closes`() {
        val text = """{"v":2,"type":"PING","id":"x","ts":0,"payload":{"clientTs":1}}"""

        val disposition = failFor(text)

        assertTrue(disposition.close)
        val error = errorOf(text)
        assertEquals(ErrorCode.PROTO_VERSION, error.code)
        assertTrue(error.fatal)
    }

    @Test
    fun `an over-sized frame is fatal TOO_LARGE and closes`() {
        val text = """{"v":1,"type":"PING","id":"x","ts":0,"payload":{"clientTs":1},"pad":"${
            "x".repeat(ServerProtocol.MAX_FRAME_BYTES)
        }"}"""

        assertTrue(failFor(text).close)
        assertEquals(ErrorCode.TOO_LARGE, errorOf(text).code)
    }

    @Test
    fun `a missing payload field is a non-fatal internal fault`() {
        val text = """{"v":1,"type":"PING","id":"x","ts":0,"payload":{}}"""

        val disposition = failFor(text)

        assertFalse("the connection stays up", disposition.close)
        val error = errorOf(text)
        assertEquals(ErrorCode.INTERNAL, error.code)
        assertFalse(error.fatal)
    }
}
