package fi.ak.smartvoice.ui.onboarding

import fi.ak.smartvoice.asr.AsrModelSpec
import fi.ak.smartvoice.asr.AsrModels
import fi.ak.smartvoice.cleanup.LiteRtLmModelSpec
import fi.ak.smartvoice.cleanup.LiteRtLmModels
import java.util.Locale

/**
 * A "get started" bundle: one speech model and one cleanup model that a first-time
 * user can download with a single tap. The wizard offers two of these rather than
 * the whole catalog, because the catalog is a decision for Settings, not for
 * someone who has never dictated yet.
 */
data class OnboardingModelPreset(
    val id: String,
    val title: String,
    val description: String,
    val asrModel: AsrModelSpec,
    val llmModel: LiteRtLmModelSpec,
) {
    val totalBytes: Long get() = asrModel.totalBytes + llmModel.approximateBytes
}

object OnboardingModelPresets {

    /** Words appear while you speak — the streaming recogniser plus Gemma cleanup. */
    val STREAMING = OnboardingModelPreset(
        id = "streaming",
        title = "Best for dictation",
        description = "Text appears as you speak, with the lowest latency.",
        asrModel = AsrModels.NEMOTRON_320MS,
        llmModel = LiteRtLmModels.GEMMA_4_E2B,
    )

    /** A smaller speech model; transcription lands when you stop talking. */
    val COMPACT = OnboardingModelPreset(
        id = "compact",
        title = "Compact speech model",
        description = "Smaller download. Text appears when you pause.",
        asrModel = AsrModels.WHISPER_BASE,
        llmModel = LiteRtLmModels.GEMMA_4_E2B,
    )

    val ALL: List<OnboardingModelPreset> = listOf(STREAMING, COMPACT)

    fun byId(id: String?): OnboardingModelPreset = ALL.firstOrNull { it.id == id } ?: STREAMING
}

/**
 * "682 MB", "3.3 GB". Pinned to [Locale.US] on purpose: the UI is English-only, so a
 * locale decimal comma ("3,3 GB") would read as a typo next to English labels.
 */
fun formatModelSize(bytes: Long): String {
    val megabytes = bytes / 1_000_000.0
    return if (megabytes >= 1000.0) {
        String.format(Locale.US, "%.1f GB", megabytes / 1000.0)
    } else {
        String.format(Locale.US, "%.0f MB", megabytes)
    }
}
