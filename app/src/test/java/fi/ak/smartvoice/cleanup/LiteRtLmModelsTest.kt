package fi.ak.smartvoice.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmModelsTest {

    @Test
    fun `the LiteRT catalog is not empty and default is in it`() {
        assertTrue(LiteRtLmModels.ALL.isNotEmpty())
        assertTrue(LiteRtLmModels.DEFAULT in LiteRtLmModels.ALL)
    }

    @Test
    fun `every model declares valid sha256 and approximate size`() {
        LiteRtLmModels.ALL.forEach { model ->
            assertEquals("${model.id} sha256 length", 64, model.sha256.length)
            assertTrue("${model.id} sha256 hex", model.sha256.all { it in "0123456789abcdef" })
            assertTrue("${model.id} size positive", model.approximateBytes > 0L)
            assertTrue("${model.id} fileName ends with .litertlm", model.fileName.endsWith(".litertlm"))
        }
    }

    @Test
    fun `model ids are unique`() {
        val ids = LiteRtLmModels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `lookup by id falls back to default on unknown or null`() {
        assertEquals(LiteRtLmModels.DEFAULT, LiteRtLmModels.byId(null))
        assertEquals(LiteRtLmModels.DEFAULT, LiteRtLmModels.byId(""))
        assertEquals(LiteRtLmModels.DEFAULT, LiteRtLmModels.byId("unknown"))
        LiteRtLmModels.ALL.forEach { model ->
            assertEquals(model, LiteRtLmModels.byId(model.id))
        }
    }

    @Test
    fun `catalog contains Gemma 4 and Gemma 3 models`() {
        val ids = LiteRtLmModels.ALL.map { it.id }
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b", "gemma-3-1b-it-qat"), ids)
    }

    @Test
    fun `speculative decoding support flags match model capabilities`() {
        assertTrue(LiteRtLmModels.GEMMA_4_E2B.supportsSpeculativeDecoding)
        assertTrue(LiteRtLmModels.GEMMA_4_E4B.supportsSpeculativeDecoding)
        org.junit.Assert.assertFalse(LiteRtLmModels.GEMMA_3_1B_QAT.supportsSpeculativeDecoding)
    }

    @Test
    fun `gpu support flags match model capabilities`() {
        assertTrue(LiteRtLmModels.GEMMA_4_E2B.supportsGpu)
        assertTrue(LiteRtLmModels.GEMMA_4_E4B.supportsGpu)
        org.junit.Assert.assertFalse(LiteRtLmModels.GEMMA_3_1B_QAT.supportsGpu)
    }

    @Test
    fun `each model pins its own sampling parameters`() {
        // The engine holds no sampler numbers; these are the only definition, so pin them.
        assertEquals(
            SamplingParams(temperature = 0.2, topK = 64, topP = 0.95, seed = 0),
            LiteRtLmModels.GEMMA_4_E2B.sampling,
        )
        assertEquals(
            SamplingParams(temperature = 0.2, topK = 64, topP = 0.95, seed = 0),
            LiteRtLmModels.GEMMA_4_E4B.sampling,
        )
        assertEquals(
            SamplingParams(temperature = 0.2, topK = 64, topP = 0.95, seed = 0),
            LiteRtLmModels.GEMMA_3_1B_QAT.sampling,
        )
    }

    @Test
    fun `every model states all four sampling parameters and they satisfy the runtime constraints`() {
        LiteRtLmModels.ALL.forEach { model ->
            val s = model.sampling
            assertTrue("${model.id} topK must be positive", s.topK > 0)
            assertTrue("${model.id} topP must be in 0..1", s.topP in 0.0..1.0)
            assertTrue("${model.id} temperature must be non-negative", s.temperature >= 0.0)
        }
    }

    @Test
    fun `the default sampler is today's numbers so an untuned model keeps current behaviour`() {
        assertEquals(0.1, SamplingParams.DEFAULT.temperature, 0.0)
        assertEquals(40, SamplingParams.DEFAULT.topK)
        assertEquals(0.95, SamplingParams.DEFAULT.topP, 0.0)
        assertEquals(0, SamplingParams.DEFAULT.seed)
    }
}
