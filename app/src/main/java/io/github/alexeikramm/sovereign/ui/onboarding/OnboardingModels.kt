package io.github.alexeikramm.sovereign.ui.onboarding

import io.github.alexeikramm.sovereign.asr.AsrModelSpec
import io.github.alexeikramm.sovereign.asr.AsrModels
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModelSpec
import io.github.alexeikramm.sovereign.cleanup.LiteRtLmModels
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
    val llmModel: LiteRtLmModelSpec? = null,
) {
    val totalBytes: Long get() = asrModel.totalBytes + (llmModel?.approximateBytes ?: 0L)
}

object OnboardingModelPresets {

    /** Words appear while you speak — the streaming recogniser plus Gemma cleanup. */
    val STREAMING = OnboardingModelPreset(
        id = "streaming",
        title = "Best for dictation",
        description = "Text appears as you speak, with smart AI cleanup.",
        asrModel = AsrModels.NEMOTRON,
        llmModel = LiteRtLmModels.GEMMA_4_E2B,
    )

    /** Offline high-accuracy recogniser — fastest English transcription with Gemma cleanup. */
    val HIGH_ACCURACY = OnboardingModelPreset(
        id = "high_accuracy",
        title = "High accuracy",
        description = "Fastest offline English recognition with smart AI cleanup.",
        asrModel = AsrModels.PARAKEET_TDT_V3,
        llmModel = LiteRtLmModels.GEMMA_4_E2B,
    )

    /** Minimal download speech model without LLM cleanup. */
    val COMPACT = OnboardingModelPreset(
        id = "compact",
        title = "Low storage & battery",
        description = "Small download (~160 MB). Direct speech-to-text without AI cleanup.",
        asrModel = AsrModels.WHISPER_BASE,
        llmModel = null,
    )

    val ALL: List<OnboardingModelPreset> = listOf(STREAMING, HIGH_ACCURACY, COMPACT)

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
