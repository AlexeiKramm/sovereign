package fi.ak.smartvoice.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioFixturesTest {

    private val fixturesDir: File by lazy {
        val candidates = listOf(
            File("fixtures/en"),
            File("../fixtures/en"),
            File("../../fixtures/en"),
        )
        candidates.firstOrNull { it.exists() }
            ?: error("fixtures/en directory not found in candidate paths: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun `fixtures directory contains all required English files`() {
        val requiredFiles = listOf(
            "short.wav", "short.txt",
            "medium.wav", "medium.txt",
            "long.wav", "long.txt",
            "metadata.json",
        )
        for (name in requiredFiles) {
            val f = File(fixturesDir, name)
            assertTrue("Expected fixture file missing: ${f.absolutePath}", f.exists())
            assertTrue("Fixture file is empty: ${f.absolutePath}", f.length() > 0)
        }
    }

    @Test
    fun `short fixture meets audio and transcript specifications`() {
        val wavFile = File(fixturesDir, "short.wav")
        val txtFile = File(fixturesDir, "short.txt")

        val audio = WavReader.read(wavFile.readBytes())
        assertEquals(16000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertTrue("Short duration expected between 3.0s and 6.0s, was ${audio.durationSeconds}s",
            audio.durationSeconds in 3.0f..6.0f)

        val transcript = txtFile.readText(Charsets.UTF_8).trim()
        assertEquals(
            "Yet these thoughts affected Hester Prynne less with hope than apprehension.",
            transcript
        )
    }

    @Test
    fun `medium fixture meets audio and transcript specifications`() {
        val wavFile = File(fixturesDir, "medium.wav")
        val txtFile = File(fixturesDir, "medium.txt")

        val audio = WavReader.read(wavFile.readBytes())
        assertEquals(16000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertTrue("Medium duration expected between 9.0s and 14.0s, was ${audio.durationSeconds}s",
            audio.durationSeconds in 9.0f..14.0f)

        val transcript = txtFile.readText(Charsets.UTF_8).trim()
        assertEquals(
            "They were now illuminated by the morning radiance of a young child's disposition, but later in the day of earthly existence might be prolific of the storm and whirlwind.",
            transcript
        )
    }

    @Test
    fun `long fixture meets audio and transcript specifications`() {
        val wavFile = File(fixturesDir, "long.wav")
        val txtFile = File(fixturesDir, "long.txt")

        val audio = WavReader.read(wavFile.readBytes())
        assertEquals(16000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertTrue("Long duration expected between 18.0s and 25.0s, was ${audio.durationSeconds}s",
            audio.durationSeconds in 18.0f..25.0f)

        val transcript = txtFile.readText(Charsets.UTF_8).trim()
        assertEquals(
            "Beholding it, Hester was constrained to rush towards the child,—to pursue the little elf in the flight which she invariably began,—to snatch her to her bosom, with a close pressure and earnest kisses,—not so much from overflowing love, as to assure herself that Pearl was flesh and blood, and not utterly delusive.",
            transcript
        )
    }

    @Test
    fun `metadata json contains complete provenance and matching transcripts`() {
        val metaFile = File(fixturesDir, "metadata.json")
        val element = Json.parseToJsonElement(metaFile.readText(Charsets.UTF_8)).jsonObject

        assertEquals("LibriSpeech test-clean (OpenSLR SLR12)", element["dataset"]?.jsonPrimitive?.content)
        assertEquals("en", element["language"]?.jsonPrimitive?.content)
        assertEquals("1221", element["speaker_id"]?.jsonPrimitive?.content)
        assertEquals("135766", element["chapter_id"]?.jsonPrimitive?.content)

        val fixtures = element["fixtures"]?.jsonObject ?: error("missing fixtures")
        assertTrue("short" in fixtures)
        assertTrue("medium" in fixtures)
        assertTrue("long" in fixtures)

        for (tier in listOf("short", "medium", "long")) {
            val tierObj = fixtures[tier]?.jsonObject ?: error("missing tier $tier")
            val transcriptFile = tierObj["transcript_file"]?.jsonPrimitive?.content ?: error("missing transcript_file")
            val expectedTxt = File(fixturesDir, transcriptFile).readText(Charsets.UTF_8).trim()
            assertEquals("Ground truth in metadata.json must match $tier.txt", expectedTxt, tierObj["ground_truth"]?.jsonPrimitive?.content)
        }
    }
}
