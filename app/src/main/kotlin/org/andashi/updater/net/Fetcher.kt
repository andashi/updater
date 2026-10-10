package org.andashi.updater.net

import org.andashi.updater.core.BoundedDigest
import org.andashi.updater.core.Hex
import org.andashi.updater.core.Urls
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The one network seam (design §5.2, §16). Everything the updater fetches
 * passes through [Fetcher]; the hardware question - does this get network
 * in a lockdown zone - lives behind this interface, so if a phone says no,
 * the trigger changes and nothing else does.
 */
interface Fetcher {
    /** Small text (the lock, the heartbeat). Honors ETag: [NotModified] when the server says so. */
    fun text(url: String, etag: String?, maxBytes: Long): TextResult

    /** Large bytes (an APK) into [into], hashing on the way. Refuses more than [maxBytes]. */
    fun download(url: String, into: File, maxBytes: Long): Download

    sealed class TextResult {
        data class Ok(val text: String, val etag: String?) : TextResult()
        object NotModified : TextResult()
        data class Failed(val reason: String) : TextResult()
    }

    sealed class Download {
        data class Ok(val sha256: String, val size: Long) : Download()
        data class Failed(val reason: String) : Download()
    }
}

/**
 * HttpURLConnection on the platform TLS stack, with the network security
 * config deciding trust (system CAs only). Redirects are followed by hand so
 * every hop is checked against the host allowlist: a redirect to an
 * arbitrary host would be a way around the allowlist, and GitHub's asset
 * downloads do redirect, to hosts the allowlist names.
 */
class HttpFetcher(private val allowedHosts: Set<String>, private val cleartextLocalhost: Boolean = false) : Fetcher {

    override fun text(url: String, etag: String?, maxBytes: Long): Fetcher.TextResult {
        return try {
            open(url, etag) { conn ->
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> Fetcher.TextResult.NotModified
                    HttpURLConnection.HTTP_OK -> {
                        val bytes = conn.inputStream.use { readBounded(it, maxBytes) }
                        Fetcher.TextResult.Ok(bytes.toString(Charsets.UTF_8), conn.getHeaderField("ETag"))
                    }
                    else -> Fetcher.TextResult.Failed("http ${conn.responseCode}")
                }
            }
        } catch (e: Exception) {
            Fetcher.TextResult.Failed(describe(e))
        }
    }

    override fun download(url: String, into: File, maxBytes: Long): Fetcher.Download {
        return try {
            open(url, null) { conn ->
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@open Fetcher.Download.Failed("http ${conn.responseCode}")
                val declared = conn.contentLengthLong
                if (declared > maxBytes) return@open Fetcher.Download.Failed("content-length $declared exceeds $maxBytes")
                // The size refusal lives in core (BoundedDigest, tested there):
                // a chunk that would pass the lock's size is refused before it
                // is written.
                val bounded = BoundedDigest(maxBytes)
                into.parentFile?.mkdirs()
                into.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (!bounded.update(buf, 0, n)) return@open Fetcher.Download.Failed("body exceeds $maxBytes bytes")
                            out.write(buf, 0, n)
                        }
                    }
                }
                Fetcher.Download.Ok(bounded.sha256(), bounded.size)
            }
        } catch (e: Exception) {
            Fetcher.Download.Failed(describe(e))
        }
    }

    private fun <T> open(startUrl: String, etag: String?, handle: (HttpURLConnection) -> T): T {
        var url = startUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            Urls.refuse(url, allowedHosts, cleartextLocalhost)?.let { throw IOException("refused ${if (hop == 0) "url" else "redirect"} $url: $it") }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                if (etag != null && hop == 0) setRequestProperty("If-None-Match", etag)
            }
            try {
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                return handle(conn)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("more than $MAX_REDIRECTS redirects")
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("body exceeds $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun describe(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"

    companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        // Identifies the client without a version: nothing to fingerprint by, nothing to update.
        const val USER_AGENT = "andashi-updater"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** A null sink that only hashes, for re-hashing while writing into a session. */
class HashingOutputStream(private val sink: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L; private set
    override fun write(b: Int) { digest.update(b.toByte()); size++; sink.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); size += len; sink.write(b, off, len) }
    override fun flush() = sink.flush()
    override fun close() = sink.close()
    fun sha256(): String = Hex.encode(digest.digest())
}
