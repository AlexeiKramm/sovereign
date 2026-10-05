package io.github.alexeikramm.sovereign.cleanup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class CleanupPipelineTest {

    private class FakeEngine : CleanupEngine {
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L
        var sessionStarts = 0
        var sessionCloses = 0

        /** Fire the rebuild trigger once this reaches [contextTokens]' policy threshold. */
        var reportedTokens: Int = 0
        /** How much each pass grows the cache, modelling a segment + its reply. */
        var tokensPerPass: Int = 0
        /** Records every `onConversationRebuilt` call, for asserting the reported figures. */
        val rebuildReports = mutableListOf<Pair<Int, Int>>()
        /** Records the unit handed to every `clean`, in order. */
        val units = mutableListOf<String>()
        override val contextTokens: Int = 2048
        override val tokenCount: Int get() = reportedTokens

        override fun onConversationRebuilt(usedTokens: Int, capTokens: Int) {
            rebuildReports.add(usedTokens to capTokens)
        }

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun startSession(rules: String) {
            sessionStarts++
        }

        override fun closeSession() {
            sessionCloses++
        }

        override suspend fun clean(
            unit: String,
            rules: String,
        ): CleanupPass {
            reportedTokens += tokensPerPass
            units.add(unit)
            return CleanupPass(
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
        }

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
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
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
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
        )

        pipeline.begin()
        assertEquals(1, engine.sessionStarts)
        assertEquals(0, engine.sessionCloses)

        pipeline.abort()
        assertEquals(1, engine.sessionCloses)
    }

    @Test
    fun `seam healing submits overlap and commits with overlapToDelete`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val commits = mutableListOf<CleanupCommit>()

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            healingSentencesCount = { 1 },
            onCommitted = { commits.add(it) },
        )

        pipeline.begin(initialTail = "First sentence. Second sentence.")
        pipeline.submit("and then some")
        pipeline.drain()

        assertEquals(1, commits.size)
        val commit = commits[0]
        assertEquals("Second sentence and then some", commit.text)
        assertEquals("Second sentence.", commit.overlapToDelete)
        assertTrue(commit.cleaned)
    }

    @Test
    fun `a healed overlap is glued into the unit, not sent as separate context`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            healingSentencesCount = { 1 },
            onCommitted = {},
        )

        pipeline.begin(initialTail = "First sentence. Second sentence.")
        pipeline.submit("and then some")
        pipeline.drain()

        // The seam overlap is part of the unit handed to the model; the pipeline adds no separate
        // context (D-089, D-095).
        assertEquals(listOf("Second sentence and then some"), engine.units)
    }

    @Test
    fun `an append-only pass cleans the unit alone`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            // healingSentencesCount defaults to 0, so no seam is manufactured: append-only mode.
            onCommitted = {},
        )

        pipeline.begin(initialTail = "Earlier committed text.")
        pipeline.submit("a new unit")
        pipeline.drain()

        // No seam is manufactured and no context is added: the unit is cleaned as spoken (D-089, D-095).
        assertEquals(listOf("a new unit"), engine.units)
    }

    @Test
    fun `each queued unit is cleaned as its own unit, in order`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
        )

        pipeline.begin()
        pipeline.submit("first unit")
        pipeline.submit("second unit")
        pipeline.drain()

        assertEquals(listOf("first unit", "second unit"), engine.units)
    }

    @Test
    fun `queued units during continuous dictation do not repeat earlier sentences`() = runBlocking {
        val engine = FakeEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val committedText = StringBuilder()

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            healingSentencesCount = { 2 },
            onCommitted = { commit ->
                if (commit.overlapToDelete.isNotEmpty() && committedText.endsWith(commit.overlapToDelete)) {
                    committedText.setLength(committedText.length - commit.overlapToDelete.length)
                }
                committedText.append(commit.prefix)
                committedText.append(commit.text)
            },
        )

        pipeline.begin()

        val u1 = "Well, just sit with it, you know, just sit with it."
        val u2 = "It's just the temporary feeling, what not and don't respond to it."
        val u3 = "And I don't know precisely why, but there's just something really dissatisfying."

        pipeline.submit(u1)
        pipeline.submit(u2)
        pipeline.submit(u3)

        pipeline.drain()

        val fullText = committedText.toString()
        val occurrences = Regex("Well, just sit with it").findAll(fullText).count()
        assertEquals("Sentence should appear exactly once in final text, but appeared $occurrences times: $fullText", 1, occurrences)
    }

    @Test
    fun `conversation is rebuilt once the cache crosses the threshold, and not before`() = runBlocking {
        val engine = FakeEngine().apply { tokensPerPass = 1000 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
        )

        pipeline.begin()
        assertEquals(1, engine.sessionStarts) // begin opens the first session

        // First pass -> 1000 tokens, below 80% of 2048 (1638): no rebuild.
        pipeline.submit("one")
        assertEquals(1, engine.sessionStarts)
        assertTrue("no rebuild reported below the threshold", engine.rebuildReports.isEmpty())

        // Second pass -> 2000 tokens, past the threshold: the conversation is rebuilt.
        pipeline.submit("two")
        assertEquals("rebuild should re-open the session", 2, engine.sessionStarts)
        assertEquals(
            "the engine is told (and logs) the exact figures that triggered the rebuild",
            listOf(2000 to 2048),
            engine.rebuildReports,
        )

        pipeline.drain()
    }

    @Test
    fun `an engine that cannot report its cache is never rebuilt`() = runBlocking {
        val engine = object : CleanupEngine {
            override val isLoaded: Boolean = true
            override val lastLoadMs: Long = 0L
            var sessionStarts = 0
            override suspend fun load(): Result<Unit> = Result.success(Unit)
            override fun startSession(rules: String) {
                sessionStarts++
            }

            override suspend fun clean(
                unit: String,
                rules: String,
            ): CleanupPass = CleanupPass.failed(unit, "unused")

            override fun unload() {}
            // Explicit zeros: this engine has no cache model, so the policy must never fire. (The
            // members are abstract now — a wrapper can no longer inherit a silent 0.)
            override val tokenCount: Int get() = 0
            override val contextTokens: Int get() = 0
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
        )

        pipeline.begin()
        repeat(5) { pipeline.submit("unit $it") }

        assertEquals("only begin() opens a session; no rebuilds without token reporting", 1, engine.sessionStarts)
        pipeline.drain()
    }

    @Test
    fun `maxTurns rebuilds the conversation after the configured number of passes`() = runBlocking {
        val engine = FakeEngine().apply { tokensPerPass = 1 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val telemetry = mutableListOf<CleanupPassTelemetry>()

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            maxTurns = { 2 },
            onCommitted = {},
            onPass = { telemetry.add(it) },
        )

        pipeline.begin()
        repeat(4) { pipeline.submit("unit $it") }
        pipeline.drain()

        // begin() + one rebuild after pass 2 + one after pass 4 = 3 sessions; never a capacity rebuild.
        assertEquals(3, engine.sessionStarts)
        assertEquals("rebuild fires on passes 2 and 4 only", listOf(false, true, false, true), telemetry.map { it.rebuiltAfter })
        assertEquals("the turn counter restarts after each rebuild", listOf(0, 1, 0, 1), telemetry.map { it.turnsInConversation })
        assertTrue(
            "a turn-limit rebuild, never a capacity one",
            telemetry.filter { it.rebuiltAfter }.all { it.rebuildReason == ConversationRebuildPolicy.RebuildReason.TURNS },
        )
        assertEquals("passes are numbered across the whole utterance", listOf(1, 2, 3, 4), telemetry.map { it.index })
    }

    @Test
    fun `telemetry reports the pre-pass token position and the cleaned output`() = runBlocking {
        val engine = FakeEngine().apply { tokensPerPass = 100 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val telemetry = mutableListOf<CleanupPassTelemetry>()

        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = Dispatchers.Unconfined,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
            onPass = { telemetry.add(it) },
        )

        pipeline.begin()
        pipeline.submit("first")
        pipeline.submit("second")
        pipeline.drain()

        assertEquals("the pre-pass cache size is what the quality curve is plotted against", listOf(0, 100), telemetry.map { it.tokensBefore })
        assertEquals(listOf(100, 200), telemetry.map { it.tokensAfter })
        assertEquals(listOf("first", "second"), telemetry.map { it.cleanedText })
    }

    @Test
    fun `abortAndJoin waits for the in-flight pass, drops the queue, and closes the session`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var cleans = 0
        val engine = object : CleanupEngine {
            override val isLoaded: Boolean = true
            override val lastLoadMs: Long = 0L
            var sessionCloses = 0
            override val tokenCount: Int get() = 0
            override val contextTokens: Int get() = 0
            override suspend fun load(): Result<Unit> = Result.success(Unit)
            override fun closeSession() { sessionCloses++ }
            override suspend fun clean(unit: String, rules: String): CleanupPass {
                cleans++
                entered.complete(Unit)
                // Models a native decode: coroutine cancellation does not interrupt it mid-flight.
                withContext(NonCancellable) { gate.await() }
                return CleanupPass.failed(unit, "gated")
            }
            override fun unload() {}
        }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pipeline = CleanupPipeline(
            engine = engine,
            scope = scope,
            dispatcher = dispatcher,
            rules = { CleanupPrompt.SMART_CLEAN_RULES },
            onCommitted = {},
        )

        pipeline.begin()
        pipeline.submit("one")
        pipeline.submit("two")
        pipeline.submit("three")
        entered.await()

        val abortJob = launch { pipeline.abortAndJoin() }
        delay(50)
        assertFalse("abort must wait for the in-flight pass to finish", abortJob.isCompleted)

        gate.complete(Unit)
        abortJob.join()

        assertEquals("buffered units are dropped, not cleaned", 1, cleans)
        assertEquals("abort closes the session once", 1, engine.sessionCloses)
        scope.cancel()
        dispatcher.close()
    }
}


