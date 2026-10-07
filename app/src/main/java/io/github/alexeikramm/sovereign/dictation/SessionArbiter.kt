package io.github.alexeikramm.sovereign.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide guard for "exactly one dictation capture at a time" (D-106, `REVIEW.md` H3).
 *
 * The app owns one `WavRecorder` and one [DictationPipeline] whose utterance state is not
 * re-entrant, so a second capture path — the desktop server's `START_DICTATION`, or the phone's own
 * pill — would corrupt both utterances. Every capture path acquires the arbiter before it touches
 * the recorder; a second attempt is **refused with the current holder**, which the caller surfaces
 * rather than failing silently.
 *
 * The lease is held for the whole session, not merely until the recorder stops: a review that is
 * still on screen owns the pipeline's text, and a second capture starting under it would wipe it.
 *
 * A class rather than an `object` so the decision is unit-testable in isolation; [shared] is the one
 * process-wide instance the app uses.
 */
class SessionArbiter {

    /** Who is capturing. The refusal message and the residency policy both key off this. */
    enum class Owner {
        PILL,

        /**
         * The transcribe screen: a live mic capture or an audio-file replay driven through the same
         * shared session (D-126). A file job holds the lease for its whole run, so the pill and the
         * desktop server are refused while it works — the accepted trade for one engine set.
         */
        TRANSCRIBE,
        SERVER,
        ;

        /**
         * The user-facing reason a capture could not start because this owner holds the arbiter.
         * One wording for every surface — the pill (CHUNK-7) and the transcribe screen both show
         * it — so the two cannot drift.
         */
        fun busyReason(): String = when (this) {
            PILL -> "The floating pill is already dictating"
            TRANSCRIBE -> "A transcription is already in progress"
            SERVER -> "The desktop server is using the microphone"
        }
    }

    sealed interface Result {
        data class Granted(val lease: Lease) : Result

        /** Another owner already captures; [holder] is who to tell the user about. */
        data class Refused(val holder: Owner) : Result
    }

    /** Proof of a grant. Handing the wrong token back must not free someone else's capture. */
    class Lease internal constructor(val owner: Owner)

    private var held: Lease? = null

    /** The owner currently capturing, or null. The server reads this to report `SESSION_BUSY`. */
    val holder: Owner? get() = held?.owner

    /**
     * The same holder as an observable, for callers that must *react* to a change rather than poll
     * it — the server broadcasts `SERVER_STATE` on every transition (CHUNK-8). Updated by [acquire]/
     * [release]; `StateFlow` conflates repeats, so a collector sees each new holder once.
     */
    private val _holderFlow = MutableStateFlow<Owner?>(null)
    val holderFlow: StateFlow<Owner?> = _holderFlow.asStateFlow()

    @Synchronized
    fun acquire(owner: Owner): Result {
        val current = held
        return if (current != null) {
            Result.Refused(current.owner)
        } else {
            val lease = Lease(owner)
            held = lease
            _holderFlow.value = owner
            Result.Granted(lease)
        }
    }

    @Synchronized
    fun release(lease: Lease) {
        if (held === lease) {
            held = null
            _holderFlow.value = null
        }
    }

    companion object {
        /** The process-wide arbiter: the pill and the desktop server both claim this one. */
        val shared = SessionArbiter()
    }
}
