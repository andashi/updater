package org.andashi.updater.core

import org.andashi.updater.core.Fixtures.entry
import org.andashi.updater.core.Fixtures.installed
import org.andashi.updater.core.Fixtures.lock
import org.andashi.updater.core.Fixtures.managed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {
    private val self = "org.andashi.updater"
    private val abi = "arm64-v8a"

    private fun decide(installed: InstalledPackage?, lock: Lock? = lock(entry(versionCode = 11)), managed: Config.ManagedApp = managed()) =
        Decide.state(managed, lock, abi, installed, self)

    @Test fun `current when installed equals the lock in version and bytes`() =
        assertEquals(AppState.Current("org.example.app", 11), decide(installed(versionCode = 11)))

    @Test fun `the same version with other bytes is behind, marked as a rebuild`() {
        val s = decide(installed(versionCode = 11, apkSha256 = Fixtures.SHA_B))
        assertTrue(s is AppState.Behind && s.rebuilt)
    }

    @Test fun `the same version whose APK cannot be hashed is unverifiable, never current`() {
        val s = decide(installed(versionCode = 11, apkSha256 = null))
        assertTrue(s is AppState.Unverifiable && s.reason.contains("hashed"))
    }

    @Test fun `not owner even when the version matches - the next update would prompt`() =
        assertEquals(AppState.NotOwner("org.example.app", "dev.imranr.obtainium", 11, 11), decide(installed(versionCode = 11, installer = "dev.imranr.obtainium")))

    @Test fun `behind when the lock is newer and the updater owns the package`() {
        val s = decide(installed(versionCode = 10))
        assertTrue(s is AppState.Behind)
        assertEquals(11, (s as AppState.Behind).entry.versionCode)
    }

    @Test fun `ahead when installed is newer - never a downgrade`() =
        assertEquals(AppState.Ahead("org.example.app", 12, 11), decide(installed(versionCode = 12)))

    @Test fun `not owner when somebody else or nobody is installer of record and an update is due`() {
        assertEquals(AppState.NotOwner("org.example.app", "dev.imranr.obtainium", 10, 11), decide(installed(versionCode = 10, installer = "dev.imranr.obtainium")))
        assertEquals(AppState.NotOwner("org.example.app", null, 10, 11), decide(installed(versionCode = 10, installer = null)))
    }

    @Test fun `not owner when another package holds update ownership, even as installer of record`() {
        val owned = InstalledPackage("org.example.app", 10, setOf(Fixtures.SIGNER_A), self, true, Fixtures.SHA_A, updateOwner = "dev.imranr.obtainium")
        assertEquals(AppState.NotOwner("org.example.app", "dev.imranr.obtainium", 10, 11), decide(owned))
        val own = InstalledPackage("org.example.app", 10, setOf(Fixtures.SIGNER_A), self, true, Fixtures.SHA_A, updateOwner = self)
        assertTrue(decide(own) is AppState.Behind)
    }

    @Test fun `missing when not installed - the updater never first-installs`() =
        assertEquals(AppState.Missing("org.example.app"), decide(null))

    @Test fun `unverifiable when the installed signer is not the pin, before anything else is believed`() {
        val s = decide(installed(versionCode = 10, signer = Fixtures.SIGNER_B))
        assertTrue(s is AppState.Unverifiable && s.reason.contains("installed signer"))
    }

    @Test fun `unverifiable when the lock's signer disagrees with the pin`() {
        val s = decide(installed(versionCode = 10), lock = lock(entry(versionCode = 11, signer = Fixtures.SIGNER_B)))
        assertTrue(s is AppState.Unverifiable && s.reason.contains("lock signer"))
    }

    @Test fun `unverifiable without a lock or without an entry for this abi`() {
        assertTrue((decide(installed(), lock = null) as AppState.Unverifiable).reason.contains("no lock"))
        val s = decide(installed(), lock = lock(entry(versionCode = 11, abi = "x86_64")))
        assertTrue((s as AppState.Unverifiable).reason.contains("no lock entry for abi arm64-v8a"))
    }

    @Test fun `a package with two signers is unverifiable`() {
        val two = InstalledPackage("org.example.app", 10, setOf(Fixtures.SIGNER_A, Fixtures.SIGNER_B), self, true, Fixtures.SHA_A)
        assertTrue(decide(two) is AppState.Unverifiable)
    }

    @Test fun `all keeps the config's order, foreign and netFalse are reported from the same facts`() {
        val config = Config(
            schemaVersion = 1, zone = "home",
            lock = Config.LockSource(url = "https://github.com/l.json", allowedHosts = listOf("github.com")),
            managed = listOf(managed("b.app"), managed("a.app")),
            checks = Config.Checks(netFalse = listOf("org.andashi.home", "helium314.keyboard")),
        )
        val packages = Fixtures.FakePackages(
            listOf(
                installed("a.app", 11), installed("b.app", 10),
                installed("org.andashi.home", 1, internet = false), installed("helium314.keyboard", 1, internet = true),
                installed("dev.imranr.obtainium", 5, installer = null),
            ),
        )
        val lock = lock(entry("a.app", 11), entry("b.app", 11))
        val states = Decide.all(config, lock, abi, packages, self)
        assertEquals(listOf("b.app", "a.app"), states.map { it.pkg })
        assertTrue(states[0] is AppState.Behind && states[1] is AppState.Current)
        assertEquals(listOf("org.andashi.home", "helium314.keyboard", "dev.imranr.obtainium"), Decide.foreign(config, packages).map { it.pkg })
        assertEquals(listOf("helium314.keyboard"), Decide.netFalseViolations(config, packages))
    }
}
