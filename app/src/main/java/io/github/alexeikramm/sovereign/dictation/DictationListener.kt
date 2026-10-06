package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.cleanup.CleanupPassTelemetry

/**
 * One consumer's callbacks from the shared [DictationSession] (D-106). The pill and the desktop
 * server each register one with [DictationRouter], which decides which events reach which consumer.
 *
 * Defaults are no-ops so a consumer declares only the callbacks it cares about.
 */
class DictationListener(
    val onText: (committed: String, raw: String, provisional: String) -> Unit = { _, _, _ -> },
    val onCleanupUnavailable: () -> Unit = {},
    val onPartialText: (String) -> Unit = {},
    val onUnitCut: (String) -> Unit = {},
    val onUnitCommitted: (String) -> Unit = {},
    val onCleanupPass: (CleanupPassTelemetry) -> Unit = {},
    /** Raw mic RMS per frame, `[0, 1]`; the pill maps it to its level meter. */
    val onAudioLevel: (Float) -> Unit = {},
    val onModelResidencyChanged: (asrLoaded: Boolean, cleanupLoaded: Boolean) -> Unit = { _, _ -> },
)
