package fi.ak.smartvoice.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A wizard preset is two downloads shown as one bar, so the arithmetic that turns each stage's
 * local progress into progress across the whole plan is worth pinning down without a worker.
 */
class DownloadPlanTest {

    private val speech = DownloadStage(
        label = PresetStage.SPEECH,
        directory = File("/tmp/asr"),
        files = listOf(RemoteFile("encoder.onnx", bytes = 700, sha256 = "a", url = "http://asr")),
    )
    private val cleanup = DownloadStage(
        label = PresetStage.CLEANUP,
        directory = File("/tmp/cleanup"),
        files = listOf(RemoteFile("model.litertlm", bytes = 300, sha256 = "b", url = "http://llm")),
    )
    private val plan = DownloadPlan(label = "Best for dictation", stages = listOf(speech, cleanup))

    @Test
    fun `the plan total is the sum of every stage`() {
        assertEquals(1_000L, plan.totalBytes)
    }

    @Test
    fun `the first stage's progress is weighted against the whole plan`() {
        val local = progress(overall = 0.5f, downloadedBytes = 350, totalBytes = 700)

        val global = local.acrossPlan(speech, completedBytes = 0L, planTotalBytes = plan.totalBytes)

        assertEquals(0.35f, global.overall, 1e-6f)
        assertEquals(350L, global.downloadedBytes)
        assertEquals(1_000L, global.totalBytes)
        assertEquals(PresetStage.SPEECH, global.stage)
    }

    @Test
    fun `the second stage adds the first stage's completed bytes`() {
        val local = progress(overall = 0.5f, downloadedBytes = 150, totalBytes = 300)

        val global = local.acrossPlan(cleanup, completedBytes = 700L, planTotalBytes = plan.totalBytes)

        assertEquals(0.85f, global.overall, 1e-6f)
        assertEquals(850L, global.downloadedBytes)
        assertEquals(PresetStage.CLEANUP, global.stage)
    }

    @Test
    fun `a single-stage download carries no stage label`() {
        val single = DownloadStage(
            label = null,
            directory = File("/tmp/asr"),
            files = listOf(RemoteFile("encoder.onnx", bytes = 700, sha256 = "a", url = "http://asr")),
        )

        val global = progress(overall = 1f, downloadedBytes = 700, totalBytes = 700)
            .acrossPlan(single, completedBytes = 0L, planTotalBytes = single.totalBytes)

        assertNull("a single-request download has no half to name", global.stage)
        assertEquals(1f, global.overall, 1e-6f)
    }

    private fun progress(overall: Float, downloadedBytes: Long, totalBytes: Long) = DownloadProgress(
        overall = overall,
        fileName = "file",
        fileIndex = 0,
        completedFiles = 0,
        totalFiles = 1,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )
}
