package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LockTest {
    private val policy = LockPolicy(allowedHosts = setOf("github.com", "objects.githubusercontent.com", "dist.torproject.org"))

    private fun entry(
        pkg: String = "org.example.app",
        abi: String = "universal",
        versionCode: Long = 10,
        sha256: String = Fixtures.SHA_A,
        size: Long = 1000,
        signer: String = Fixtures.SIGNER_A,
        urls: String = "\"https://github.com/example/app/releases/download/v1/app.apk\"",
    ) = """{"pkg":"$pkg","label":"App","version":"1.0","versionCode":$versionCode,"abi":"$abi","file":"$abi/app.apk","sha256":"$sha256","size":$size,"signer":"$signer","urls":[$urls]}"""

    private fun lock(generated: String = "2026-10-01T05:21:03Z", vararg entries: String, version: Int = 1) =
        """{"lockVersion":$version,"generated":"$generated","entries":[${entries.joinToString(",")}]}"""

    private fun refused(text: String, previous: Lock? = null): String {
        try {
            Lock.parse(text, policy, previous)
        } catch (e: LockFormatException) {
            return e.message!!
        }
        fail("expected the lock to be refused: $text"); return ""
    }

    @Test fun `a well-formed lock parses and keeps unknown keys out of the way`() {
        val l = Lock.parse(lock(entries = arrayOf(entry())).replace("\"entries\"", "\"newKey\":true,\"entries\""), policy)
        assertEquals(1, l.entries.size)
        assertEquals(10, l.entries[0].versionCode)
    }

    @Test fun `refuses an entry without label or version - a lost key breaks parsing`() {
        assertTrue(refused(lock(entries = arrayOf(entry().replace("\"label\":\"App\",", "")))).startsWith("not a lock"))
        assertTrue(refused(lock(entries = arrayOf(entry().replace("\"version\":\"1.0\",", "")))).startsWith("not a lock"))
    }

    @Test fun `refuses a lockVersion it does not understand`() =
        assertTrue(refused(lock(entries = arrayOf(entry()), version = 2)).contains("lockVersion 2"))

    @Test fun `refuses an empty lock`() = assertTrue(refused(lock()).contains("no entries"))

    @Test fun `refuses what is not JSON`() = assertTrue(refused("<html>").startsWith("not a lock"))

    @Test fun `refuses a generated that is not an instant`() =
        assertTrue(refused(lock(generated = "yesterday", entries = arrayOf(entry()))).contains("not an instant"))

    @Test fun `refuses a lock older than the one held - rollback`() {
        val held = Lock.parse(lock(generated = "2026-10-02T00:00:00Z", entries = arrayOf(entry())), policy)
        assertTrue(refused(lock(generated = "2026-10-01T00:00:00Z", entries = arrayOf(entry())), held).contains("rollback refused"))
    }

    @Test fun `accepts a lock with the same generated as the one held`() {
        val held = Lock.parse(lock(entries = arrayOf(entry())), policy)
        Lock.parse(lock(entries = arrayOf(entry())), policy, held)
    }

    @Test fun `refuses a sha256 that is not 64 lower-case hex digits`() {
        assertTrue(refused(lock(entries = arrayOf(entry(sha256 = "abc")))).contains("sha256 is not 64 hex"))
        assertTrue(refused(lock(entries = arrayOf(entry(sha256 = Fixtures.SHA_A.uppercase())))).contains("sha256 is not 64 hex"))
    }

    @Test fun `refuses a signer that is not 64 hex digits`() =
        assertTrue(refused(lock(entries = arrayOf(entry(signer = "deadbeef")))).contains("signer is not 64 hex"))

    @Test fun `refuses a non-positive versionCode and size`() {
        assertTrue(refused(lock(entries = arrayOf(entry(versionCode = 0)))).contains("versionCode 0"))
        assertTrue(refused(lock(entries = arrayOf(entry(size = 0)))).contains("size 0"))
    }

    @Test fun `refuses a size above the limit`() =
        assertTrue(refused(lock(entries = arrayOf(entry(size = policy.maxBytes + 1)))).contains("exceeds the limit"))

    @Test fun `refuses an abi it does not know`() =
        assertTrue(refused(lock(entries = arrayOf(entry(abi = "armeabi-v7a")))).contains("abi 'armeabi-v7a'"))

    @Test fun `refuses a package name that is not one`() =
        assertTrue(refused(lock(entries = arrayOf(entry(pkg = "../etc")))).contains("not a package name"))

    @Test fun `refuses an entry without urls, a cleartext url and a host outside the allowlist`() {
        assertTrue(refused(lock(entries = arrayOf(entry(urls = "")))).contains("no urls"))
        assertTrue(refused(lock(entries = arrayOf(entry(urls = "\"http://github.com/x.apk\"")))).contains("not https"))
        assertTrue(refused(lock(entries = arrayOf(entry(urls = "\"https://evil.example/x.apk\"")))).contains("not allow-listed"))
    }

    @Test fun `refuses the same package and abi listed twice`() =
        assertTrue(refused(lock(entries = arrayOf(entry(), entry(versionCode = 11)))).contains("listed twice"))

    @Test fun `every problem is named, not only the first`() {
        val why = refused(lock(entries = arrayOf(entry(sha256 = "x", size = 0))))
        assertTrue(why.contains("sha256") && why.contains("size 0"))
    }

    @Test fun `entryFor prefers the abi-specific build over the universal one`() {
        val l = Lock.parse(lock(entries = arrayOf(entry(abi = "universal", versionCode = 10), entry(abi = "arm64-v8a", versionCode = 11))), policy)
        assertEquals(11, l.entryFor("org.example.app", "arm64-v8a")!!.versionCode)
        assertEquals(10, l.entryFor("org.example.app", "x86_64")!!.versionCode)
    }

    @Test fun `entryFor yields nothing for an unknown package or a foreign abi only`() {
        val l = Lock.parse(lock(entries = arrayOf(entry(abi = "x86_64"))), policy)
        assertNull(l.entryFor("org.example.app", "arm64-v8a"))
        assertNull(l.entryFor("org.other.app", "x86_64"))
    }
}

