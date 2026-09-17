package fi.ak.smartvoice.ui

import fi.ak.smartvoice.download.DownloadProgress
import fi.ak.smartvoice.download.DownloadRequest
import fi.ak.smartvoice.download.DownloadRunState
import fi.ak.smartvoice.download.DownloadSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue has states that look identical from WorkManager alone: "waiting for Wi-Fi" and "queued
 * behind another download" are both ENQUEUED with a Wi-Fi-only constraint. These tests pin the rule
 * that tells them apart, and the rule that disk beats a stale job.
 */
class DownloadStatusMappingTest {

    private val model = "nemotron-3.5-streaming"
    private val otherModel = "whisper-base"

    private val progress = DownloadProgress(
        overall = 0.25f,
        fileName = "encoder.int8.onnx",
        fileIndex = 0,
        completedFiles = 0,
        totalFiles = 4,
        downloadedBytes = 1_000L,
        totalBytes = 4_000L,
    )

    private fun asr(
        id: String = model,
        state: DownloadRunState,
        progress: DownloadProgress? = null,
        waitingForNetwork: Boolean = false,
        reason: String? = null,
    ) = DownloadSnapshot(
        request = DownloadRequest.Asr(id),
        state = state,
        progress = progress,
        waitingForNetwork = waitingForNetwork,
        failureReason = reason,
    )

    @Test
    fun `nothing on disk and nothing queued is missing`() {
        assertEquals(ModelStatus.Missing, modelStatusFor(false, model, emptyList()))
    }

    @Test
    fun `an installed model is installed`() {
        assertEquals(ModelStatus.Installed, modelStatusFor(true, model, emptyList()))
    }

    @Test
    fun `a running download reports its progress`() {
        val status = modelStatusFor(false, model, listOf(asr(state = DownloadRunState.RUNNING, progress = progress)))

        val downloading = status as ModelStatus.Downloading
        assertEquals(progress, downloading.progress)
        assertFalse(downloading.queued)
        assertFalse(downloading.waitingForWifi)
    }

    @Test
    fun `a request behind a running download is queued, not waiting for wifi`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(
                asr(id = otherModel, state = DownloadRunState.RUNNING, progress = progress),
                asr(id = model, state = DownloadRunState.QUEUED, waitingForNetwork = true),
            ),
        )

        val downloading = status as ModelStatus.Downloading
        assertTrue(downloading.queued)
        assertFalse("another download is why it is waiting, not the network", downloading.waitingForWifi)
        assertEquals("progress has not started, so there is nothing to show", null, downloading.progress)
    }

    @Test
    fun `a lone enqueued request with a Wi-Fi constraint is waiting for wifi`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(asr(state = DownloadRunState.QUEUED, waitingForNetwork = true)),
        )

        val downloading = status as ModelStatus.Downloading
        assertTrue(downloading.waitingForWifi)
        assertFalse(downloading.queued)
    }

    @Test
    fun `a lone enqueued request without a constraint is just queued`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(asr(state = DownloadRunState.QUEUED, waitingForNetwork = false)),
        )

        val downloading = status as ModelStatus.Downloading
        assertFalse("metered downloads do not wait for Wi-Fi", downloading.waitingForWifi)
        assertFalse("nothing else is running, so it is not behind anything", downloading.queued)
    }

    @Test
    fun `an installed model is never reported as a stale failure`() {
        val status = modelStatusFor(
            installed = true,
            modelId = model,
            snapshots = listOf(asr(state = DownloadRunState.FAILED, reason = "HTTP 404")),
        )

        assertEquals(ModelStatus.Installed, status)
    }

    @Test
    fun `a terminal failure carries the downloader's reason`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(asr(state = DownloadRunState.FAILED, reason = "HTTP 404 while fetching tokens.txt")),
        )

        assertEquals(
            ModelStatus.Failed("HTTP 404 while fetching tokens.txt"),
            status,
        )
    }

    @Test
    fun `a cancelled download says how to resume`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(asr(state = DownloadRunState.CANCELLED)),
        )

        val failed = status as ModelStatus.Failed
        assertTrue(failed.message.contains("resume"))
    }

    @Test
    fun `work for another model does not leak into this model's status`() {
        val status = modelStatusFor(
            installed = false,
            modelId = model,
            snapshots = listOf(asr(id = otherModel, state = DownloadRunState.RUNNING, progress = progress)),
        )

        assertEquals(ModelStatus.Missing, status)
    }

    @Test
    fun `asrDownloadStates keys by model and marks what is queued`() {
        val states = asrDownloadStates(
            listOf(
                asr(id = otherModel, state = DownloadRunState.RUNNING, progress = progress),
                asr(id = model, state = DownloadRunState.QUEUED, waitingForNetwork = true),
                asr(id = "whisper-turbo", state = DownloadRunState.SUCCEEDED),
                DownloadSnapshot(
                    request = DownloadRequest.LiteRt("gemma-4-e2b"),
                    state = DownloadRunState.RUNNING,
                    progress = progress,
                    waitingForNetwork = false,
                    failureReason = null,
                ),
            ),
        )

        assertEquals("only live ASR requests are listed", setOf(otherModel, model), states.keys)
        assertEquals(progress, states.getValue(otherModel).progress)
        assertFalse(states.getValue(otherModel).queued)
        assertTrue(states.getValue(model).queued)
        assertFalse(states.getValue(model).waitingForWifi)
    }
}
