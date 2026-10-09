package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigTest {
    private fun config(
        mode: String = "fetch",
        url: String = "https://raw.githubusercontent.com/andashi/provisioning/main/apks/lock.json",
        hosts: String = "\"raw.githubusercontent.com\",\"github.com\"",
        managed: String = """{"pkg":"org.example.app","label":"App","signer":"${Fixtures.SIGNER_A}"}""",
        extra: String = "",
    ) = """{
      "schemaVersion": 1, "zone": "anon",
      "lock": {"mode": "$mode", "url": "$url", "maxAgeDays": 14, "allowedHosts": [$hosts]},
      "managed": [$managed],
      "checks": {"netFalse": ["org.andashi.home"]} $extra
    }"""

    private fun refused(text: String): String {
        try {
            Config.parse(text)
        } catch (e: ConfigFormatException) {
            return e.message!!
        }
        fail("expected the config to be refused"); return ""
    }

    @Test fun `parses with defaults for what the host left out`() {
        val c = Config.parse(config())
        assertEquals("anon", c.zone)
        assertEquals(6, c.schedule.checkEveryHours)
        assertEquals(24, c.policy.constraints.boundHours)
        assertEquals(Fixtures.SIGNER_A, c.pinFor("org.example.app"))
        assertFalse(c.hostOnly)
        assertEquals(setOf("raw.githubusercontent.com", "github.com"), c.lockPolicy.allowedHosts)
    }

    @Test fun `host-only needs no url and no hosts`() {
        val c = Config.parse(config(mode = "host-only", url = "", hosts = ""))
        assertTrue(c.hostOnly)
    }

    @Test fun `refuses an unknown schema version`() =
        assertTrue(refused(config().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).contains("schemaVersion 2"))

    @Test fun `refuses a lock url outside its own allowlist`() =
        assertTrue(refused(config(url = "https://evil.example/lock.json")).contains("lock.url"))

    @Test fun `refuses fetch mode without hosts`() =
        assertTrue(refused(config(hosts = "")).contains("allowedHosts is empty"))

    @Test fun `refuses a mode it does not know`() =
        assertTrue(refused(config(mode = "magic")).contains("neither fetch nor host-only"))

    @Test fun `refuses an empty managed set and a bad or duplicate pin`() {
        assertTrue(refused(config(managed = "")).contains("managed is empty"))
        assertTrue(refused(config(managed = """{"pkg":"a.b","signer":"nope"}""")).contains("signer is not 64 hex"))
        val twice = """{"pkg":"a.b","signer":"${Fixtures.SIGNER_A}"},{"pkg":"a.b","signer":"${Fixtures.SIGNER_A}"}"""
        assertTrue(refused(config(managed = twice)).contains("listed twice"))
    }

    @Test fun `refuses nonsense bounds`() {
        assertTrue(refused(config(extra = ""","schedule":{"checkEveryHours":0}""")).contains("checkEveryHours"))
        assertTrue(refused(config(extra = ""","policy":{"constraints":{"boundHours":0}}""")).contains("boundHours"))
        assertTrue(refused(config().replace("\"maxAgeDays\": 14", "\"maxAgeDays\": 0")).contains("maxAgeDays"))
    }

    @Test fun `refuses what is not JSON`() = assertTrue(refused("nope").startsWith("not an updater config"))

    @Test fun `a localhost lock url is refused unless the debug flag is set`() {
        val text = config(url = "http://localhost:18080/lock.json", hosts = "\"localhost\"")
        assertTrue(refused(text).contains("lock.url"))
        assertTrue(Config.parse(text, cleartextLocalhost = true).lockPolicy(true).cleartextLocalhost)
    }
}
