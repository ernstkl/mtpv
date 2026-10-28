package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressScanTest {
    private fun ip(s: String) = AddressScan.parse(s)!!
    private fun scan(vararg locals: Pair<String, Int>) =
        AddressScan.candidates(locals.map { (a, p) -> ip(a) to p }).map(AddressScan::format)

    @Test
    fun wholeSlash24WithoutOwnNetworkAndBroadcast() {
        val c = scan("192.168.49.1" to 24)
        assertEquals(253, c.size)
        assertEquals("192.168.49.2", c.first())
        assertEquals("192.168.49.254", c.last())
    }

    @Test
    fun largerNetworkOnlyTheOwnSlash24() {
        val c = scan("10.1.2.3" to 8)
        assertEquals(253, c.size)
        assertEquals("10.1.2.1", c.first())
        assertEquals("10.1.2.254", c.last())
        assertFalse("10.1.2.3" in c)
    }

    @Test
    fun smallerNetwork() {
        assertEquals(listOf("192.168.1.9", "192.168.1.10", "192.168.1.11", "192.168.1.13", "192.168.1.14"), scan("192.168.1.12" to 29))
        assertEquals(listOf("192.168.1.13"), scan("192.168.1.12" to 31))
        assertEquals(emptyList<String>(), scan("192.168.1.12" to 32))
    }

    @Test
    fun publicAndMobileNetworksAreSkipped() {
        assertEquals(emptyList<String>(), scan("100.64.3.7" to 24, "8.8.8.8" to 24, "169.254.1.1" to 16))
    }

    @Test
    fun severalNetworksInOrderWithoutDuplicatesOrOwnAddresses() {
        val c = scan("192.168.49.1" to 24, "192.168.178.20" to 24, "192.168.178.21" to 24)
        assertEquals(253 + 252, c.size)
        assertEquals("192.168.49.2", c.first())
        assertEquals("192.168.178.1", c[253])
        assertFalse("192.168.178.20" in c || "192.168.178.21" in c)
    }

    @Test
    fun privateRanges() {
        assertTrue(AddressScan.isPrivate(ip("10.0.0.1")))
        assertTrue(AddressScan.isPrivate(ip("172.16.0.1")))
        assertTrue(AddressScan.isPrivate(ip("172.31.255.1")))
        assertFalse(AddressScan.isPrivate(ip("172.32.0.1")))
        assertTrue(AddressScan.isPrivate(ip("192.168.0.1")))
        assertFalse(AddressScan.isPrivate(ip("192.169.0.1")))
    }

    @Test
    fun parseAndFormat() {
        assertEquals("255.0.10.1", AddressScan.format(ip("255.0.10.1")))
        assertEquals(ip("192.168.49.1"), AddressScan.fromBytes(byteArrayOf(192.toByte(), 168.toByte(), 49, 1)))
        assertNull(AddressScan.parse("1.2.3"))
        assertNull(AddressScan.parse("1.2.3.256"))
        assertNull(AddressScan.parse("a.b.c.d"))
    }
}
