package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.asr.AsrEngine
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
import io.github.alexeikramm.sovereign.data.CleanupMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [DictationPipeline] with fakes, so the ASR→cleanup routing is tested on this machine rather
 * than on a phone. The engines are scripted: the fake ASR emits one unit per frame with an endpoint,
 * and the fake cleaner records/transforms what it is handed.
 */
class DictationPipelineTest {

    /** Emits [units] one per `accept` call, then [tail] on `finish`. */
    private class FakeAsrEngine(
        private val units: List<String>,
        private val tail: String = "",
    ) : AsrEngine {
        private var index = 0
        private var pending: String? = null
        var sessionStarts = 0

        override fun load(): LocalAsrEngine.Availability = LocalAsrEngine.Availability.Loaded

        override fun startSession(language: String, hotwords: String) {
            sessionStarts++
            index = 0
            pending = null
        }

        override fun accept(samples: FloatArray, count: Int): String? {
            if (index >= units.size) return null
            pending = units[index]
            index++
            return pending
        }

        override fun isSpeechDetected(): Boolean = true

        override fun isEndpoint(): Boolean = pending != null

        override fun result(): String = pending.orEmpty()

        override fun resetSegment() {
            pending = null
        }

        override fun finish(): String = tail

        override fun abort() {
            pending = null
        }
    }

    /**
     * Mimics the real recogniser's post-cut behaviour: after a segment ends the last text stays
     * available, and `resetSegment` does **not** clear it, so the next `accept` re-emits the
     * just-cut words as a partial. `isEndpoint` fires only once per unit, then stays quiet, so the
     * test can feed the leftover re-emission frames after the single cut.
     */
    private class StickyTextAsrEngine(
        private val units: List<String>,
    ) : AsrEngine {
        private var index = 0
        private var last: String = ""
        private var cutPending = false

        override fun load(): LocalAsrEngine.Availability = LocalAsrEngine.Availability.Loaded
        override fun startSession(language: String, hotwords: String) {
            index = 0
            last = ""
            cutPending = false
        }

        override fun accept(samples: FloatArray, count: Int): String? {
            if (index < units.size) {
                last = units[index]
                index++
                cutPending = true
            }
            return last
        }

        override fun isSpeechDetected(): Boolean = true
        override fun isEndpoint(): Boolean = cutPending
        override fun result(): String = last
        override fun resetSegment() {
            cutPending = false
        }
        override fun finish(): String = ""
        override fun abort() {}
    }

