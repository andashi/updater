package org.andashi.updater.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Where the updater may connect. Two kinds of URL exist - the lock and the
 * APKs the lock names - and both pass through here, every redirect hop
 * included (design §10). The allowlist comes from the host; a lock cannot
 * widen it, so a lock cannot send a phone to a server of its choosing.
 */
object Urls {
    /** Null when allowed; otherwise the reason, in words that go into the state the host reads. */
    fun refuse(url: String, allowedHosts: Set<String>): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "not a URL"
        }
        if (uri.scheme?.lowercase() != "https") return "scheme '${uri.scheme}' is not https"
        val host = uri.host?.lowercase() ?: return "no host"
        if (uri.userInfo != null) return "user info in URL"
        if (uri.port != -1 && uri.port != 443) return "port ${uri.port} is not 443"
        if (uri.fragment != null) return "fragment in URL"
        if (host !in allowedHosts.map { it.lowercase() }) return "host '$host' is not allow-listed"
        return null
    }

    fun isAllowed(url: String, allowedHosts: Set<String>): Boolean = refuse(url, allowedHosts) == null
}

object Hex {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Lower-case, exactly 64 hex digits. Upper case is refused on purpose: the lock and the pins are written lower-case, and a comparison that normalises invites a comparison that forgets to. */
    fun isSha256(s: String): Boolean = SHA256.matches(s)

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(DIGITS[v ushr 4]); append(DIGITS[v and 0x0f])
        }
    }

    private const val DIGITS = "0123456789abcdef"
}
