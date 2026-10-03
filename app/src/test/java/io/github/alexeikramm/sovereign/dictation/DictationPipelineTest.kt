package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.asr.AsrEngine
import io.github.alexeikramm.sovereign.asr.ListenerTimings
import io.github.alexeikramm.sovereign.asr.LocalAsrEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupEngine
import io.github.alexeikramm.sovereign.cleanup.CleanupPass
import io.github.alexeikramm.sovereign.cleanup.CleanupPrompt
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

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun startSession(variant: CleanupPrompt.Variant, customRules: String?) {}

        override fun closeSession() {}

        override suspend fun clean(
            unit: String,
            context: String?,
            variant: CleanupPrompt.Variant,
            customRules: String?,
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

    private fun pipeline(
        engine: AsrEngine,
        cleaner: FakeCleanupEngine,
        onText: (String, String) -> Unit = { _, _ -> },
        onPartialText: (String) -> Unit = {},
    ): DictationPipeline = DictationPipeline(
        asrEngine = engine,
        asrDispatcher = Dispatchers.Unconfined,
        cleanupEngine = cleaner,
        cleanupDispatcher = Dispatchers.Unconfined,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        mainDispatcher = Dispatchers.Unconfined,
        timings = { ListenerTimings() },
        cleanupVariant = { CleanupPrompt.Variant.SMART_CLEAN },
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
    fun `provisional text is exposed live and cleared once the unit settles`() = runBlocking {
        val engine = FakeAsrEngine(listOf("Hello."))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        // Before any frame the recogniser has produced nothing provisional.
        assertEquals("", pipeline.provisionalText)

        feed(pipeline, 1)
        // Once the unit cuts, the provisional text is promoted and the live field is cleared, so a
        // later read never re-shows text that is now part of the committed output.
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

    @Test
    fun `a re-emitted partial from the just-cut segment does not resurrect the committed text`() = runBlocking {
        // The real recogniser keeps returning the cut segment's text until the next segment's words
        // arrive; the session reports it as a partial. That must not re-show it as a grey tail.
        val engine = StickyTextAsrEngine(listOf("Hello."))
        val pipeline = pipeline(engine, FakeCleanupEngine())

        pipeline.begin(fresh = true, cleanupEnabled = false)
        pipeline.beginSession()
        pipeline.open("en")
        feed(pipeline, 3) // one cut, then two frames of the leftover re-emission

        assertEquals("Hello.", pipeline.committedText)
        assertEquals("", pipeline.provisionalText)
    }
}
