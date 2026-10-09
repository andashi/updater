package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BackoffTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")

    @Test fun `doubles from thirty minutes and caps at a day`() {
        assertEquals(now.plus(Duration.ofMinutes(30)), Backoff.next(1, now))
        assertEquals(now.plus(Duration.ofHours(1)), Backoff.next(2, now))
        assertEquals(now.plus(Duration.ofHours(8)), Backoff.next(5, now))
        assertEquals(now.plus(Duration.ofHours(24)), Backoff.next(7, now))
        assertEquals(now.plus(Duration.ofHours(24)), Backoff.next(50, now))
    }

    @Test fun `a zero or negative attempt count is treated as the first`() =
        assertEquals(Backoff.next(1, now), Backoff.next(0, now))

    @Test fun `notifies from the third miss on`() {
        assertFalse(Backoff.shouldNotify(2))
        assertTrue(Backoff.shouldNotify(3))
    }

    @Test fun `due without a next try, at it, and after it - not before`() {
        assertTrue(Backoff.due(null, now))
        assertTrue(Backoff.due(now, now))
        assertTrue(Backoff.due(now.minusSeconds(1), now))
        assertFalse(Backoff.due(now.plusSeconds(1), now))
    }
}
