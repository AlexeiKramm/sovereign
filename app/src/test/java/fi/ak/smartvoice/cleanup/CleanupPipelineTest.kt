package fi.ak.smartvoice.cleanup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupPipelineTest {

    private class FakeEngine : CleanupEngine {
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L
        var sessionStarts = 0
        var sessionCloses = 0

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun startSession(variant: CleanupPrompt.Variant, customRules: String?) {
            sessionStarts++
        }

        override fun closeSession() {
            sessionCloses++
        }

        override suspend fun clean(
            unit: String,
            context: String?,
            variant: CleanupPrompt.Variant,
            customRules: String?,
        ): CleanupPass = CleanupPass(
            unit = unit,
            text = unit.trim(),
            cleaned = true,
            note = "fake",
            loadMs = 0L,
            ttftMs = 0.0,
            decodeTimeMs = 0.0,
            generatedTokens = 1L,
            promptSent = unit,
        )

        override fun unload() {}
    }

    @Test
    fun `begin starts session and drain closes session`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val committed = mutableListOf<String>()

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.Variant.STANDARD },
            onCommitted = { committed.add(it.text) },
        )

        pipeline.begin()
        assertEquals(1, engine.sessionStarts)
        assertEquals(0, engine.sessionCloses)

        pipeline.submit("hello")
        pipeline.drain()

        assertEquals(1, engine.sessionStarts)
        assertEquals(1, engine.sessionCloses)
        assertEquals(listOf("hello"), committed)
    }

    @Test
    fun `abort closes session`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.Variant.STANDARD },
            onCommitted = {},
        )

        pipeline.begin()
        assertEquals(1, engine.sessionStarts)
        assertEquals(0, engine.sessionCloses)

        pipeline.abort()
        assertEquals(1, engine.sessionCloses)
    }
}
