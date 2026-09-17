package fi.ak.smartvoice.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * Drives [ArtifactDownloader] against a real HTTP server on localhost, so the parts that matter —
 * resume with a `Range`, a server that ignores the range, digest verification, cancel, and which
 * failures are worth retrying — are exercised for real rather than mocked.
 */
class ArtifactDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val port: Int get() = server.address.port
    private val requestedRanges = mutableListOf<String>()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `downloads a file and verifies its digest`() {
        val body = "hello".toByteArray()
        serve(body)
        val directory = temp.newFolder("model")

        val outcome = ArtifactDownloader(directory, listOf(remoteFile(body))).download()

        assertTrue(outcome is DownloadOutcome.Complete)
        assertEquals("hello", File(directory, "tokens.txt").readText())
        assertFalse("the .part must be gone after the rename", File(directory, "tokens.txt.part").exists())
    }

    @Test
    fun `resumes from the part file with a Range request`() {
        val body = ByteArray(64) { it.toByte() }
        serve(body)
        val directory = temp.newFolder("model")
        File(directory, "tokens.txt.part").writeBytes(body.copyOfRange(0, 40))

        val outcome = ArtifactDownloader(directory, listOf(remoteFile(body))).download()

        assertTrue(outcome is DownloadOutcome.Complete)
        assertEquals(listOf("bytes=40-"), requestedRanges)
        assertArrayEquals(body, File(directory, "tokens.txt").readBytes())
    }

    @Test
    fun `a server that ignores Range is restarted, not appended to`() {
        val body = ByteArray(64) { it.toByte() }
        serve(body, honourRange = false)
        val directory = temp.newFolder("model")
        File(directory, "tokens.txt.part").writeBytes(body.copyOfRange(0, 40))

        val outcome = ArtifactDownloader(directory, listOf(remoteFile(body))).download()

        assertTrue(outcome is DownloadOutcome.Complete)
        assertArrayEquals(body, File(directory, "tokens.txt").readBytes())
    }

    @Test
    fun `a file already on disk is not fetched again`() {
        val body = "hello".toByteArray()
        // No context is registered, so any request would 404. Completing proves nothing was asked.
        val directory = temp.newFolder("model")
        File(directory, "tokens.txt").writeBytes(body)

        val outcome = ArtifactDownloader(directory, listOf(remoteFile(body))).download()

        assertTrue(outcome is DownloadOutcome.Complete)
    }

    @Test
    fun `a digest mismatch deletes the bad part and is terminal`() {
        val body = "hello".toByteArray()
        serve(body)
        val directory = temp.newFolder("model")
        val wrong = RemoteFile(
            name = "tokens.txt",
            bytes = body.size.toLong(),
            sha256 = "ab".repeat(32),
            url = artifactUrl(),
        )

        val outcome = ArtifactDownloader(directory, listOf(wrong)).download()

        val failed = outcome as DownloadOutcome.Failed
        assertFalse("retrying a changed artifact cannot help", failed.retryable)
        assertFalse("the bad bytes must not be kept", File(directory, "tokens.txt.part").exists())
        assertFalse(File(directory, "tokens.txt").exists())
    }

    @Test
    fun `a short stream is retryable and keeps the part`() {
        val body = ByteArray(10) { it.toByte() }
        serve(body)
        val directory = temp.newFolder("model")
        // The spec expects 20 bytes; the server only has 10.
        val short = RemoteFile(
            name = "tokens.txt",
            bytes = 20L,
            sha256 = digest(body),
            url = artifactUrl(),
        )

        val outcome = ArtifactDownloader(directory, listOf(short)).download()

        val failed = outcome as DownloadOutcome.Failed
        assertTrue("a dropped stream is worth another attempt", failed.retryable)
        assertTrue("the part must survive for the resume", File(directory, "tokens.txt.part").exists())
    }

    @Test
    fun `cancelling keeps the part and reports what arrived`() {
        val body = ByteArray(4096) { it.toByte() }
        serve(body)
        val directory = temp.newFolder("model")

        val outcome = ArtifactDownloader(directory, listOf(remoteFile(body)))
            .download(isCancelled = { true })

        val cancelled = outcome as DownloadOutcome.Cancelled
        val part = File(directory, "tokens.txt.part")
        assertTrue(part.exists())
        assertEquals(cancelled.downloadedBytes, part.length())
    }

    @Test
    fun `a 404 is terminal and a 503 is retryable`() {
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/busy") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }

        val missing = ArtifactDownloader(temp.newFolder("a"), listOf(bareFile("missing"))).download()
        val busy = ArtifactDownloader(temp.newFolder("b"), listOf(bareFile("busy"))).download()

        assertFalse((missing as DownloadOutcome.Failed).retryable)
        assertTrue((busy as DownloadOutcome.Failed).retryable)
    }

    @Test
    fun `a refused connection is retryable and never throws`() {
        val directory = temp.newFolder("model")
        val unreachable = RemoteFile("tokens.txt", 1L, "00".repeat(32), "http://127.0.0.1:1/artifact")

        val outcome = ArtifactDownloader(directory, listOf(unreachable)).download()

        assertTrue("a network drop must be a value, not an exception", (outcome as DownloadOutcome.Failed).retryable)
    }

    @Test
    fun `progress is reported while streaming and ends at one`() {
        val body = ByteArray(4096) { it.toByte() }
        serve(body)
        val directory = temp.newFolder("model")
        val seen = mutableListOf<Float>()

        val outcome = ArtifactDownloader(
            directory = directory,
            files = listOf(remoteFile(body)),
            onProgress = { seen += it.overall },
            progressStepBytes = 1024,
        ).download()

        assertTrue(outcome is DownloadOutcome.Complete)
        assertTrue("progress is reported during the transfer", seen.size > 1)
        assertEquals("the last report is the finished artifact", 1f, seen.last(), 1e-6f)
    }

    // ------------------------------------------------------------------ helpers

    private fun artifactUrl(): String = "http://127.0.0.1:$port/artifact"

    private fun bareFile(path: String): RemoteFile =
        RemoteFile("file.bin", 1L, "00".repeat(32), "http://127.0.0.1:$port/$path")

    private fun remoteFile(body: ByteArray): RemoteFile =
        RemoteFile("tokens.txt", body.size.toLong(), digest(body), artifactUrl())

    private fun serve(body: ByteArray, honourRange: Boolean = true) {
        server.createContext("/artifact") { exchange -> handle(exchange, body, honourRange) }
    }

    private fun handle(exchange: HttpExchange, body: ByteArray, honourRange: Boolean) {
        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null) requestedRanges += range
        val start = if (honourRange && range != null) {
            range.removePrefix("bytes=").removeSuffix("-").toInt()
        } else {
            0
        }
        if (start > 0) {
            val slice = body.copyOfRange(start, body.size)
            exchange.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        } else {
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    /** An independent oracle: the test computes the digest itself rather than reusing production code. */
    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
