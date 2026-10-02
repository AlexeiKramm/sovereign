package io.github.alexeikramm.sovereign.net

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response shapes are copied from the server's frozen contract §2/§3, so these tests pin the
 * client to the contract rather than to whatever the server happens to send today. They run on the
 * JVM — no device needed.
 */
class TranscribeModelsTest {

    private fun parse(json: String) =
        StackJson.decodeFromString<TranscribeResponse>(json)

    @Test
    fun `cleanup off response parses with no cleanup stage`() {
        val r = parse(
            """
            {"text":"raw transcript","stages_run":["stt"],"corrections":[],
             "latency_ms":{"stt":1800,"cleanup":null,"total":1800},"warnings":[],
             "detected_language":"fi"}
            """.trimIndent()
        )

        assertEquals("raw transcript", r.text)
        assertEquals(1800L, r.latencyMs?.sttMs())
        assertNull(r.latencyMs?.cleanupMs())
        assertFalse(r.cleanupRan)
        assertFalse(r.cleanupUnavailable)
        assertEquals("fi", r.detectedLanguage)
    }

    @Test
    fun `cleanup full response exposes corrections and both stage timings`() {
        val r = parse(
            """
            {"text":"cleaned text","stages_run":["stt","cleanup"],
             "corrections":[{"from":"umachi","to":"Umacut"}],
             "latency_ms":{"stt":1800,"cleanup":2200,"total":4000},"warnings":[],
             "detected_language":"fi"}
            """.trimIndent()
        )

        assertTrue(r.cleanupRan)
        assertEquals(1, r.corrections.size)
        assertEquals("umachi", r.corrections[0].from)
        assertEquals(2200L, r.latencyMs?.cleanupMs())
        assertEquals(4000L, r.latencyMs?.totalMs())
    }

    /**
     * Regression test from the first real device round trip (2026-09-11).
     *
     * The contract's example writes integer milliseconds, but the server emits fractional ones, so
     * a `Long` field failed to parse a perfectly valid response. The payload below is the observed
     * one: `latency_ms.stt = 665.6670000000133`, `cleanup = null`, `corrections` empty.
     *
     * Doubles accept both shapes, which is the point: the client must not care whether the server
     * reports 1800 or 1800.0.
     */
    @Test
    fun `fractional milliseconds from the real server parse and round for display`() {
        val r = parse(
            """
            {"text":"nopea ruskea kettu","stages_run":["stt"],"corrections":[],
             "latency_ms":{"stt":665.6670000000133,"cleanup":null,"total":665.6670000000133},
             "warnings":[],"detected_language":"fi"}
            """.trimIndent()
        )

        assertEquals(665.6670000000133, r.latencyMs!!.stt, 1e-9)
        assertEquals(666L, r.latencyMs!!.sttMs())
        assertNull(r.latencyMs!!.cleanupMs())
        assertEquals("fi", r.detectedLanguage)
    }

    @Test
    fun `integer milliseconds still parse after the Double change`() {
        val r = parse(
            """
            {"text":"ok","stages_run":["stt"],"corrections":[],
             "latency_ms":{"stt":1800,"cleanup":2200,"total":4000},"warnings":[],
             "detected_language":"fi"}
            """.trimIndent()
        )

        assertEquals(1800.0, r.latencyMs!!.stt, 0.0)
        assertEquals(2200L, r.latencyMs!!.cleanupMs())
    }

    @Test
    fun `cleanup unavailable is a degraded success not an error`() {
        // The contract is explicit: LLM absence is 200 + warning, never an error status.
        val r = parse(
            """
            {"text":"raw transcript","stages_run":["stt"],"corrections":[],
             "latency_ms":{"stt":1800,"cleanup":null,"total":1800},
             "warnings":["cleanup_unavailable"],"detected_language":"fi"}
            """.trimIndent()
        )

        assertTrue(r.cleanupUnavailable)
        assertFalse(r.cleanupRan)
        assertEquals("raw transcript", r.text)
    }

    @Test
    fun `additive response keys do not break parsing`() {
        val r = parse(
            """
            {"text":"ok","stages_run":["stt"],"corrections":[],
             "latency_ms":{"stt":1,"cleanup":null,"total":1},"warnings":[],
             "detected_language":null,"engine":"parakeet","queue_depth":0}
            """.trimIndent()
        )

        assertEquals("ok", r.text)
    }

    @Test
    fun `error envelope parses the contract code`() {
        val e = StackJson.decodeFromString<ApiErrorEnvelope>(
            """{"error":{"code":"audio_too_long","message":"duration 4000s exceeds max"}}""",
        )

        assertEquals("audio_too_long", e.error?.code)
        assertTrue(humanMessage(e.error!!.code, "x").contains("max_audio_seconds"))
    }

    @Test
    fun `422 keeps fastapi detail shape`() {
        val e = StackJson.decodeFromString<ApiErrorEnvelope>(
            """{"detail":[{"loc":["body","audio"],"msg":"field required"}]}""",
        )

        assertNull(e.error)
        assertTrue(e.detail is JsonPrimitive == false)
    }

    @Test
    fun `meta parses contract version`() {
        val m = StackJson.decodeFromString<MetaResponse>("""{"contract_version":"1.0"}""")
        assertEquals(EXPECTED_CONTRACT_VERSION, m.contractVersion)
    }
}
