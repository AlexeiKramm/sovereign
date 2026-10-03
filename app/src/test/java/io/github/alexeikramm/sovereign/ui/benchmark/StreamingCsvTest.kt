package io.github.alexeikramm.sovereign.ui.benchmark

import io.github.alexeikramm.sovereign.asr.AsrModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV is what survives a long unattended run, so encoding and re-parsing it must round-trip:
 * the viewer (SB-5) reads results back from it rather than trusting in-memory state.
 */
class StreamingCsvTest {

    private fun sample(
        committed: String = "Hello there, world.",
        raw: String = "Um, hello there world",
        partials: List<Long> = listOf(120L, 340L, 560L),
    ) = StreamingCellResult(
        cell = StreamingCell(
            asrModel = AsrModels.NEMOTRON,
            cleanup = true,
            pauseSeconds = 0.75f,
            healingSentences = 3,
            cold = false,
        ),
        fixtureId = "d03_fillers_selfcorrection",
        audioDurationSec = 12.15f,
        unitCount = 4,
        timeToFirstTextMs = 900L,
        deadAirMs = 500L,
        endpointGatingMs = 300L,
        cleanupLagMs = 200L,
        visibleCadenceMs = listOf(100L, 250L),
        commitCadenceMs = listOf(100L, 250L),
        partialCadenceMs = partials,
        tailLatencyMs = 700L,
        asrActiveMs = 4000L,
        llmActiveMs = 1500L,
        wallMs = 13000L,
        committedText = committed,
        rawText = raw,
    )

    @Test
    fun `header lists every column in order`() {
        val header = StreamingCsv.HEADER.split(',')
        assertEquals(header.size, header.toSet().size)
        assertTrue(header.contains("asr_model_id"))
        assertTrue(header.contains("partial_cadence_ms"))
        assertTrue(header.contains("committed_text"))
        assertTrue(header.contains("raw_text"))
    }

    @Test
    fun `encode then parse round-trips a plain row`() {
        val result = sample()
        val parsed = StreamingCsv.parse("${StreamingCsv.HEADER}\n${StreamingCsv.encode(result)}")

        assertEquals(1, parsed.size)
        val round = parsed.single()
        assertEquals(result.cell.asrModel.id, round.cell.asrModel.id)
        assertTrue(round.cell.cleanup)
        assertEquals(0.75f, round.cell.pauseSeconds, 0.001f)
        assertEquals(3, round.cell.healingSentences)
        assertFalse(round.cell.cold)
        assertEquals(result.fixtureId, round.fixtureId)
        assertEquals(12.15f, round.audioDurationSec, 0.001f)
        assertEquals(result.unitCount, round.unitCount)
        assertEquals(result.timeToFirstTextMs, round.timeToFirstTextMs)
        assertEquals(result.deadAirMs, round.deadAirMs)
        assertEquals(result.partialCadenceMs, round.partialCadenceMs)
        assertEquals(result.visibleCadenceMs, round.visibleCadenceMs)
        assertEquals(result.wallMs, round.wallMs)
        assertEquals(result.committedText, round.committedText)
        assertEquals(result.rawText, round.rawText)
    }

    @Test
    fun `commas, quotes and newlines in recognised text survive the round trip`() {
        val messy = "\"Yes,\" he said —\nthen left \"quickly\"."
        val result = sample(committed = messy, raw = "Yes he said then left quickly")
        val parsed = StreamingCsv.parse("${StreamingCsv.HEADER}\n${StreamingCsv.encode(result)}")

        assertEquals(1, parsed.size)
        assertEquals(messy, parsed.single().committedText)
        assertEquals("Yes he said then left quickly", parsed.single().rawText)
    }

    @Test
    fun `a quoted field containing a comma does not split into extra columns`() {
        val result = sample(committed = "one, two, three", raw = "one two three")
        val parsed = StreamingCsv.parse("${StreamingCsv.HEADER}\n${StreamingCsv.encode(result)}")

        assertEquals("one, two, three", parsed.single().committedText)
    }

    @Test
    fun `parse tolerates a header-only file and blank rows`() {
        assertTrue(StreamingCsv.parse(StreamingCsv.HEADER).isEmpty())
        assertTrue(StreamingCsv.parse("").isEmpty())
        assertTrue(StreamingCsv.parse("${StreamingCsv.HEADER}\n\n").isEmpty())
    }

    @Test
    fun `parse rebuilds several rows in order`() {
        val a = sample(committed = "first", raw = "first")
        val b = sample(committed = "second", raw = "second")
        val lines = "${StreamingCsv.HEADER}\n${StreamingCsv.encode(a)}\n${StreamingCsv.encode(b)}"

        val parsed = StreamingCsv.parse(lines)
        assertEquals(listOf("first", "second"), parsed.map { it.committedText })
    }
}