    private class FakeCleanupEngine(
        private val transform: (String) -> String = { it.trim() },
    ) : CleanupEngine {
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L
        var cleanCalls = 0

        /** This fake has no cache model; explicit 0s satisfy the abstract interface members. */
        override val tokenCount: Int get() = 0
        override val contextTokens: Int get() = 0

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun startSession(rules: String) {}

        override fun closeSession() {}

        override suspend fun clean(
            unit: String,
            rules: String,
        ): CleanupPass {
            cleanCalls++
            return CleanupPass(
                unit = unit,
                text = transform(unit),
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

    /**
     * Like [FakeCleanupEngine] but blocks inside `clean()` until [release] is called, so a test can
     * observe the pipeline *during* the cleanup latency — the window where the grey tail must stay
     * on screen. Runs on the pipeline's cleanup dispatcher; the DONE commit fires once released.
     */
    private class GatedCleanupEngine : CleanupEngine {
        private val gate = CompletableDeferred<Unit>()
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L

        /** This fake has no cache model; explicit 0s satisfy the abstract interface members. */
        override val tokenCount: Int get() = 0
        override val contextTokens: Int get() = 0

        fun release() = gate.complete(Unit)

        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override fun startSession(rules: String) {}
        override fun closeSession() {}
        override fun unload() {}

        override suspend fun clean(
            unit: String,
            rules: String,
        ): CleanupPass {
            gate.await()
            return CleanupPass(
                unit = unit,
                text = unit.trim().uppercase(),
                cleaned = true,
                note = "gated",
                loadMs = 0L,
                ttftMs = 0.0,
                decodeTimeMs = 0.0,
                generatedTokens = 1L,
                promptSent = unit,
            )
        }
    }

    /**
     * A fake that behaves like the **offline** engines: it decodes a whole segment, sets the pending
     * unit, and — crucially — returns **null** from `accept`, because offline models have no partials.
     * The unit is delivered through `result()` when `isEndpoint` fires. This pins the contract that
     * `accept`'s return value is a *partial*, so an offline engine returning its decoded text there
     * (the "grey flash" bug) is caught.
     */
    private class OfflineStyleAsrEngine(
        private val units: List<String>,
    ) : AsrEngine {
        private var index = 0
        private var pending: String? = null

        override fun load(): LocalAsrEngine.Availability = LocalAsrEngine.Availability.Loaded
        override fun startSession(language: String, hotwords: String) {
            index = 0
            pending = null
        }

        override fun accept(samples: FloatArray, count: Int): String? {
            when {
                index >= units.size -> return null
                pending != null -> return null // endpoint already queued; wait for the session to cut
                else -> {
                    pending = units[index]
                    index++
                    return null // never a partial: the decoded unit is final
                }
            }
        }

        override fun isSpeechDetected(): Boolean = true
        override fun isEndpoint(): Boolean = pending != null
        override fun result(): String = pending.orEmpty()
        override fun resetSegment() {
            pending = null
        }
        override fun finish(): String = ""
        override fun abort() {
            pending = null
        }
    }

    private fun pipeline(
        engine: AsrEngine,
        cleaner: CleanupEngine,
        cleanupMode: CleanupMode = CleanupMode.PER_SEGMENT,
        onText: (String, String, String) -> Unit = { _, _, _ -> },
        onPartialText: (String) -> Unit = {},
        onCleanupUnavailable: () -> Unit = {},
    ): DictationPipeline = DictationPipeline(
        asrEngine = engine,
        asrDispatcher = Dispatchers.Unconfined,
        cleanupEngine = cleaner,
        cleanupDispatcher = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        mainDispatcher = Dispatchers.Unconfined,
        timings = { ListenerTimings() },
        cleanupRules = { CleanupPrompt.SMART_CLEAN_RULES },
        healingSentencesCount = { 0 },
        cleanupMode = { cleanupMode },
        onText = onText,
        onCleanupUnavailable = onCleanupUnavailable,
        onPartialText = onPartialText,
    )

    /** A 100 ms s16le frame; the fake engine ignores the samples. */
    private fun feed(pipeline: DictationPipeline, frames: Int) {
        val bytes = ByteArray(3200)
        repeat(frames) { pipeline.onFrames(bytes, bytes.size) }
    }

    /** A 100 ms 16 kHz float frame; the fake engine ignores the samples. */
    private suspend fun feedFloats(pipeline: DictationPipeline, frames: Int) {
        val samples = FloatArray(1600)
        repeat(frames) { pipeline.onFramesSuspending(samples) }
    }

    /**
     * The audio-file replay path feeds already-decoded floats through the same pipeline as the mic
     * (D-126). This pins that the float feed produces the identical text to the byte feed.
     */
    @Test
    fun `the float replay feed delivers the same text as the mic byte feed`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello.", "World."))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feedFloats(pipeline, 2)
        pipeline.finish()

        assertEquals("Hello. World.", pipeline.committedText)
        assertEquals("Hello. World.", pipeline.rawText)
    }

    @Test
    fun `cleanup off routes every unit straight to the text and never calls the cleaner`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello.", "World."))
        val cleaner = FakeCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)
        pipeline.finish()

        assertEquals("Hello. World.", pipeline.committedText)
        assertEquals("Hello. World.", pipeline.rawText)
        assertEquals(0, cleaner.cleanCalls)
        assertTrue(!pipeline.isCleanupEngaged)
    }

    /**
     * The pause's punctuation arrives at the *start* of the next unit, so the pipeline must not insert
     * a space and push it off the word it punctuates. Regression for "word , and more".
     */
    @Test
    fun `cleanup off does not insert a space before a punctuation-led unit`() = runBlocking {
        val engine = FakeAsrEngine(listOf("I think", ", and more"))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)
        pipeline.finish()

