package io.github.alexeikramm.sovereign.asr

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live frame queue must never drop audio (D-139). The old queue was bounded at 512 frames
 * (~51 s) and silently discarded the newest microphone frames once the recogniser fell behind; the
 * raw capture is never re-decoded, so a dropped frame was a lost word.
 *
 * A fake recogniser stands in for the native engine: it counts the frames it is actually handed, so
 * "nothing was dropped" and "everything was delivered" are both observable without a device.
 */
class LocalAsrSessionTest {

    private class CountingEngine : AsrEngine {
        val accepted = AtomicInteger(0)
        override fun load(): LocalAsrEngine.Availability = LocalAsrEngine.Availability.Loaded
        override fun startSession(language: String, hotwords: String) {}
        override fun accept(samples: FloatArray, count: Int): String? {
            accepted.incrementAndGet()
            return ""
        }
        override fun isSpeechDetected(): Boolean = false
        override fun isEndpoint(): Boolean = false
        override fun result(): String = ""
        override fun resetSegment() {}
        override fun finish(): String = ""
        override fun abort() {}
    }

    @Test
    fun `frames queued while the recogniser is behind are all delivered, none dropped`() = runBlocking {
        val engine = CountingEngine()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        try {
            val session = LocalAsrSession(engine, dispatcher, scope)
            session.begin()

            // Feed far more than the removed 512-frame cap *before* the recogniser starts draining,
            // which is exactly the "recogniser is behind" case that used to discard audio.
            val frame = ByteArray(3_200) // 1_600 samples @ 16 kHz = 100 ms
            val count = 2_000
            repeat(count) { session.onFrames(frame, frame.size) }

            session.open("en")
            session.finish()

            assertEquals("every queued frame must reach the recogniser", count, engine.accepted.get())
            assertEquals("no frame may be dropped", 0, session.droppedFrames)
            assertEquals(
                "the high-water must be the peak queue depth (the old 512 cap would have dropped)",
                count,
                session.maxBacklogFrames,
            )

            // A new utterance starts from zero: the high-water must not be sticky across utterances.
            session.begin()
            assertEquals(0, session.maxBacklogFrames)
        } finally {
            dispatcher.close()
        }
    }
}
