package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.cleanup.CleanupPassTelemetry

/**
 * Fans the one shared [DictationSession]'s callbacks out to its consumers (D-106).
 *
 * **Text callbacks are capture-scoped.** They go only to the listener whose owner currently holds the
 * [SessionArbiter], so a desktop capture never streams into the pill's UI and a pill capture never
 * streams to the desktop. The arbiter already serialises capture, so "who owns the utterance" has a
 * single answer at any instant.
 *
 * **Cosmetic callbacks are broadcast** to every registered listener: the pill mirrors audio level and
 * engine warmth even while it is resting and nothing is being captured.
 *
 * Pure Kotlin with an injected owner source, so the routing is unit-tested without Android.
 */
class DictationRouter(private val captureOwner: () -> SessionArbiter.Owner?) {

    private val listeners = LinkedHashMap<SessionArbiter.Owner, DictationListener>()

    @Synchronized
    fun setListener(owner: SessionArbiter.Owner, listener: DictationListener) {
        listeners[owner] = listener
    }

    @Synchronized
    fun clearListener(owner: SessionArbiter.Owner) {
        listeners.remove(owner)
    }

    // --- Capture-scoped: only the owner of the active utterance --------------------------------

    fun onText(committed: String, raw: String, provisional: String) {
        captureTarget()?.onText?.invoke(committed, raw, provisional)
    }

    fun onPartialText(text: String) {
        captureTarget()?.onPartialText?.invoke(text)
    }

    fun onUnitCut(text: String) {
        captureTarget()?.onUnitCut?.invoke(text)
    }

    fun onUnitCommitted(text: String) {
        captureTarget()?.onUnitCommitted?.invoke(text)
    }

    fun onCleanupPass(pass: CleanupPassTelemetry) {
        captureTarget()?.onCleanupPass?.invoke(pass)
    }

    // --- Broadcast: every registered listener --------------------------------------------------

    fun onCleanupUnavailable() {
        observers().forEach { it.onCleanupUnavailable() }
    }

    fun onAudioLevel(level: Float) {
        observers().forEach { it.onAudioLevel(level) }
    }

    fun onModelResidencyChanged(asrLoaded: Boolean, cleanupLoaded: Boolean) {
        observers().forEach { it.onModelResidencyChanged(asrLoaded, cleanupLoaded) }
    }

    @Synchronized
    private fun captureTarget(): DictationListener? = captureOwner()?.let { listeners[it] }

    @Synchronized
    private fun observers(): List<DictationListener> = listeners.values.toList()
}
