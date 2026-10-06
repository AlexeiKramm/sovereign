package io.github.alexeikramm.sovereign.dictation

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
    enum class Owner { PILL, SERVER }

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

    @Synchronized
    fun acquire(owner: Owner): Result {
        val current = held
        return if (current != null) {
            Result.Refused(current.owner)
        } else {
            val lease = Lease(owner)
            held = lease
            Result.Granted(lease)
        }
    }

    @Synchronized
    fun release(lease: Lease) {
        if (held === lease) held = null
    }

    companion object {
        /** The process-wide arbiter: the pill and the desktop server both claim this one. */
        val shared = SessionArbiter()
    }
}
