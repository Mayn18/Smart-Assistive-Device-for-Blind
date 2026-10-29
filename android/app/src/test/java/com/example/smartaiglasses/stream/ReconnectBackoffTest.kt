package com.example.smartaiglasses.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun delaysDoubleThenStayAtTheCap() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000L, maxDelayMs = 8_000L)

        val delays = List(7) { backoff.nextDelayMs() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun resetReturnsToTheShortInitialDelay() {
        val backoff = ReconnectBackoff()
        repeat(5) { backoff.nextDelayMs() }

        backoff.reset()

        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test
    fun capThatIsNotAPowerOfTwoMultipleIsStillRespected() {
        val backoff = ReconnectBackoff(initialDelayMs = 1_000L, maxDelayMs = 5_000L)

        val delays = List(5) { backoff.nextDelayMs() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), delays)
    }

    @Test
    fun hugeCapDoesNotOverflow() {
        val backoff = ReconnectBackoff(initialDelayMs = 1L, maxDelayMs = Long.MAX_VALUE)

        var previous = 0L
        repeat(100) {
            val next = backoff.nextDelayMs()
            // A wrapped (negative) or shrinking delay would mean the doubling overflowed.
            assertTrue("delay went backwards: $previous -> $next", next >= previous)
            previous = next
        }
    }

    @Test
    fun rejectsNonsenseConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff(0L, 1_000L) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff(2_000L, 1_000L) }
    }
}
