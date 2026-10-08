package io.github.alexeikramm.sovereign.server

/**
 * A per-connection token bucket that bounds how many inbound frames the server will act on
 * (PROTOCOL.md §8 `RATE_LIMITED`). It exists so a hostile LAN peer — which at this point may not even
 * have completed `HELLO` — cannot flood the single-threaded protocol engine or the outbound socket.
 *
 * [check] is synchronized: Java-WebSocket may deliver frames for one connection on more than one
 * worker thread, so the bucket must be safe under that.
 */
class MessageRateLimiter(
    /** Burst size: how many frames may arrive back-to-back before any are limited. */
    private val capacity: Double = DEFAULT_CAPACITY,
    /** Sustained allowance, tokens per second. */
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
    /** Consecutive limited frames that escalate from LIMIT to CLOSE. */
    private val closeThreshold: Int = DEFAULT_CLOSE_THRESHOLD,
    private val nowNanos: () -> Long = System::nanoTime,
) {

    /** What the transport should do with one inbound frame. */
    enum class Decision {
        /** Within budget: process the frame normally. */
        ALLOW,

        /** Over budget: answer `RATE_LIMITED` (non-fatal) and drop this frame. */
        LIMIT,

        /** Sustained abuse: close the connection. */
        CLOSE,
    }

    private var tokens = capacity
    private var lastNanos = nowNanos()
    private var consecutive = 0

    @Synchronized
    fun check(): Decision {
        val now = nowNanos()
        val elapsed = (now - lastNanos).coerceAtLeast(0L) / 1_000_000_000.0
        lastNanos = now
        tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity)

        if (tokens >= 1.0) {
            tokens -= 1.0
            consecutive = 0
            return Decision.ALLOW
        }
        consecutive += 1
        return if (consecutive >= closeThreshold) Decision.CLOSE else Decision.LIMIT
    }

    companion object {
        /** A generous burst: a pairing handshake plus a session start is well under this. */
        const val DEFAULT_CAPACITY = 60.0

        /** ~4x the real client's rate (a PING every 5s plus a handful of session frames). */
        const val DEFAULT_REFILL_PER_SECOND = 20.0

        const val DEFAULT_CLOSE_THRESHOLD = 100
    }
}
