package fi.ak.smartvoice.cleanup

/**
 * The on-device cleanup catalogue — the list of models the cleanup tier can run (D-029 item 3),
 * after the runtime was decided by the NPU spike (D-030: GenieX `llama_cpp`, Hexagon NPU).
 *
 * This is the app's only hardcoded model list on the GenieX side, and it is hardcoded on purpose:
 * the SDK can list hub models (`ModelManagerWrapper.listHubModels`), but an entry that is wrong — a
 * mistyped repository id, a precision that does not land on the NPU — fails **on the phone**, and
 * there is no adb on the build machine (docs/build.md), so each mistake costs a device round trip.
 * Verifying ids here instead of in the app is the cheap end of that trade.
 */
data class CleanupModel(
    val label: String,
    /** Hugging Face repository id, exactly as the SDK's model manager expects it. */
    val repository: String,
    /** Q4_0 is a *requirement* for the Hexagon NPU on the `llama_cpp` path (D-029), not a default. */
    val precision: String,
    /** The hub's figure, for the size line before anything has been downloaded. */
    val approximateBytes: Long,
    val note: String,
)

object CleanupModels {

    /**
     * Gemma 4 E2B QAT Q4_0 — the release Qualcomm's own NPU path points at, and the only id this
     * project has pulled and run on the phone (probe, 2026-09-12).
     *
     * E4B is the intended second entry and is deliberately absent: its repository id has not been
     * verified against the hub, and guessing here would fail only on the phone.
     */
    val ALL: List<CleanupModel> = listOf(
        CleanupModel(
            label = "Gemma 4 E2B QAT",
            repository = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            precision = "Q4_0",
            approximateBytes = 3_350_000_000L,
            note = "557 tps prefill / 29.4 tps decode on Hexagon NPU, 3.2 s cold load (measured)",
        ),
        CleanupModel(
            label = "Gemma 4 E4B QAT",
            repository = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            precision = "Q4_0",
            approximateBytes = 5_800_000_000L,
            note = "Higher capacity cleanup model for complex sentences and formatting",
        ),
        CleanupModel(
            label = "Qwen 3.5 0.8B",
            repository = "unsloth/Qwen3.5-0.8B-GGUF",
            precision = "Q4_0",
            approximateBytes = 507_000_000L,
            note = "Ultra-compact 0.8B parameter edge model from Qualcomm AI Hub",
        ),
        CleanupModel(
            label = "Qwen 3.5 2B",
            repository = "unsloth/Qwen3.5-2B-GGUF",
            precision = "Q4_0",
            approximateBytes = 1_400_000_000L,
            note = "Up-to-date 2B parameter conversational cleanup model from Qualcomm AI Hub",
        ),
    )

    val DEFAULT: CleanupModel get() = ALL.first()

    /** Unknown or unset means the default: a stale preference must not leave the tier unusable. */
    fun byRepository(id: String?): CleanupModel = ALL.firstOrNull { it.repository == id } ?: DEFAULT

    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "size unknown"
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        else -> "%.0f MB".format(bytes / 1_000_000.0)
    }

    /**
     * The SDK reports progress as per-file byte counts, with its own bean type. This turns that into
     * the one number and one file name the screen renders — and it takes a plain `FileBytes` rather
     * than the SDK's class so the arithmetic stays testable on this machine, off the phone.
     */
    fun progressOf(files: List<FileBytes>): PullProgress {
        val downloaded = files.sumOf { it.downloaded }
        val total = files.sumOf { it.total }
        val overall = if (total > 0L) (downloaded.toDouble() / total).toFloat() else 0f
        // The first file still being fetched is the one to name; if none is, the last one is where
        // the download just finished.
        val current = files.firstOrNull { it.downloaded < it.total } ?: files.lastOrNull()
        return PullProgress(
            overall = overall.coerceIn(0f, 1f),
            downloadedBytes = downloaded,
            totalBytes = total,
            fileName = current?.name.orEmpty(),
            fileIndex = if (current == null) 0 else files.indexOf(current),
            totalFiles = files.size,
        )
    }
}

/**
 * One model the GenieX manager has on disk. `inCatalogue` is false for anything this app does not
 * list — a probe model left over from the spike, most likely — so the screen can say so rather than
 * pretending it does not exist.
 */
data class CachedModel(val id: String, val bytes: Long, val inCatalogue: Boolean)

/** One file's progress, lifted out of the SDK's own bean so the arithmetic is testable off-device. */
data class FileBytes(val name: String, val downloaded: Long, val total: Long)

/** One reading of the SDK's progress events, in the shape the screen renders. */
data class PullProgress(
    val overall: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileName: String,
    val fileIndex: Int,
    val totalFiles: Int,
)
