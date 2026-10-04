package io.github.alexeikramm.sovereign.cleanup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
            onCommitted = { commits.add(it) },
        )

        pipeline.begin()
        val seam = SeamSplit(
            overlapText = "driving to the supermarket.",
            strippedOverlap = "driving to the supermarket",
        )
        pipeline.submit("I realized something", seam)
        pipeline.drain()

        assertEquals(1, commits.size)
        val commit = commits[0]
        assertEquals("driving to the supermarket I realized something", commit.text)
        assertEquals("driving to the supermarket.", commit.overlapToDelete)
        org.junit.Assert.assertTrue(commit.cleaned)
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
            onCommitted = {},
        )

        pipeline.begin(initialTail = "Earlier committed text.")
        val seam = SeamSplit(
            overlapText = "driving to the supermarket.",
            strippedOverlap = "driving to the supermarket",
        )
        pipeline.submit("I realized something", seam)
        pipeline.drain()

        // Continuity is the overlap itself, part of the unit handed to the model; the pipeline adds
        // no separate context (D-089, D-095).
        assertEquals(listOf("driving to the supermarket I realized something"), engine.units)
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
}

