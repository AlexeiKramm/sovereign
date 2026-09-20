package fi.ak.smartvoice.asr

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
        assertEquals(3, catalog.size)
        val ids = catalog.map { it.id }
        assertTrue("contains nemotron 560ms", ids.contains("nemotron-3.5-streaming"))
        assertTrue("contains parakeet-tdt-v3", ids.contains("parakeet-tdt-v3"))
        assertTrue("contains whisper-base", ids.contains("whisper-base"))

        catalog.forEach { model ->
            assertTrue("${model.id} has files", model.files.isNotEmpty())
            assertTrue("${model.id} totalBytes > 0", model.totalBytes > 0)
            model.files.forEach { file ->
                assertEquals("${model.id} file ${file.name} sha256 length", 64, file.sha256.length)
            }
        }
    }

    @Test
    fun `byId finds each model and falls back to default`() {
        assertEquals("nemotron-3.5-streaming-320ms", AsrModels.byId("nemotron-3.5-streaming-320ms").id)
        assertEquals("whisper-turbo", AsrModels.byId("whisper-turbo").id)
        assertEquals("parakeet-tdt-v3", AsrModels.byId("parakeet-tdt-v3").id)
        assertEquals(AsrModels.DEFAULT_MODEL_ID, AsrModels.byId("non-existent-id").id)
    }
}