object Fixtures {
    const val SHA_A = "0c91ea7c979009f3447d825d6b78f1d0e2981eaa1d5003d1a1b3d04d3d0e73ff"
    const val SHA_B = "40f97ac3ee659d8b1f91dba8b84449fe3c14e05c1e82754375252d8746e6b1f5"
    const val SIGNER_A = "067a40c4193aad51ac87f9ddfdebb15e24a1850babfa4821c28c5c25c3fdc071"
    const val SIGNER_B = "86c5c455dfaf4985923a8f35adb31d0c9e0b957d7f94c2d2af6a2438aa960020"

    fun entry(pkg: String = "org.example.app", versionCode: Long = 10, abi: String = "universal", signer: String = SIGNER_A, sha256: String = SHA_A, size: Long = 1000) =
        LockEntry(pkg = pkg, label = "App", version = "1.0", versionCode = versionCode, abi = abi, sha256 = sha256, size = size, signer = signer, urls = listOf("https://github.com/x/y/releases/download/v1/app.apk"))

    fun lock(vararg entries: LockEntry, generated: String = "2026-10-01T05:21:03Z") = Lock(1, generated, entries.toList())

    fun installed(pkg: String = "org.example.app", versionCode: Long = 10, signer: String = SIGNER_A, installer: String? = "org.andashi.updater", internet: Boolean = true, apkSha256: String? = SHA_A) =
        InstalledPackage(pkg, versionCode, setOf(signer), installer, internet, apkSha256)

    fun managed(pkg: String = "org.example.app", signer: String = SIGNER_A) = Config.ManagedApp(pkg, "App", signer)

    class FakePackages(private val packages: List<InstalledPackage>, private val archives: Map<String, ArchiveInfo> = emptyMap()) : Packages {
        override fun installed(pkg: String) = packages.firstOrNull { it.pkg == pkg }
        override fun all() = packages
        override fun archive(path: String) = archives[path]
    }
}
