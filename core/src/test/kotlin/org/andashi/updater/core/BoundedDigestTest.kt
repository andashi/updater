package org.andashi.updater.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDigestTest {
    @Test fun `hashes exactly the bytes accepted`() {
        val d = BoundedDigest(1000)
        assertTrue(d.update("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d.sha256())
        assertEquals(3, d.size)
        assertFalse(d.exceeded)
    }

    @Test fun `accepts a body of exactly the limit and refuses the byte after it`() {
        val d = BoundedDigest(4)
        assertTrue(d.update(ByteArray(4)))
        assertFalse(d.update(ByteArray(1)))
        assertTrue(d.exceeded)
        assertEquals(4, d.size)
    }

    @Test fun `refuses a chunk that would pass the limit without counting any of it, and stays refused`() {
        val d = BoundedDigest(10)
        assertTrue(d.update(ByteArray(8)))
        assertFalse(d.update(ByteArray(5)))
        assertEquals(8, d.size)
        assertFalse(d.update(ByteArray(1)))
    }

    @Test fun `a zero limit refuses everything - a declared size above the limit is the caller's refusal`() {
        val d = BoundedDigest(0)
        assertFalse(d.update(ByteArray(1)))
        assertTrue(d.exceeded)
    }
}
