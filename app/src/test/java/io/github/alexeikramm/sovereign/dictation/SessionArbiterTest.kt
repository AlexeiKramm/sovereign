package io.github.alexeikramm.sovereign.dictation

import io.github.alexeikramm.sovereign.dictation.SessionArbiter.Owner
import io.github.alexeikramm.sovereign.dictation.SessionArbiter.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide "one capture at a time" guard (D-106, `REVIEW.md` H3). The interesting cases are
 * the ones that would corrupt two utterances on the device: a second claimant from another owner,
 * a second claimant from the *same* owner, and a stale lease handed back after a new capture began.
 */
class SessionArbiterTest {

    @Test
    fun `first capture is granted and becomes the holder`() {
        val arbiter = SessionArbiter()
        assertTrue(arbiter.acquire(Owner.PILL) is Result.Granted)
        assertEquals(Owner.PILL, arbiter.holder)
    }

    @Test
    fun `a second capture from another owner is refused with the current holder`() {
        val arbiter = SessionArbiter()
        arbiter.acquire(Owner.SERVER)
        val refused = arbiter.acquire(Owner.PILL)
        assertTrue(refused is Result.Refused)
        assertEquals(Owner.SERVER, (refused as Result.Refused).holder)
        assertEquals(Owner.SERVER, arbiter.holder)
    }

    @Test
    fun `the same owner cannot capture twice either`() {
        val arbiter = SessionArbiter()
        arbiter.acquire(Owner.PILL)
        val refused = arbiter.acquire(Owner.PILL)
        assertEquals(Owner.PILL, (refused as Result.Refused).holder)
    }

    @Test
    fun `release frees the arbiter for the next capture`() {
        val arbiter = SessionArbiter()
        val lease = (arbiter.acquire(Owner.PILL) as Result.Granted).lease
        arbiter.release(lease)
        assertNull(arbiter.holder)
        assertTrue(arbiter.acquire(Owner.SERVER) is Result.Granted)
    }

    @Test
    fun `a stale lease cannot release the current holder`() {
        val arbiter = SessionArbiter()
        val first = (arbiter.acquire(Owner.PILL) as Result.Granted).lease
        arbiter.release(first)
        val second = (arbiter.acquire(Owner.SERVER) as Result.Granted).lease
        arbiter.release(first) // stale: must not free the server's capture
        assertEquals(Owner.SERVER, arbiter.holder)
        arbiter.release(second)
        assertNull(arbiter.holder)
    }

    @Test
    fun `release is idempotent`() {
        val arbiter = SessionArbiter()
        val lease = (arbiter.acquire(Owner.PILL) as Result.Granted).lease
        arbiter.release(lease)
        arbiter.release(lease)
        assertNull(arbiter.holder)
    }

    /**
     * D-126: the transcribe screen is a first-class claimant (live mic and file replay), so it
     * refuses the pill and the server exactly as they refuse each other.
     */
    @Test
    fun `the transcribe screen is a first-class claimant`() {
        val arbiter = SessionArbiter()
        val lease = (arbiter.acquire(Owner.TRANSCRIBE) as Result.Granted).lease
        assertEquals(Owner.TRANSCRIBE, arbiter.holder)
        assertEquals(Owner.TRANSCRIBE, (arbiter.acquire(Owner.PILL) as Result.Refused).holder)
        arbiter.release(lease)
        assertTrue(arbiter.acquire(Owner.SERVER) is Result.Granted)
    }

    /**
     * CHUNK-8: the arbiter's holder is observable, so the server can broadcast `SERVER_STATE` on every
     * transition instead of polling.
     */
    @Test
    fun `holderFlow follows acquire and release`() {
        val arbiter = SessionArbiter()
        assertNull(arbiter.holderFlow.value)
        val lease = (arbiter.acquire(Owner.TRANSCRIBE) as Result.Granted).lease
        assertEquals(Owner.TRANSCRIBE, arbiter.holderFlow.value)
        arbiter.release(lease)
        assertNull(arbiter.holderFlow.value)
    }

    /**
     * CHUNK-7: every holder names itself for the user, and the transcribe holder in particular must
     * not read as a generic busy — the shared wording the pill and the transcribe screen both show.
     */
    @Test
    fun `each holder has its own user-facing reason`() {
        val reasons = Owner.entries.map { it.busyReason() }
        assertEquals(reasons.size, reasons.toSet().size)
        assertTrue(reasons.all { it.isNotBlank() })
        assertEquals("A transcription is already in progress", Owner.TRANSCRIBE.busyReason())
    }
}
