package org.andashi.updater.core

import java.security.MessageDigest

/**
 * The refusal of a body longer than the lock's `size` (design §7, step 3)
 * as a pure object: bytes are hashed as they arrive, and the first byte
 * past the limit is refused before it is written anywhere. The app's
 * fetcher feeds it; the test exercises every edge without a socket.
 */
class BoundedDigest(private val limit: Long) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var size: Long = 0
        private set
    var exceeded: Boolean = false
        private set

    /** Accepts a chunk; false - and nothing of the chunk counted - once the total would pass [limit]. */
    fun update(buf: ByteArray, off: Int = 0, len: Int = buf.size): Boolean {
        if (exceeded) return false
        if (size + len > limit) { exceeded = true; return false }
        digest.update(buf, off, len)
        size += len
        return true
    }

    /** Lower-case hex of everything accepted so far. Only meaningful when not [exceeded]. */
    fun sha256(): String = Hex.encode(digest.digest())
}
