package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FreshnessTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val lockSha = Fixtures.SHA_A
    private val generated = Instant.parse("2026-09-20T05:21:03Z")   // 20 days ago: stale on its own

    private fun hb(checked: String, forLock: String = lockSha) = Heartbeat(checked, forLock, "abc123")

    @Test fun `fresh when the heartbeat is recent and binds to the lock held`() =
        assertEquals(Freshness.Fresh(Instant.parse("2026-10-10T05:17:00Z")), Fresh.of(lockSha, generated, hb("2026-10-10T05:17:00Z"), now, 14))

    @Test fun `a fresh heartbeat for another lock counts for nothing`() {
        val f = Fresh.of(lockSha, generated, hb("2026-10-10T05:17:00Z", forLock = Fixtures.SHA_B), now, 14)
        assertTrue(f is Freshness.Unknown && f.reason.contains("heartbeat is for lock"))
    }

    @Test fun `stale when the heartbeat is older than allowed`() {
        val f = Fresh.of(lockSha, generated, hb("2026-09-20T05:17:00Z"), now, 14)
        assertTrue(f is Freshness.Stale && f.ageDays == 20L)
    }

    @Test fun `a heartbeat from the future or without an instant is unknown`() {
        assertTrue(Fresh.of(lockSha, generated, hb("2026-10-11T00:00:00Z"), now, 14) is Freshness.Unknown)
        assertTrue(Fresh.of(lockSha, generated, hb("soon"), now, 14) is Freshness.Unknown)
    }

    @Test fun `without a heartbeat the lock's own age is only ever unknown or stale, never fresh`() {
        val recent = Fresh.of(lockSha, Instant.parse("2026-10-09T05:21:03Z"), null, now, 14)
        assertTrue(recent is Freshness.Unknown)
        val old = Fresh.of(lockSha, generated, null, now, 14)
        assertTrue(old is Freshness.Stale && old.reason.startsWith("no heartbeat"))
        assertTrue(Fresh.of(lockSha, null, null, now, 14) is Freshness.Unknown)
    }

    @Test fun `heartbeat parsing tolerates unknown keys and refuses garbage`() {
        assertEquals(hb("2026-10-10T05:17:00Z"), Heartbeat.parse("""{"checked":"2026-10-10T05:17:00Z","lockSha256":"$lockSha","mainCommit":"abc123","extra":1}"""))
        assertNull(Heartbeat.parse("nope"))
        assertNull(Heartbeat.parse("""{"checked":"x"}"""))
    }
}
