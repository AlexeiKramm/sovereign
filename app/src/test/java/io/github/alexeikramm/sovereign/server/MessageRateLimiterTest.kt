package io.github.alexeikramm.sovereign.server

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRateLimiterTest {

    /** A manually advanced clock so refill is deterministic. */
    private class Clock(var nanos: Long = 0L) {
        fun now(): Long = nanos
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000
        }
    }

    private fun limiter(
        clock: Clock,
        capacity: Double = MessageRateLimiter.DEFAULT_CAPACITY,
        refill: Double = MessageRateLimiter.DEFAULT_REFILL_PER_SECOND,
        close: Int = MessageRateLimiter.DEFAULT_CLOSE_THRESHOLD,
    ) = MessageRateLimiter(capacity, refill, close, clock::now)

    @Test
    fun `allows a burst up to capacity then limits`() {
        val clock = Clock()
        val l = limiter(clock, capacity = 3.0, refill = 1.0)
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
        assertEquals(MessageRateLimiter.Decision.LIMIT, l.check())
    }

    @Test
    fun `refills over time`() {
        val clock = Clock()
        val l = limiter(clock, capacity = 1.0, refill = 2.0)
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
        assertEquals(MessageRateLimiter.Decision.LIMIT, l.check())
        clock.advanceMs(500) // half a second at 2/s = one token
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
    }

    @Test
    fun `sustained abuse escalates to close`() {
        val clock = Clock()
        val l = limiter(clock, capacity = 1.0, refill = 0.0, close = 3)
        assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
        assertEquals(MessageRateLimiter.Decision.LIMIT, l.check())
        assertEquals(MessageRateLimiter.Decision.LIMIT, l.check())
        assertEquals(MessageRateLimiter.Decision.CLOSE, l.check())
    }

    @Test
    fun `a normal client pinging every five seconds is never limited`() {
        val clock = Clock()
        val l = limiter(clock)
        repeat(100) {
            assertEquals(MessageRateLimiter.Decision.ALLOW, l.check())
            clock.advanceMs(5_000)
        }
    }
}
