package io.github.alexeikramm.sovereign.asr

/**
 * The **uncommitted tail** of a growing streaming transcript, and whether the committed part was
 * revised underneath it (D-140).
 *
 * With the per-pause reset removed, the streaming recogniser returns one growing transcript for the
 * whole utterance instead of a clean per-segment string. The pause still decides *where to cut*, so
 * the pipeline needs the text after the last cut — that is [tail] — computed against the transcript
 * snapshot taken at that cut ("the boundary").
 *
 * It is a pure function of two strings, so the boundary rule is tested on this machine rather than
 * inferred on a phone.
 */
object TranscriptTail {

    /**
     * @property tail The transcript after the committed snapshot: the current unit's live text.
     * @property revised True when the snapshot is **no longer a prefix** of the transcript, i.e. the
     *   recogniser changed text that was already committed. Detected and reported, never repaired.
     * @property divergedChars Characters of the committed snapshot that changed (0 unless [revised]).
     */
    data class Split(
        val tail: String,
        val revised: Boolean,
        val divergedChars: Int,
    )

    /**
     * Splits [transcript] against [committed] (the snapshot at the last cut), returning the live tail.
     *
     * A revision is not an error path to hide: it means the boundary is now approximate, so the tail
     * starts at the divergence point and the caller records it. Failing closed here (returning an
     * empty tail) would silently swallow the words, which is worse.
     */
    fun split(transcript: String, committed: String): Split {
        if (committed.isEmpty()) return Split(tail = transcript, revised = false, divergedChars = 0)
        if (transcript.startsWith(committed)) {
            return Split(tail = transcript.substring(committed.length), revised = false, divergedChars = 0)
        }
        val lcp = commonPrefixLength(transcript, committed)
        return Split(
            tail = transcript.substring(lcp),
            revised = true,
            divergedChars = committed.length - lcp,
        )
    }

    /** Length of the longest common prefix of [a] and [b]. */
    fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }
}
