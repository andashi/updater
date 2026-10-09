package org.andashi.updater.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `updater.json`, pushed by the host into each zone (design §9.1). It is the
 * only input that can widen what the updater does: the managed set, the
 * signer pins, the lock URL and host allowlist, the policy. The lock can
 * narrow nothing here and widen nothing.
 */
@Serializable
data class Config(
    val schemaVersion: Int,
    val zone: String,
    val lock: LockSource,
    val schedule: Schedule = Schedule(),
    val policy: Policy = Policy(),
    val managed: List<ManagedApp>,
    val checks: Checks = Checks(),
) {
    @Serializable
    data class LockSource(
        /** `fetch` or `host-only` (0002). */
        val mode: String = "fetch",
        val url: String = "",
        val heartbeatUrl: String = "",
        val maxAgeDays: Int = 14,
        val allowedHosts: List<String> = emptyList(),
    )

    @Serializable
    data class Schedule(
        val checkEveryHours: Int = 6,
        val requireUnmetered: Boolean = false,
        val requireCharging: Boolean = false,
    )

    @Serializable
    data class Policy(
        val minimumAgeHours: Int = 0,
        val constraints: InstallConstraints = InstallConstraints(),
    )

    @Serializable
    data class InstallConstraints(
        val notInCall: Boolean = true,
        val notForeground: Boolean = true,
        /** After this many hours of waiting for a quiet moment, install anyway and say so. */
        val boundHours: Int = 24,
    )

    @Serializable
    data class ManagedApp(
        val pkg: String,
        val label: String = "",
        /** SHA-256 of the pinned signer certificate, from `apks/certs/<pkg>.cert`. */
        val signer: String,
    )

    @Serializable
    data class Checks(
        /** Packages declared `net: false` in the catalog: the updater verifies they hold no INTERNET. */
        val netFalse: List<String> = emptyList(),
    )

    val hostOnly: Boolean get() = lock.mode == "host-only"
    val lockPolicy: LockPolicy get() = LockPolicy(allowedHosts = lock.allowedHosts.toSet())

    fun pinFor(pkg: String): String? = managed.firstOrNull { it.pkg == pkg }?.signer

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (schemaVersion != SUPPORTED_VERSION) problems += "schemaVersion $schemaVersion, only $SUPPORTED_VERSION is understood"
        if (zone.isBlank()) problems += "zone is empty"
        when (lock.mode) {
            "fetch" -> {
                if (lock.allowedHosts.isEmpty()) problems += "lock.allowedHosts is empty"
                Urls.refuse(lock.url, lock.allowedHosts.toSet())?.let { problems += "lock.url: $it" }
                if (lock.heartbeatUrl.isNotEmpty()) Urls.refuse(lock.heartbeatUrl, lock.allowedHosts.toSet())?.let { problems += "lock.heartbeatUrl: $it" }
            }
            "host-only" -> {}
            else -> problems += "lock.mode '${lock.mode}' is neither fetch nor host-only"
        }
        if (lock.maxAgeDays < 1) problems += "lock.maxAgeDays must be at least 1"
        if (schedule.checkEveryHours < 1) problems += "schedule.checkEveryHours must be at least 1"
        if (policy.minimumAgeHours < 0) problems += "policy.minimumAgeHours must not be negative"
        if (policy.constraints.boundHours < 1) problems += "policy.constraints.boundHours must be at least 1"
        if (managed.isEmpty()) problems += "managed is empty"
        val seen = HashSet<String>()
        for (m in managed) {
            if (!seen.add(m.pkg)) problems += "managed: ${m.pkg} listed twice"
            if (!Hex.isSha256(m.signer)) problems += "managed: ${m.pkg} signer is not 64 hex digits"
        }
        return problems
    }

    companion object {
        const val SUPPORTED_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Config {
            val config = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw ConfigFormatException("not an updater config: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            val problems = config.validate()
            if (problems.isNotEmpty()) throw ConfigFormatException(problems.joinToString("; "))
            return config
        }
    }
}

class ConfigFormatException(message: String) : Exception(message)
