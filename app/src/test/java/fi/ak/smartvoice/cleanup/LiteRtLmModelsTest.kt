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
    fun `catalog contains only Gemma 4 E2B and E4B models`() {
        val ids = LiteRtLmModels.ALL.map { it.id }
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b"), ids)
    }
}
