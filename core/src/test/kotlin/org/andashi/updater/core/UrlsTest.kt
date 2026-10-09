package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    private val hosts = setOf("github.com", "objects.githubusercontent.com")

    @Test fun `allows https on an allow-listed host`() =
        assertNull(Urls.refuse("https://github.com/x/y/releases/download/v1/app.apk", hosts))

    @Test fun `host comparison ignores case`() =
        assertNull(Urls.refuse("https://GitHub.com/x.apk", setOf("github.com")))

    @Test fun `refuses cleartext`() =
        assertEquals("scheme 'http' is not https", Urls.refuse("http://github.com/x.apk", hosts))

    @Test fun `refuses a host outside the allowlist, including look-alikes`() {
        assertTrue(Urls.refuse("https://evil.example/x.apk", hosts)!!.contains("not allow-listed"))
        assertTrue(Urls.refuse("https://github.com.evil.example/x.apk", hosts)!!.contains("not allow-listed"))
        assertTrue(Urls.refuse("https://notgithub.com/x.apk", hosts)!!.contains("not allow-listed"))
    }

    @Test fun `refuses user info, odd ports and fragments`() {
        assertEquals("user info in URL", Urls.refuse("https://github.com@evil.example/x.apk", setOf("evil.example", "github.com")))
        assertEquals("port 8443 is not 443", Urls.refuse("https://github.com:8443/x.apk", hosts))
        assertEquals("fragment in URL", Urls.refuse("https://github.com/x.apk#frag", hosts))
    }

    @Test fun `refuses what is not a URL`() {
        assertEquals("not a URL", Urls.refuse("https://git hub.com/x", hosts))
        assertEquals("no host", Urls.refuse("https:///x.apk", hosts))
    }

    @Test fun `cleartext localhost is refused by default and allowed only when the debug flag says so, and only on localhost`() {
        assertEquals("scheme 'http' is not https", Urls.refuse("http://localhost:18080/lock.json", setOf("localhost")))
        assertNull(Urls.refuse("http://localhost:18080/lock.json", setOf("localhost"), cleartextLocalhost = true))
        assertEquals("scheme 'http' is not https", Urls.refuse("http://github.com/x.apk", setOf("github.com"), cleartextLocalhost = true))
        assertTrue(Urls.refuse("http://localhost:18080/x", setOf("github.com"), cleartextLocalhost = true)!!.contains("not allow-listed"))
    }

    @Test fun `isAllowed mirrors refuse`() {
        assertTrue(Urls.isAllowed("https://github.com/x.apk", hosts))
        assertFalse(Urls.isAllowed("https://evil.example/x.apk", hosts))
    }

    @Test fun `hex encodes lower-case and recognises only lower-case sha256`() {
        assertEquals("00ff10", Hex.encode(byteArrayOf(0, -1, 16)))
        assertTrue(Hex.isSha256(Fixtures.SHA_A))
        assertFalse(Hex.isSha256(Fixtures.SHA_A.uppercase()))
        assertFalse(Hex.isSha256(Fixtures.SHA_A.dropLast(1)))
    }
}
