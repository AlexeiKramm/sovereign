package io.github.alexeikramm.sovereign.ui.benchmark

import io.github.alexeikramm.sovereign.audio.WavReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictationFixturesTest {

    private val fixturesDir: File by lazy {
        val candidates = listOf(
            File("fixtures/en/dictation"),
            File("../fixtures/en/dictation"),
            File("../../fixtures/en/dictation"),
        )
        candidates.firstOrNull { it.exists() }
            ?: error("fixtures/en/dictation not found in candidate paths: ${candidates.map { it.absolutePath }}")
    }

    private val metadataFixtures by lazy {
        val root = Json.parseToJsonElement(File(fixturesDir, "metadata.json").readText(Charsets.UTF_8)).jsonObject
        root["fixtures"]?.jsonObject ?: error("metadata.json has no fixtures object")
    }

    @Test
    fun `every listed fixture is a 16 kHz mono wav`() {
        for (fixture in DictationFixtures.FIXTURES) {
            val file = File(fixturesDir, "${fixture.id}.wav")
            assertTrue("Expected fixture file missing: ${file.absolutePath}", file.isFile)
            assertTrue("Fixture file is empty: ${file.absolutePath}", file.length() > 0)
            val audio = WavReader.read(file.readBytes())
            assertEquals("${fixture.id} sample rate", 16000, audio.sampleRate)
            assertEquals("${fixture.id} channels", 1, audio.channels)
        }
    }

    @Test
    fun `metadata agrees with the kotlin fixture list`() {
        assertEquals(DictationFixtures.FIXTURES.size, metadataFixtures.size)
        for (fixture in DictationFixtures.FIXTURES) {
            val entry = metadataFixtures[fixture.id]?.jsonObject ?: error("metadata missing ${fixture.id}")
            assertEquals("${fixture.id} file", "${fixture.id}.wav", entry["file"]?.jsonPrimitive?.content)
            assertEquals("${fixture.id} asset path", "en/dictation/${fixture.id}.wav", fixture.assetWavPath)
            val metaDuration = entry["duration_seconds"]?.jsonPrimitive?.content?.toDouble()
                ?: error("${fixture.id} duration_seconds missing")
            assertEquals("${fixture.id} duration", fixture.durationSeconds.toDouble(), metaDuration, 0.02)
            assertEquals(
                "${fixture.id} pause density",
                fixture.pauseDensity,
                entry["pause_density"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `recorded durations match the declared durations`() {
        for (fixture in DictationFixtures.FIXTURES) {
            val audio = WavReader.read(File(fixturesDir, "${fixture.id}.wav").readBytes())
            assertEquals(
                "${fixture.id} recorded duration",
                fixture.durationSeconds.toDouble(),
                audio.durationSeconds.toDouble(),
                0.05,
            )
        }
    }

    @Test
    fun `the set covers both pause densities and every content feature`() {
        val densities = DictationFixtures.FIXTURES.map { it.pauseDensity }.toSet()
        assertTrue("needs a continuous take to expose dead air", "continuous" in densities)
        assertTrue("needs pausing takes", "pausing" in densities)

        val features = DictationFixtures.FIXTURES.flatMap { it.features }.toSet()
        for (feature in listOf("fillers", "self-correction", "list", "numbers", "dates", "proper-nouns")) {
            assertTrue("no fixture exercises $feature", feature in features)
        }
    }

    @Test
    fun `the continuous and pausing long takes share one script`() {
        val continuous = metadataFixtures["d07_continuous"]?.jsonObject?.get("script")?.jsonPrimitive?.content
        val pausing = metadataFixtures["d08_pausing"]?.jsonObject?.get("script")?.jsonPrimitive?.content
        assertTrue("d07 script missing", !continuous.isNullOrBlank())
        assertEquals("d07 and d08 must share a script", continuous, pausing)
    }
}
