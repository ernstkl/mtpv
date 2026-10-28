package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PairCodeTest {
    private val fpS = "a".repeat(64)
    private val fpC = "b".repeat(64)
    private val nc = "0".repeat(64)
    private val ns = "f".repeat(64)

    @Test
    fun fixedVectors() {
        // Computed independently with Python's hashlib.
        assertEquals("364282", PairCode.derive(fpS, fpC, nc, ns))
        assertEquals("004379", PairCode.derive(fpS, fpC, nc, "%064x".format(329)))
        assertEquals("60e05bd1b195af2f94112fa7197a5c88289058840ce7c6df9693756bc6250f55", PairCode.commit(nc))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", PairCode.fingerprint("hello".encodeToByteArray()))
    }

    @Test
    fun deterministic() {
        assertEquals(PairCode.derive(fpS, fpC, nc, ns), PairCode.derive(fpS, fpC, nc, ns))
    }

    @Test
    fun everyInputChangesTheCode() {
        val code = PairCode.derive(fpS, fpC, nc, ns)
        assertEquals("201346", PairCode.derive(fpC, fpS, nc, ns))
        assertNotEquals(code, PairCode.derive(fpS, fpC, ns, nc))
        assertNotEquals(code, PairCode.derive(fpS, fpC, "1" + nc.drop(1), ns))
        assertNotEquals(code, PairCode.derive(fpS, fpC, nc, "e" + ns.drop(1)))
        assertNotEquals(code, PairCode.derive("c".repeat(64), fpC, nc, ns))
    }

    @Test
    fun alwaysSixDigits() {
        val r = Random(1)
        repeat(1000) {
            val code = PairCode.derive(fpS, fpC, PairCode.newNonce(r), PairCode.newNonce(r))
            assertTrue(code, Regex("[0-9]{6}").matches(code))
        }
    }

    @Test
    fun commitVerifies() {
        val r = Random(2)
        val n = PairCode.newNonce(r)
        val c = PairCode.commit(n)
        assertTrue(PairCode.verify(c, n))
        assertFalse(PairCode.verify(c, PairCode.newNonce(r)))
        assertFalse(PairCode.verify(c, n.uppercase()))
        assertFalse(PairCode.verify(c, n.dropLast(2)))
        assertFalse(PairCode.verify("", n))
    }

    @Test
    fun malformedInputRejected() {
        assertThrows(IllegalArgumentException::class.java) { PairCode.derive(fpS.dropLast(1), fpC, nc, ns) }
        assertThrows(IllegalArgumentException::class.java) { PairCode.derive(fpS, fpC, nc, ns + "00") }
        assertThrows(IllegalArgumentException::class.java) { PairCode.derive(fpS, fpC.uppercase(), nc, ns) }
        assertThrows(IllegalArgumentException::class.java) { PairCode.commit("xyz") }
    }

    @Test
    fun formatInGroupsOfThree() {
        assertEquals("004 379", PairCode.format("004379"))
    }
}
