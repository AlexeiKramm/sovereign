package io.github.alexeikramm.sovereign.dictation

import android.content.Context
import io.github.alexeikramm.sovereign.data.AppSettings
import io.github.alexeikramm.sovereign.data.DictionaryStore
import io.github.alexeikramm.sovereign.data.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the **one** process-wide [DictationSession] (D-106). The ASR + cleanup engines, the recorder
 * and the dispatchers are ~1.5 GB together and must never exist twice under `largeHeap` (D-045), so
 * the floating pill and the desktop server are both adapters that borrow this instance and register
 * a [DictationListener] via [setListener].
 *
 * The session's scope is deliberately process-lifetime: a consumer's own scope (the pill service) can
 * be cancelled while another consumer (the server) still needs the engines.
 *
 * The stores are built here from the application context; `ProfileStore`/`DictionaryStore` read their
 * JSON from disk on every call, so they see edits another instance made.
 */
object DictationSessionHolder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val router = DictationRouter { SessionArbiter.shared.holder }

    @Volatile
    private var instance: DictationSession? = null

    /** The shared session, created on first use. [context] matters only for that first creation. */
    fun shared(context: Context): DictationSession {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    /**
     * Register [listener] as [owner]'s callbacks. Capture-scoped events reach it only while [owner]
     * holds the arbiter; cosmetic events reach it always.
     */
    fun setListener(owner: SessionArbiter.Owner, listener: DictationListener) {
        router.setListener(owner, listener)
    }

    /** Drop [owner]'s callbacks. Call when the consumer (e.g. the pill service) is destroyed. */
    fun clearListener(owner: SessionArbiter.Owner) {
        router.clearListener(owner)
    }

    /** Test seam: the router alone, without a constructed session. */
    internal fun router(): DictationRouter = router

    private fun create(appContext: Context): DictationSession = DictationSession(
        context = appContext,
        settings = AppSettings(appContext),
        profileStore = ProfileStore(appContext),
        dictionaryStore = DictionaryStore(appContext),
        scope = scope,
        onText = router::onText,
        onCleanupUnavailable = router::onCleanupUnavailable,
        onPartialText = router::onPartialText,
        onUnitCut = router::onUnitCut,
        onUnitCommitted = router::onUnitCommitted,
        onCleanupPass = router::onCleanupPass,
        onAudioLevel = router::onAudioLevel,
        onModelResidencyChanged = router::onModelResidencyChanged,
    )
}
