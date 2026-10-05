package io.github.alexeikramm.sovereign.ui.cleanuplab

import io.github.alexeikramm.sovereign.cleanup.ConversationRebuildPolicy
import kotlinx.serialization.Serializable

/**
 * The Cleanup Lab's data model — the phone-side schema is deliberately small and serializable, because
 * the analysis happens off-device. A **unit set** is the cached output of one ASR pass; a **run** is one
 * cleanup configuration replayed over it.
 */

/** One pause-delimited ASR segment, with the audio position it ended at (best effort, for context). */
@Serializable
data class LabUnit(
    val index: Int,
    val text: String,
    val startSeconds: Float = 0f,
    val endSeconds: Float = 0f,
)

/** Phase A's product: the ordered units for one (fixture, ASR model, pause) triple, cached as JSON. */
@Serializable
data class LabUnitSet(
    val fixtureId: String,
    val asrModelId: String,
    val pauseSeconds: Float,
    val units: List<LabUnit>,
) {
    val rawText: String get() = units.joinToString(" ") { it.text }
}

/**
 * One cell of the matrix: which fixture and recogniser produced the units, and how the cleanup stage is
 * configured. [rules] is the resolved profile text, captured so a run is reproducible after the profile
 * is edited.
 */
@Serializable
data class LabConfig(
    val fixtureId: String,
    val fixtureName: String,
    val asrModelId: String,
    val pauseSeconds: Float,
    val llmModelId: String,
    val llmLabel: String,
    val maxTurns: Int,
    val healingSentences: Int,
    val profileId: String,
    val profileName: String,
    val rules: String,
) {
    val label: String
        get() = buildString {
            append(llmLabel)
            append(" · ")
            append(if (maxTurns == ConversationRebuildPolicy.NO_TURN_LIMIT) "turn∞" else "turn$maxTurns")
            append(" · heal$healingSentences")
            append(" · ")
            append(profileName)
        }
}

/** What one unit's pass produced, plus where in the conversation it ran. */
data class LabUnitResult(
    val index: Int,
    /** What the speaker said for this unit (no seam overlap): the reference for filler removal. */
    val rawUnit: String,
    /**
     * The text actually handed to the model — the raw unit plus any seam overlap. Per-pass diffs use
     * this, not [rawUnit], or the overlap would read as invented content.
     */
    val modelInput: String = rawUnit,
    val cleanedUnit: String,
    val cleanedOk: Boolean,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val turnsInConversation: Int,
    val rebuiltAfter: Boolean,
    val rebuildReason: String?,
    val audioStartSeconds: Float,
    val audioEndSeconds: Float,
    val activeMs: Long,
    val ttftMs: Double,
    val decodeTimeMs: Double,
    val promptTokens: Long,
    val generatedTokens: Long,
)

/** The end-to-end outcome of one configuration: the joined text, the metrics, and the position curve. */
data class LabRunResult(
    val config: LabConfig,
    val units: List<LabUnitResult>,
    val rawText: String,
    val cleanedText: String,
    val wallMs: Long,
    val asrActiveMs: Long,
    val llmActiveMs: Long,
    val metrics: LabMetrics,
    val positions: List<LabPositionPoint>,
)
