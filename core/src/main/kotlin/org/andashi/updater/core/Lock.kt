package org.andashi.updater.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * `apks/lock.json` as provisioning writes it (lockVersion 1): per APK the
 * package, the version, the ABI, the bytes' SHA-256, their size, the signer
 * certificate's SHA-256, and the URLs proven to serve exactly those bytes.
 *
 * Parsing is strict about shape and lenient about unknown keys: a key the
 * lock gains later must not break every phone, a key it loses must.
 */
@Serializable
data class Lock(
    val lockVersion: Int,
    val generated: String,
    val entries: List<LockEntry>,
) {
    /** `generated` as an instant; null when it does not parse, which validation refuses. */
    val generatedAt: Instant? get() = runCatching { Instant.parse(generated) }.getOrNull()

    /**
     * The one entry for a package on this ABI: the ABI-specific build if the
     * lock has one, else the universal one. Two candidates of the same kind
     * is a malformed lock and yields none - a guess would be an install the
     * lock did not name.
     */
    fun entryFor(pkg: String, abi: String): LockEntry? {
        val mine = entries.filter { it.pkg == pkg }
        val specific = mine.filter { it.abi == abi }
        val universal = mine.filter { it.abi == "universal" }
        return when {
            specific.size == 1 -> specific[0]
            specific.isEmpty() && universal.size == 1 -> universal[0]
            else -> null
        }
    }

    companion object {
        const val SUPPORTED_VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses, or throws [LockFormatException] with the reason. Never returns a lock that failed [validate]. */
        fun parse(text: String, policy: LockPolicy, previous: Lock? = null): Lock {
            val lock = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw LockFormatException("not a lock: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            val problems = lock.validate(policy, previous)
            if (problems.isNotEmpty()) throw LockFormatException(problems.joinToString("; "))
            return lock
        }
    }

    /**
     * Every reason this lock may not be used. Empty means usable. Each check
     * names the entry it failed on, so the state the host reads says which
     * line of the lock is wrong, not just that one is.
     */
    fun validate(policy: LockPolicy, previous: Lock? = null): List<String> {
        val problems = mutableListOf<String>()
        if (lockVersion != SUPPORTED_VERSION) problems += "lockVersion $lockVersion, only $SUPPORTED_VERSION is understood"
        val at = try {
            Instant.parse(generated)
        } catch (_: DateTimeParseException) {
            problems += "generated '$generated' is not an instant"; null
        }
        if (at != null && previous?.generatedAt != null && at.isBefore(previous.generatedAt)) {
            problems += "generated $generated is older than the lock already held (${previous.generated}): rollback refused"
        }
        if (entries.isEmpty()) problems += "no entries"
        val seen = HashSet<String>()
        for (e in entries) {
            val id = "${e.pkg}/${e.abi}"
            if (!seen.add(id)) problems += "$id listed twice"
            problems += e.validate(policy).map { "$id: $it" }
        }
        return problems
    }
}

@Serializable
data class LockEntry(
    val pkg: String,
    val label: String = "",
    val version: String = "",
    val versionCode: Long,
    val abi: String,
    val sha256: String,
    val size: Long,
    val signer: String,
    val urls: List<String>,
    @SerialName("file") val file: String = "",
) {
    fun validate(policy: LockPolicy): List<String> {
        val problems = mutableListOf<String>()
        if (!PACKAGE_NAME.matches(pkg)) problems += "package name '$pkg' is not a package name"
        if (versionCode <= 0) problems += "versionCode $versionCode is not positive"
        if (abi !in ALLOWED_ABIS) problems += "abi '$abi' is not one of $ALLOWED_ABIS"
        if (!Hex.isSha256(sha256)) problems += "sha256 is not 64 hex digits"
        if (!Hex.isSha256(signer)) problems += "signer is not 64 hex digits"
        if (size <= 0) problems += "size $size is not positive"
        if (size > policy.maxBytes) problems += "size $size exceeds the limit of ${policy.maxBytes} bytes"
        if (urls.isEmpty()) problems += "no urls"
        for (u in urls) {
            val why = Urls.refuse(u, policy.allowedHosts)
            if (why != null) problems += "url $u: $why"
        }
        return problems
    }

    companion object {
        val ALLOWED_ABIS = setOf("universal", "arm64-v8a", "x86_64")
        // Android's own rule: dot-separated Java identifiers, at least two.
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}

/** What the host allows a lock to ask for. Comes from `updater.json`, never from the lock. */
data class LockPolicy(
    val allowedHosts: Set<String>,
    /** An APK larger than this is refused before a byte is fetched. Tor Browser is ~110 MB. */
    val maxBytes: Long = 512L * 1024 * 1024,
)

class LockFormatException(message: String) : Exception(message)
