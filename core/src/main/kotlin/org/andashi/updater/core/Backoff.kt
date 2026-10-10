package org.andashi.updater.core

import java.time.Duration
import java.time.Instant

/**
 * Retry budget per package and lock version (design §11): exponential from
 * thirty minutes, capped at a day, a failure notification after the third
 * miss, then a daily retry until the lock moves.
 */
object Backoff {
    val FIRST: Duration = Duration.ofMinutes(30)
    val CAP: Duration = Duration.ofHours(24)
    const val NOTIFY_AFTER = 3

    /** When to try again after the [attempts]-th failure (1-based). */
    fun next(attempts: Int, now: Instant): Instant {
        val n = attempts.coerceAtLeast(1)
        val shift = (n - 1).coerceAtMost(6)
        val wait = FIRST.multipliedBy(1L shl shift)
        return now.plus(if (wait > CAP) CAP else wait)
    }

    fun shouldNotify(attempts: Int): Boolean = attempts >= NOTIFY_AFTER

    /** True when a package in `failed` may be tried now. */
    fun due(nextTry: Instant?, now: Instant): Boolean = nextTry == null || !now.isBefore(nextTry)
}

/**
 * `minimumAgeHours` (design §11): an update is held until the version has
 * been seen in a valid lock for that long. The clock is this zone's own
 * first sighting, recorded per package, never a date the phone cannot
 * verify.
 */
object Hold {
    /** When the window opens; equal to [firstSeen] when there is no window. */
    fun opensAt(firstSeen: Instant, minimumAgeHours: Int): Instant = firstSeen.plus(Duration.ofHours(minimumAgeHours.toLong().coerceAtLeast(0)))

    fun held(firstSeen: Instant, minimumAgeHours: Int, now: Instant): Boolean = now.isBefore(opensAt(firstSeen, minimumAgeHours))
}
