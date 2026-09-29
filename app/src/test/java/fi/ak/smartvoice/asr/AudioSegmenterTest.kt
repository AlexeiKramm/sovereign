package fi.ak.smartvoice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmenterTest {

    @Test
    fun `segment with empty samples returns empty list`() {
        val result = AudioSegmenter.segment(FloatArray(0), assetManager = null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `segment with short audio under maxChunkSeconds returns single chunk`() {
        val sampleRate = 16_000
        val tenSecondsSamples = FloatArray(10 * sampleRate) { 0.1f }
        val result = AudioSegmenter.segment(tenSecondsSamples, assetManager = null, sampleRate = sampleRate)

        assertEquals(1, result.size)
        assertEquals(tenSecondsSamples.size, result[0].size)
    }

    @Test
    fun `segment without assetManager falls back to sliceUniform bounded by maxChunkSeconds`() {
        val sampleRate = 16_000
        val sixtySecondsSamples = FloatArray(60 * sampleRate) { it.toFloat() }
        val maxSeconds = 28.0f
        val maxChunkSamples = (maxSeconds * sampleRate).toInt()

        val result = AudioSegmenter.segment(
            samples = sixtySecondsSamples,
            assetManager = null,
            sampleRate = sampleRate,
            maxChunkSeconds = maxSeconds,
        )

        // 60s / 28s = 3 chunks (28s, 28s, 4s)
        assertEquals(3, result.size)
        assertEquals(maxChunkSamples, result[0].size)
        assertEquals(maxChunkSamples, result[1].size)
        assertEquals((4 * sampleRate), result[2].size)

        // Ensure all chunks are <= maxChunkSamples
        assertTrue(result.all { it.size <= maxChunkSamples })

        // Ensure sample continuity across fallback chunks
        var offset = 0
        for (chunk in result) {
            for (i in chunk.indices) {
                assertEquals(offset.toFloat(), chunk[i], 0.0001f)
                offset++
            }
        }
        assertEquals(sixtySecondsSamples.size, offset)
    }

    @Test
    fun `sliceUniform handles exact multiples and remainders`() {
        val samples = FloatArray(100) { it.toFloat() }
        val chunks = AudioSegmenter.sliceUniform(samples, chunkSize = 30)

        // 100 / 30 = 30, 30, 30, 10
        assertEquals(4, chunks.size)
        assertEquals(30, chunks[0].size)
        assertEquals(30, chunks[1].size)
        assertEquals(30, chunks[2].size)
        assertEquals(10, chunks[3].size)
    }

    @Test
    fun `mergeIntoBoundedChunks merges small segments up to minSamples`() {
        val seg1 = FloatArray(500) { 1.0f }
        val seg2 = FloatArray(600) { 2.0f }
        val seg3 = FloatArray(400) { 3.0f }

        val chunks = AudioSegmenter.mergeIntoBoundedChunks(
            segments = listOf(seg1, seg2, seg3),
            minSamples = 1000,
            maxSamples = 3000,
            gapSamples = 50,
        )

        // seg1 (500) + gap (50) + seg2 (600) = 1150 >= minSamples(1000) -> chunk 1
        // seg3 (400) -> chunk 2
        assertEquals(2, chunks.size)
        assertEquals(500 + 50 + 600, chunks[0].size)
        assertEquals(400, chunks[1].size)
    }

    @Test
    fun `mergeIntoBoundedChunks flushes before exceeding maxSamples`() {
        val seg1 = FloatArray(1500) { 1.0f }
        val seg2 = FloatArray(1600) { 2.0f }

        val chunks = AudioSegmenter.mergeIntoBoundedChunks(
            segments = listOf(seg1, seg2),
            minSamples = 500,
            maxSamples = 2000,
            gapSamples = 100,
        )

        // seg1 (1500) >= minSamples(500) -> committed as chunk 1
        // seg2 (1600) -> chunk 2
        assertEquals(2, chunks.size)
        assertEquals(1500, chunks[0].size)
        assertEquals(1600, chunks[1].size)
    }

    @Test
    fun `mergeIntoBoundedChunks clamps individual segments exceeding maxSamples`() {
        val hugeSeg = FloatArray(5000) { 1.0f }

        val chunks = AudioSegmenter.mergeIntoBoundedChunks(
            segments = listOf(hugeSeg),
            minSamples = 1000,
            maxSamples = 3000,
            gapSamples = 100,
        )

        assertEquals(1, chunks.size)
        assertEquals(3000, chunks[0].size)
    }

    @Test
    fun `chunking constants satisfy Whisper 30s hard limit and memory constraints`() {
        assertTrue(
            "AudioSegmenter max chunk duration must be strictly < 30.0s for Whisper Base mel window safety",
            AudioSegmenter.MAX_CHUNK_SECONDS < 30.0f
        )
        assertTrue(
            "AudioSegmenter max chunk duration must be at least 20s for speech context",
            AudioSegmenter.MAX_CHUNK_SECONDS >= 20.0f
        )
        assertTrue(
            "AudioSegmenter min chunk duration must be >= 10s to avoid thrashing ASR / LLM overhead",
            AudioSegmenter.MIN_CHUNK_SECONDS >= 10.0f
        )
    }
}
