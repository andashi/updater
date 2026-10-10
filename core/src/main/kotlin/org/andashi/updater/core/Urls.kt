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
    /**
     * Null when allowed; otherwise the reason, in words that go into the
     * state the host reads. [cleartextLocalhost] is the debug build's
     * allowance for the emulator e2e, which serves over `adb reverse` on
     * loopback: `http://localhost` on any port, nothing else. A release
     * build never sets it, and the network security config refuses
     * cleartext there a second time.
     */
    fun refuse(url: String, allowedHosts: Set<String>?, cleartextLocalhost: Boolean = false): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "not a URL"
        }
        val host = uri.host?.lowercase() ?: return "no host"
        val loopback = cleartextLocalhost && host == "localhost" && uri.scheme?.lowercase() == "http"
        if (!loopback && uri.scheme?.lowercase() != "https") return "scheme '${uri.scheme}' is not https"
        if (uri.userInfo != null) return "user info in URL"
        if (!loopback && uri.port != -1 && uri.port != 443) return "port ${uri.port} is not 443"
        if (uri.fragment != null) return "fragment in URL"
        // A null allowlist means "shape only": host-only mode, where nothing
        // is fetched (LockPolicy.fetches). An empty allowlist refuses every host.
        if (allowedHosts != null && host !in allowedHosts.map { it.lowercase() }) return "host '$host' is not allow-listed"
        return null
    }

    fun isAllowed(url: String, allowedHosts: Set<String>, cleartextLocalhost: Boolean = false): Boolean = refuse(url, allowedHosts, cleartextLocalhost) == null
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
