package io.github.alexeikramm.sovereign.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins [ServerProtocol] to the frozen golden vectors in
 * `docs/work/phone-server-desktop/protocol-vectors/` (PROTOCOL.md §11). The desktop repo runs the
 * same vectors against its C# implementation, so this is the cross-repo contract test.
 *
 * Pure JVM: no device.
 */
class ServerProtocolTest {

    private val vectorsDir: File by lazy {
        listOf(
            File("docs/work/phone-server-desktop/protocol-vectors"),
            File("../docs/work/phone-server-desktop/protocol-vectors"),
            File("../../docs/work/phone-server-desktop/protocol-vectors"),
        ).firstOrNull { it.isDirectory }
            ?: error("protocol-vectors not found from ${File(".").absolutePath}")
    }

    private val manifest: JsonObject by lazy {
        Json.parseToJsonElement(File(vectorsDir, "manifest.json").readText(Charsets.UTF_8)).jsonObject
    }

    private val cases: List<JsonObject> by lazy {
        manifest["cases"]?.jsonArray?.map { it.jsonObject }
            ?: error("manifest.json has no `cases` array")
    }

    private fun caseText(case: JsonObject): String =
        File(vectorsDir, case.str("file")).readText(Charsets.UTF_8)

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("manifest case missing `$key`: $this")

    @Test
    fun `the manifest declares this protocol version`() {
        assertEquals(ServerProtocol.VERSION, manifest["protover"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `every catalogue message type has exactly one valid vector`() {
        val validTypes = cases.filter { it.str("expect") == "valid" }.map { it.str("type") }
        assertEquals("duplicate valid vectors", validTypes.size, validTypes.toSet().size)
        assertEquals(
            "valid vectors must cover the catalogue exactly",
            MessageType.entries.map { it.wire }.toSet(),
            validTypes.toSet(),
        )
    }

    @Test
    fun `every manifest case exists and every vector file is referenced`() {
        val referenced = cases.map { it.str("file") }.toSet()
        for (file in referenced) {
            assertTrue("manifest references missing file $file", File(vectorsDir, file).isFile)
        }
        val onDisk = vectorsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name }?.filter { it != "manifest.json" }?.toSet().orEmpty()
        assertEquals("orphan vector files not in the manifest", onDisk, referenced)
    }

    @Test
    fun `every valid vector decodes, re-encodes and round-trips exactly`() {
        for (case in cases.filter { it.str("expect") == "valid" }) {
            val file = case.str("file")
            val text = caseText(case)

            val decoded = ServerProtocol.decode(text)
            assertTrue("$file: expected Decoded, got $decoded", decoded is DecodeResult.Decoded)
            val message = (decoded as DecodeResult.Decoded).message
            assertEquals("$file: message type", case.str("type"), message.type.wire)

            // Re-encoding must reproduce the same JSON document (key order is not significant).
            val reencoded = ServerProtocol.encode(message)
            assertEquals(
                "$file: re-encoded JSON differs",
                Json.parseToJsonElement(text),
                Json.parseToJsonElement(reencoded),
            )

            // The re-encoded bytes must decode to the identical message.
            assertEquals(
                "$file: decode(encode(message)) != message",
                message,
                (ServerProtocol.decode(reencoded) as DecodeResult.Decoded).message,
            )
        }
    }

    @Test
    fun `unknown message types are ignored, not rejected`() {
        val case = cases.single { it.str("expect") == "ignored" }
        val decoded = ServerProtocol.decode(caseText(case))
        assertTrue("§3: unknown type must be ignored, got $decoded", decoded is DecodeResult.Ignored)
    }

    @Test
    fun `invalid vectors are rejected with the expected fault`() {
        for (case in cases.filter { it.str("expect") == "invalid" }) {
            val file = case.str("file")
            val expected = ProtocolFault.valueOf(case.str("fault"))
            val decoded = ServerProtocol.decode(caseText(case))
            assertTrue("$file: expected Rejected, got $decoded", decoded is DecodeResult.Rejected)
            assertEquals("$file: fault", expected, (decoded as DecodeResult.Rejected).fault)
        }
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val ping = Json.parseToJsonElement(caseText(cases.single { it.str("file") == "ping.json" })).jsonObject
        val payload = ping["payload"]!!.jsonObject
        val mutated = JsonObject(
            ping +
                ("futureField" to JsonPrimitive(42)) +
                ("payload" to JsonObject(payload + ("anotherFutureField" to JsonPrimitive("ok")))),
        )
        val decoded = ServerProtocol.decode(mutated.toString())
        assertTrue("unknown fields must be ignored, got $decoded", decoded is DecodeResult.Decoded)
    }

    @Test
    fun `oversized frames are rejected as TOO_LARGE`() {
        val huge = "x".repeat(ServerProtocol.MAX_FRAME_BYTES + 1)
        val frame =
            """{"v":1,"type":"PROVISIONAL_TEXT","id":"x","ts":1,""" +
                """"payload":{"sessionId":"s","seq":1,"text":"$huge"}}"""
        assertEquals(
            ProtocolFault.TOO_LARGE,
            (ServerProtocol.decode(frame) as? DecodeResult.Rejected)?.fault,
        )
    }

    @Test
    fun `malformed json is rejected`() {
        assertEquals(
            ProtocolFault.MALFORMED_ENVELOPE,
            (ServerProtocol.decode("{not json") as DecodeResult.Rejected).fault,
        )
    }
}
