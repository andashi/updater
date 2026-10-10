package org.andashi.updater.core

import org.andashi.updater.core.Fixtures.entry
import org.andashi.updater.core.Fixtures.installed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyTest {
    private val e = entry(versionCode = 11, sha256 = Fixtures.SHA_A, size = 1000)
    private val good = ArchiveInfo("org.example.app", 11, setOf(Fixtures.SIGNER_A))

    private fun refused(result: Verify.Result): String = (result as Verify.Result.Refused).reason

    @Test fun `accepts a file that agrees with the lock and the pin on every count`() =
        assertEquals(Verify.Result.Ok, Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good))

    @Test fun `refuses a size mismatch before hashing is even considered`() =
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 999, good)).startsWith("size 999"))

    @Test fun `refuses a hash mismatch`() =
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_B, 1000, good)).startsWith("sha256"))

    @Test fun `refuses what is not an APK`() =
        assertEquals("not an APK", refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, null)))

    @Test fun `refuses another package or version under the right hash`() {
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good.copy(pkg = "org.other"))).startsWith("package org.other"))
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good.copy(versionCode = 12))).startsWith("versionCode 12"))
    }

    @Test fun `refuses a signer that is not the pin, and more than one signer`() {
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good.copy(signers = setOf(Fixtures.SIGNER_B)))).startsWith("signer"))
        assertTrue(refused(Verify.downloaded(e, Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good.copy(signers = setOf(Fixtures.SIGNER_A, Fixtures.SIGNER_B)))).contains("2 signers"))
    }

    @Test fun `refuses when the lock's signer and the pin disagree even if the file matches one of them`() =
        assertTrue(refused(Verify.downloaded(e.copy(signer = Fixtures.SIGNER_B), Fixtures.SIGNER_A, Fixtures.SHA_A, 1000, good)).contains("lock says"))

    @Test fun `before commit - proceed only when the session bytes match and the package is still behind`() {
        assertEquals(Verify.Commit.Proceed, Verify.beforeCommit(e, Fixtures.SHA_A, installed(versionCode = 10)))
        assertTrue(Verify.beforeCommit(e, Fixtures.SHA_B, installed(versionCode = 10)) is Verify.Commit.Refused)
        assertEquals(Verify.Commit.AlreadyCurrent(11), Verify.beforeCommit(e, Fixtures.SHA_A, installed(versionCode = 11, apkSha256 = Fixtures.SHA_A)))
        assertEquals(Verify.Commit.Proceed, Verify.beforeCommit(e, Fixtures.SHA_A, installed(versionCode = 11, apkSha256 = Fixtures.SHA_B)))
        assertEquals(Verify.Commit.Ahead(12), Verify.beforeCommit(e, Fixtures.SHA_A, installed(versionCode = 12)))
        assertTrue(Verify.beforeCommit(e, Fixtures.SHA_A, null) is Verify.Commit.Refused)
    }
}
