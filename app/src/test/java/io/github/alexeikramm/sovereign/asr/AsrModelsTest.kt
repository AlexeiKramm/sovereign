package io.github.alexeikramm.sovereign.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the artifact manifest. These are the values a wrong edit would silently corrupt: a sha256
 * with a typo makes every download fail verification, and a wrong size makes a truncated download
 * look complete.
 *
 * Everything here goes through [AsrModels.NEMOTRON] rather than an `AsrModels` shortcut, because
 * the per-model behaviour — streaming and offline alike — is the spec's, and a test that reads a
 * default hides that.
 */
class AsrModelsTest {

    private val nemotron = AsrModels.NEMOTRON

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the four artifact files are exactly the ones the runtime loads`() {
        assertEquals(
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            nemotron.files.map { it.name },
        )
    }

    @Test
    fun `every digest is a full lowercase sha256`() {
        nemotron.files.forEach { file ->
            assertEquals("${file.name} digest length", 64, file.sha256.length)
            assertTrue("${file.name} digest is hex", file.sha256.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `the encoder dominates the download, which is why it is first`() {
        val encoder = nemotron.files.first().bytes
        assertTrue(encoder > nemotron.totalBytes * 9 / 10)
        assertEquals(nemotron.files.sumOf { it.bytes }, nemotron.totalBytes)
    }

    @Test
    fun `the download url is pinned to a revision and served over https`() {
        val url = nemotron.downloadUrl(nemotron.files.first())
        assertTrue(url.startsWith("https://huggingface.co/${nemotron.repo}/resolve/${nemotron.revision}/"))
        assertFalse("must not track a moving branch", url.contains("/resolve/main/"))
    }

    @Test
    fun `the model lives in the app's private files dir`() {
        assertEquals(
            java.io.File(java.io.File(temp.root, "models"), nemotron.directoryName).absolutePath,
            nemotron.directory(temp.root).absolutePath,
        )
    }

    @Test
    fun `a missing directory means every file is incomplete`() {
        assertEquals(nemotron.files.size, nemotron.incomplete(temp.root.resolve("nothing-here")).size)
        assertFalse(nemotron.isInstalled(temp.root.resolve("nothing-here")))
    }

    @Test
    fun `a file of the wrong length does not count as installed`() {
        val directory = temp.newFolder("model")
        nemotron.files.forEach { file -> directory.resolve(file.name).writeText("too short") }

        val incomplete = nemotron.incomplete(directory)
        assertEquals(nemotron.files.size, incomplete.size)
        assertFalse(nemotron.isInstalled(directory))
    }

    @Test
    fun `remoteFiles resolves each file to a pinned url and its digest`() {
        val remote = nemotron.remoteFiles()

        assertEquals(nemotron.files.size, remote.size)
        nemotron.files.zip(remote).forEach { (spec, download) ->
            assertEquals(spec.name, download.name)
            assertEquals(spec.bytes, download.bytes)
            assertEquals(spec.sha256, download.sha256)
            assertEquals(nemotron.downloadUrl(spec), download.url)
        }
    }

    @Test
    fun `catalog contains all supported streaming and offline models`() {
        val catalog = AsrModels.CATALOG
        assertEquals(5, catalog.size)
        val ids = catalog.map { it.id }
        assertTrue("contains nemotron 560ms", ids.contains("nemotron-3.5-streaming"))
        // Re-listed by D-144: chosen for live-text responsiveness, which the D-090 matrix did not measure.
        assertTrue("contains nemotron 320ms", ids.contains("nemotron-3.5-streaming-320ms"))
        // Added by D-145 as the finest streaming variant.
        assertTrue("contains nemotron 160ms", ids.contains("nemotron-3.5-streaming-160ms"))
        assertTrue("contains parakeet-tdt-v3", ids.contains("parakeet-tdt-v3"))
        assertTrue("contains whisper-base", ids.contains("whisper-base"))

        catalog.forEach { model ->
            assertTrue("${model.id} has files", model.files.isNotEmpty())
            assertTrue("${model.id} totalBytes > 0", model.totalBytes > 0)
            assertTrue("${model.id} has a short label", model.shortLabel.isNotBlank())
            model.files.forEach { file ->
                assertEquals("${model.id} file ${file.name} sha256 length", 64, file.sha256.length)
            }
        }
        assertEquals("short labels are distinct for the chips", catalog.size, catalog.map { it.shortLabel }.toSet().size)
    }

    @Test
    fun `byId finds catalog and legacy models and falls back to default`() {
        // 320 ms is listed again (D-144) and still resolves; the legacy offline models resolve too.
        assertEquals("nemotron-3.5-streaming-320ms", AsrModels.byId("nemotron-3.5-streaming-320ms").id)
        assertEquals("nemotron-3.5-streaming-160ms", AsrModels.byId("nemotron-3.5-streaming-160ms").id)
        assertTrue("320ms is listed (D-144)", "nemotron-3.5-streaming-320ms" in AsrModels.CATALOG.map { it.id })
        assertEquals("whisper-turbo", AsrModels.byId("whisper-turbo").id)
        assertEquals("whisper-small", AsrModels.byId("whisper-small").id)
        assertEquals("parakeet-tdt-v3", AsrModels.byId("parakeet-tdt-v3").id)
        assertEquals(AsrModels.DEFAULT_MODEL_ID, AsrModels.byId("non-existent-id").id)
    }

    @Test
    fun `getTierTitle resolves ids, labels and partial names`() {
        // D-145: the three streaming variants are one family and share one title; the ms codenames are gone.
        assertEquals("Most Responsive", AsrModels.getTierTitle("nemotron-3.5-streaming"))
        assertEquals("Most Responsive", AsrModels.getTierTitle("nemotron-3.5-streaming-320ms"))
        assertEquals("Most Responsive", AsrModels.getTierTitle("nemotron-3.5-streaming-160ms"))
        assertEquals("Most Responsive", AsrModels.getTierTitle("Nemotron 3.5 (320ms)"))
        assertEquals("Highest Accuracy", AsrModels.getTierTitle("parakeet-tdt-v3"))
        assertEquals("Lightweight", AsrModels.getTierTitle("whisper-base"))
        assertEquals("High Performance", AsrModels.getTierTitle("whisper-turbo"))
        assertEquals("Most Responsive", AsrModels.getTierTitle("Nemotron"))
        assertEquals("Most Responsive", AsrModels.getTierTitle(null))
        assertEquals("something-else", AsrModels.getTierTitle("something-else"))
    }

    @Test
    fun `the streaming variants differ only in the encoder`() {
        val variants = AsrModels.NEMOTRON_VARIANTS
        assertEquals(3, variants.size)
        assertEquals(listOf(560, 320, 160), variants.map { it.chunkMs })

        // decoder/joiner/tokens are byte-identical across the family; only the encoder differs — which
        // is exactly why they can share one card.
        listOf("decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").forEach { name ->
            val digests = variants.map { spec -> spec.files.first { it.name == name }.sha256 }.toSet()
            assertEquals("$name must be identical across variants", 1, digests.size)
        }
        val encoders = variants.map { spec -> spec.files.first { it.name == "encoder.int8.onnx" }.sha256 }.toSet()
        assertEquals("each variant ships its own encoder", variants.size, encoders.size)
    }

    @Test
    fun `the 160 ms variant is pinned to its own revision and encoder`() {
        val spec = AsrModels.byId("nemotron-3.5-streaming-160ms")
        assertEquals(160, spec.chunkMs)
        assertEquals("nemotron-3.5-streaming-0.6b-160ms-int8", spec.directoryName)
        val encoder = spec.files.first { it.name == "encoder.int8.onnx" }
        assertEquals(657_601_518L, encoder.bytes)
        assertEquals(
            "e1b39e5e16bef578a54ed2fba5f031438e000cc36c3ea2ca49d55699d5baebd4",
            encoder.sha256,
        )
    }

    @Test
    fun `families put the streaming variants in one card and every other model alone`() {
        val families = AsrModels.FAMILIES
        assertEquals(3, families.size)
        assertEquals(AsrModels.NEMOTRON_VARIANTS.map { it.id }, families[0].map { it.id })
        assertEquals(listOf("parakeet-tdt-v3"), families[1].map { it.id })
        assertEquals(listOf("whisper-base"), families[2].map { it.id })
        // Every catalog model appears exactly once across the families.
        assertEquals(AsrModels.CATALOG.map { it.id }.toSet(), families.flatten().map { it.id }.toSet())
    }

    @Test
    fun `the default variant is the balanced 320 ms chunk and stays in the catalog`() {
        assertEquals("nemotron-3.5-streaming-320ms", AsrModels.DEFAULT_VARIANT_ID)
        assertTrue(AsrModels.DEFAULT_VARIANT_ID in AsrModels.CATALOG.map { it.id })
        // The family anchor and the default variant are deliberately different things (D-145).
        assertTrue(AsrModels.DEFAULT_MODEL_ID != AsrModels.DEFAULT_VARIANT_ID)
    }
}