        assertEquals("I think, and more.", pipeline.committedText)
    }

    @Test
    fun `the live provisional tail does not insert a space before leading punctuation`() = runBlocking {
        // Per-segment cleanup, gated, so both units sit in pendingClean at once — the grey preview
        // the user watches while speaking.
        val engine = ControllableAsrEngine()
        val cleaner = QueueGatedCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        engine.currentPartial = "I think"
        engine.currentCutUnit = "I think"
        feed(pipeline, 1)
        engine.currentPartial = ", and more"
        engine.currentCutUnit = ", and more"
        feed(pipeline, 1)

        assertEquals("I think, and more", pipeline.provisionalText)
    }

    @Test
    fun `cleanup on routes every unit through the cleaner`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello.", "World."))
        val cleaner = FakeCleanupEngine(transform = { it.trim().uppercase() })
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)
        pipeline.finish()

        assertEquals(2, cleaner.cleanCalls)
        assertEquals("HELLO. WORLD.", pipeline.committedText)
        // Raw stays the recogniser's own words, whatever cleanup did to the delivered text.
        assertEquals("Hello. World.", pipeline.rawText)
        assertTrue(pipeline.isCleanupEngaged)
    }

    @Test
    fun `seam healing replaces the overlap and commits the unified sentence`() = runBlocking {
        val engine = FakeAsrEngine(listOf("First sentence.", "Second sentence."))
        val cleaner = FakeCleanupEngine(transform = { unit ->
            if (unit.contains("First sentence") && unit.contains("Second sentence")) {
                "First sentence, and second sentence."
            } else {
                unit.trim()
            }
        })
        val pipeline = DictationPipeline(
            asrEngine = engine,
            asrDispatcher = Dispatchers.Unconfined,
            cleanupEngine = cleaner,
            cleanupDispatcher = Dispatchers.Unconfined,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            timings = { ListenerTimings() },
            cleanupRules = { CleanupPrompt.SMART_CLEAN_RULES },
            healingSentencesCount = { 1 },
            onText = { _, _, _ -> },
        )

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)
        pipeline.finish()

        assertEquals("First sentence, and second sentence.", pipeline.committedText)
    }

    @Test
    fun `finish flushes the tail as a final unit`() = runBlocking {
        val engine = FakeAsrEngine(units = emptyList(), tail = "Just the tail.")
        val cleaner = FakeCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        pipeline.finish()

        assertEquals("Just the tail.", pipeline.committedText)
    }

    @Test
    fun `reset clears the text and disengages cleanup`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val cleaner = FakeCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 1)
        pipeline.finish()
        assertTrue(pipeline.committedText.isNotBlank())

        pipeline.reset()
        assertEquals("", pipeline.committedText)
        assertEquals("", pipeline.rawText)
        assertTrue(!pipeline.isCleanupEngaged)
    }

    @Test
    fun `partial text is surfaced as the recogniser produces it`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello.", "World."))
        val partials = mutableListOf<String>()
        val pipeline = pipeline(engine, FakeCleanupEngine(), onPartialText = { partials.add(it) })

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)
        pipeline.finish()

        assertEquals(listOf("Hello.", "World."), partials)
    }

    @Test
    fun `provisional text survives the cut and is retired only when the unit commits`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        // Before any frame the recogniser has produced nothing provisional.
        assertEquals("", pipeline.provisionalText)

        feed(pipeline, 1)
        // With cleanup off the unit commits in the same update as the cut, so by the time we read,
        // the tail has already been replaced by the committed text.
        assertEquals("", pipeline.provisionalText)
        assertEquals("Hello.", pipeline.committedText)
    }

    @Test
    fun `reset clears any provisional text`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 1)

        pipeline.reset()
        assertEquals("", pipeline.provisionalText)
    }

    /**
     * The device bug (RP-1c): with cleanup on, the grey tail must stay visible across the cleanup
     * latency — from the partial, through the cut, until the cleaned text is committed — and be
     * replaced *in that same update*. Clearing it at cut time is what produced the blank gap.
     */
    @Test
    fun `with cleanup on the provisional tail persists until the cleaned text commits`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val cleaner = GatedCleanupEngine()
        val emissions = mutableListOf<Triple<String, String, String>>()
        val pipeline = pipeline(engine, cleaner, onText = { c, r, p -> emissions.add(Triple(c, r, p)) })

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 1) // partial, then cut -> cleanup submitted but not yet released

        // The unit was cut but cleanup has not finished: the grey tail must still be delivered.
        assertEquals("", pipeline.committedText)
        assertEquals("Hello.", pipeline.provisionalText)
        assertTrue(
            "a delivered update still carries the live tail while cleanup runs",
            emissions.any { it.third == "Hello." },
        )

        cleaner.release()
        // Now the cleaned text commits and the tail is retired in the same update.
        assertEquals("HELLO.", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertEquals(Triple("HELLO.", "Hello.", ""), emissions.last())
    }

    @Test
    fun `a committed unit is delivered with an empty provisional in the same update`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val emissions = mutableListOf<Triple<String, String, String>>()
        val pipeline = pipeline(engine, FakeCleanupEngine(), onText = { c, r, p -> emissions.add(Triple(c, r, p)) })

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 1)

        assertEquals("Hello.", pipeline.committedText)
        assertEquals(
            "the last delivered update shows the committed text and no provisional tail",
            Triple("Hello.", "Hello.", ""),
            emissions.last(),
        )
    }

    @Test
    fun `a re-emitted partial from the just-cut segment does not resurrect the committed text`() = runBlocking {
        // The real recogniser keeps returning the cut segment's text until the next segment's words
        // arrive; the session reports it as a partial. That must not re-show it as a grey tail.
        val engine = StickyTextAsrEngine(listOf("Hello."))
        val emissions = mutableListOf<Triple<String, String, String>>()
        val pipeline = pipeline(engine, FakeCleanupEngine(), onText = { c, r, p -> emissions.add(Triple(c, r, p)) })

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 3) // one cut, then two frames of the leftover re-emission

        assertEquals("Hello.", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertEquals(
            "the grey tail is never re-delivered after the unit commits",
            Triple("Hello.", "Hello.", ""),
            emissions.last(),
        )
        assertTrue(
            "no delivered update resurrects a non-empty provisional after the commit",
            emissions.dropWhile { it.first != "Hello." }.all { it.third.isEmpty() },
        )
    }

    @Test
    fun `an offline-style engine never surfaces a partial and commits white`() = runBlocking {
        // Offline models (Whisper, Parakeet) have no partials: their decoded segment is final. The
        // engine returns null from accept, so the pipeline must show no provisional tail at any point
        // — otherwise the committed text flashes grey for a moment before turning white.
        val engine = OfflineStyleAsrEngine(listOf("Paris is the capital."))
        val partials = mutableListOf<String>()
        val emissions = mutableListOf<Triple<String, String, String>>()
        val pipeline = pipeline(
            engine,
            FakeCleanupEngine(),
            onText = { c, r, p -> emissions.add(Triple(c, r, p)) },
            onPartialText = { partials.add(it) },
        )

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 2)

        assertEquals("Paris is the capital.", pipeline.committedText)
        assertEquals(emptyList<String>(), partials)
        assertTrue(
            "no delivered update ever carried a provisional tail",
            emissions.all { it.third.isEmpty() },
        )
        assertEquals("", pipeline.provisionalText)
    }

    private class ControllableAsrEngine : AsrEngine {
        var currentPartial: String? = null
        var currentCutUnit: String? = null

        override fun load(): LocalAsrEngine.Availability = LocalAsrEngine.Availability.Loaded
        override fun startSession(language: String, hotwords: String) {}
        override fun accept(samples: FloatArray, count: Int): String? = currentPartial
        override fun isSpeechDetected(): Boolean = true
        override fun isEndpoint(): Boolean = currentCutUnit != null
        override fun result(): String = currentCutUnit.orEmpty()
        override fun resetSegment() {
            currentCutUnit = null
        }
        override fun finish(): String = ""
        override fun abort() {}
    }

    private class QueueGatedCleanupEngine : CleanupEngine {
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()
        override val isLoaded: Boolean = true
        override val lastLoadMs: Long = 0L
        override val tokenCount: Int get() = 0
        override val contextTokens: Int get() = 0

        fun releaseNext() {
            gates.removeFirstOrNull()?.complete(Unit)
        }

        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override fun startSession(rules: String) {}
        override fun closeSession() {}
        override fun unload() {}

        override suspend fun clean(unit: String, rules: String): CleanupPass {
            val gate = CompletableDeferred<Unit>()
            gates.add(gate)
            gate.await()
            return CleanupPass(
                unit = unit,
                text = unit.trim().uppercase(),
                cleaned = true,
                note = "queue-gated",
                loadMs = 0L,
                ttftMs = 0.0,
                decodeTimeMs = 0.0,
                generatedTokens = 1L,
                promptSent = unit,
            )
        }
    }

    @Test
    fun `speaking during in-flight cleanup does not drop cut unit or wipe new partial`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = QueueGatedCleanupEngine()
        val emissions = mutableListOf<Triple<String, String, String>>()
        val pipeline = pipeline(engine, cleaner, onText = { c, r, p -> emissions.add(Triple(c, r, p)) })

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        // 1. First unit cuts and enters cleanup (gated)
        engine.currentPartial = "Unit 1"
        engine.currentCutUnit = "Unit 1"
        feed(pipeline, 1)

        assertEquals("", pipeline.committedText)
        assertEquals("Unit 1", pipeline.provisionalText)
        assertTrue(pipeline.hasPendingClean)

        // 2. User starts speaking next unit before cleanup finishes
        engine.currentCutUnit = null
        engine.currentPartial = "speaking next unit"
        feed(pipeline, 1)

        // The cut unit must NOT disappear while cleanup is in-flight: both pendingClean
        // and liveProvisional are displayed together.
        assertEquals("", pipeline.committedText)
        assertEquals("Unit 1 speaking next unit", pipeline.provisionalText)

        // 3. Cleanup pass for Unit 1 completes
        cleaner.releaseNext()

        // Unit 1 becomes white committed text, while the live partial stays intact (not wiped)
        assertEquals("UNIT 1", pipeline.committedText)
        assertEquals("speaking next unit", pipeline.provisionalText)
        assertTrue(!pipeline.hasPendingClean)

        // 4. Now the second unit cuts and enters cleanup
        engine.currentCutUnit = "speaking next unit"
        feed(pipeline, 1)
        assertEquals("UNIT 1", pipeline.committedText)
        assertEquals("speaking next unit", pipeline.provisionalText)
        assertTrue(pipeline.hasPendingClean)

        cleaner.releaseNext()
        assertEquals("UNIT 1 SPEAKING NEXT UNIT", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
    }

    @Test
    fun `multiple units queued in cleanup maintain strict FIFO and preserve live partial`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = QueueGatedCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        // Cut unit 1
        engine.currentPartial = "First"
        engine.currentCutUnit = "First"
        feed(pipeline, 1)

        // Cut unit 2 while unit 1 is still in cleanup
        engine.currentPartial = "Second"
        engine.currentCutUnit = "Second"
        feed(pipeline, 1)

        // Live partial for unit 3
        engine.currentCutUnit = null
        engine.currentPartial = "Third partial"
        feed(pipeline, 1)

        // All pending units + live partial stay visible in provisional text
        assertEquals("", pipeline.committedText)
        assertEquals("First Second Third partial", pipeline.provisionalText)
        assertTrue(pipeline.hasPendingClean)

        // Release first unit
        cleaner.releaseNext()
        assertEquals("FIRST", pipeline.committedText)
        assertEquals("Second Third partial", pipeline.provisionalText)
        assertTrue(pipeline.hasPendingClean)

        // Release second unit
        cleaner.releaseNext()
        assertEquals("FIRST SECOND", pipeline.committedText)
        assertEquals("Third partial", pipeline.provisionalText)
        assertTrue(!pipeline.hasPendingClean)
    }

    @Test
    fun `with cleanup disabled pendingClean stays empty and units commit immediately`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = FakeCleanupEngine()
        val pipeline = pipeline(engine, cleaner)

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")

        // Streaming partial before cut
        engine.currentPartial = "Raw words"
        engine.currentCutUnit = null
        feed(pipeline, 1)

        assertEquals("", pipeline.committedText)
        assertEquals("Raw words", pipeline.provisionalText)
        assertTrue(!pipeline.hasPendingClean)

        // Cut unit commits immediately without LLM queuing
        engine.currentCutUnit = "Raw words"
        feed(pipeline, 1)

        assertEquals("Raw words", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertTrue(!pipeline.hasPendingClean)
    }

    @Test
    fun `per-utterance cleanup keeps speech provisional in gray and cleans entire transcript on finish`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = FakeCleanupEngine(transform = { it.trim().uppercase() })
        val textEvents = mutableListOf<String>()
        val pipeline = pipeline(
            engine = engine,
            cleaner = cleaner,
            cleanupMode = CleanupMode.PER_UTTERANCE,
            onText = { committed, _, _ -> if (committed.isNotEmpty()) textEvents.add(committed) },
        )

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        // First unit cut
        engine.currentCutUnit = "hello world"
        feed(pipeline, 1)

        // Staged in provisional (gray), committed is empty, cleaner NOT yet called
        assertEquals("", pipeline.committedText)
        assertEquals("hello world", pipeline.provisionalText)
        assertEquals(0, cleaner.cleanCalls)
        assertTrue(pipeline.hasPendingClean)

        // Second unit cut
        engine.currentCutUnit = "how are you"
        feed(pipeline, 1)

        assertEquals("", pipeline.committedText)
        assertEquals("hello world how are you", pipeline.provisionalText)
        assertEquals(0, cleaner.cleanCalls)
        assertTrue(pipeline.hasPendingClean)
        assertTrue(textEvents.isEmpty())

        // On finish, cleaner is invoked once with the entire transcript
        val finalSettled = pipeline.finish()

        assertEquals(1, cleaner.cleanCalls)
        assertEquals("HELLO WORLD HOW ARE YOU.", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertTrue(!pipeline.hasPendingClean)
        assertEquals("HELLO WORLD HOW ARE YOU.", finalSettled)
        assertTrue(pipeline.totalActiveLlmMs > 0L)
        // Settled text emitted exactly once with final punctuation; no intermediate unpunctuated emission
        assertEquals(listOf("HELLO WORLD HOW ARE YOU."), textEvents)
    }

    @Test
    fun `per-utterance cleanup fails closed to raw words when cleaner crashes`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = FakeCleanupEngine(transform = { throw RuntimeException("NPU OOM") })
        var unavailableCalled = false
        val pipeline = pipeline(
            engine = engine,
            cleaner = cleaner,
            cleanupMode = CleanupMode.PER_UTTERANCE,
            onCleanupUnavailable = { unavailableCalled = true },
        )

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        engine.currentCutUnit = "important words"
        feed(pipeline, 1)

        assertEquals("", pipeline.committedText)
        assertEquals("important words", pipeline.provisionalText)

        val finalSettled = pipeline.finish()

        // Fails closed to raw words (with final punctuation) and surfaces failure advisory
        assertEquals("important words.", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertTrue(unavailableCalled)
        assertEquals("important words.", finalSettled)
    }

    @Test
    fun `per-utterance cleanup on blank utterance returns empty text without calling cleaner`() = runBlocking {
        val engine = ControllableAsrEngine()
        val cleaner = FakeCleanupEngine()
        val pipeline = pipeline(engine, cleaner, cleanupMode = CleanupMode.PER_UTTERANCE)

        pipeline.begin(fresh = true, cleanupEnabled = true)
        pipeline.beginSession()
        pipeline.open("en")

        val finalSettled = pipeline.finish()

        assertEquals(0, cleaner.cleanCalls)
        assertEquals("", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
        assertEquals("", finalSettled)
    }
}
