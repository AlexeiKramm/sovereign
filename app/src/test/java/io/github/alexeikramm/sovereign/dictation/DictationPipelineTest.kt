package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.asr.AsrEngine
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
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
            context: String?,
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
            context: String?,
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
        onText: (String, String, String) -> Unit = { _, _, _ -> },
        onPartialText: (String) -> Unit = {},
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
        onText = onText,
        onPartialText = onPartialText,
    )

    /** A 100 ms s16le frame; the fake engine ignores the samples. */
    private fun feed(pipeline: DictationPipeline, frames: Int) {
        val bytes = ByteArray(3200)
        repeat(frames) { pipeline.onFrames(bytes, bytes.size) }
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
}
