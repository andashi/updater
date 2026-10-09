package org.andashi.updater.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * The `lock-heartbeat` branch's file (design §11): written by the refresh
 * workflow on every run, `unchanged` included, so a lock that stopped moving
 * can be told from an upstream that stopped releasing.
 */
@Serializable
data class Heartbeat(
    val checked: String,
    val lockSha256: String,
    val mainCommit: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Heartbeat? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

sealed class Freshness {
    /** The heartbeat is recent and binds to the lock held. */
    data class Fresh(val checkedAt: Instant) : Freshness()

    /** The heartbeat (or, without one, the lock itself) is older than allowed. */
    data class Stale(val ageDays: Long, val reason: String) : Freshness()

    /** No usable heartbeat: unreachable, unparsable, or for another lock. Reported, not alarmed. */
    data class Unknown(val reason: String) : Freshness()
}

object Fresh {
    /**
     * A heartbeat counts only if its `lockSha256` is the hash of the lock
     * bytes the updater holds; otherwise an old lock could be paired with a
     * fresh heartbeat. Without a heartbeat the lock's own `generated` is
     * the only clock, and it only moves when the lock changes - so that
     * verdict is `Stale` with its reason, never silently `Fresh`.
     */
    fun of(lockSha256: String, lockGeneratedAt: Instant?, heartbeat: Heartbeat?, now: Instant, maxAgeDays: Int): Freshness {
        val limit = Duration.ofDays(maxAgeDays.toLong())
        if (heartbeat != null) {
            if (heartbeat.lockSha256 != lockSha256) return Freshness.Unknown("heartbeat is for lock ${heartbeat.lockSha256.take(12)}, held ${lockSha256.take(12)}")
            val checked = runCatching { Instant.parse(heartbeat.checked) }.getOrNull()
                ?: return Freshness.Unknown("heartbeat 'checked' is not an instant")
            if (checked.isAfter(now.plus(Duration.ofHours(1)))) return Freshness.Unknown("heartbeat is from the future")
            val age = Duration.between(checked, now)
            return if (age > limit) Freshness.Stale(age.toDays(), "last refresh run ${age.toDays()} days ago") else Freshness.Fresh(checked)
        }
        if (lockGeneratedAt == null) return Freshness.Unknown("no heartbeat and no generated instant")
        val age = Duration.between(lockGeneratedAt, now)
        return if (age > limit) Freshness.Stale(age.toDays(), "no heartbeat; lock generated ${age.toDays()} days ago") else Freshness.Unknown("no heartbeat; lock generated ${age.toDays()} days ago")
    }
}
