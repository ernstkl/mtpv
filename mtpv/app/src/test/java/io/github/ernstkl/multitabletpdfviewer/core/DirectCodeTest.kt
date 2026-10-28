package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DirectCodeTest {
    private val group = DirectCode.GROUP_PREFIX

    @Test
    fun generatedCodesAreSixteenDigits() {
        val r = Random(1)
        repeat(100) {
            val c = DirectCode.generate(r)
            assertEquals(c, DirectCode.normalize(c))
        }
        assertNotEquals(DirectCode.generate(r), DirectCode.generate(r))
    }

    @Test
    fun normalizeAcceptsGroupsAndRejectsTheRest() {
        assertEquals("4821093756120384", DirectCode.normalize("4821 0937 5612 0384"))
        assertEquals("4821093756120384", DirectCode.normalize(" 4821-0937-5612-0384\n"))
        assertNull(DirectCode.normalize("482109375612038"))
        assertNull(DirectCode.normalize("48210937561203845"))
        assertNull(DirectCode.normalize("4821 0937 5612 038a"))
        assertNull(DirectCode.normalize("４８２１０９３７５６１２０３８４")) // fullwidth digits
        assertNull(DirectCode.normalize(""))
    }

    @Test
    fun formatInGroupsOfFour() {
        assertEquals("4821 0937 5612 0384", DirectCode.format("4821093756120384"))
    }

    @Test
    fun networkNameIsDeterministicAndValid() {
        val name = DirectCode.networkName("4821093756120384")
        assertEquals(name, DirectCode.networkName("4821093756120384"))
        assertTrue(name.startsWith("DIRECT-mt-"))
        assertTrue(Regex("DIRECT-mt-MTPV-[0-9a-f]{4}").matches(name))
        assertTrue(name.encodeToByteArray().size <= 32)
        assertNotEquals(name, DirectCode.networkName("4821093756120385"))
    }

    @Test
    fun passphraseIsTheCode() {
        assertEquals("4821093756120384", DirectCode.passphrase("4821093756120384"))
    }

    @Test
    fun groupAddressKeptWhileOnline() {
        assertEquals("192.168.49.7", DirectCode.preferAddress("192.168.49.7", "192.168.178.53", group, online = true))
        assertEquals("192.168.49.9", DirectCode.preferAddress("192.168.49.7", "192.168.49.9", group, online = true))
    }

    @Test
    fun anyFoundAddressTakenOtherwise() {
        assertEquals("192.168.178.53", DirectCode.preferAddress("192.168.49.7", "192.168.178.53", group, online = false))
        assertEquals("192.168.49.7", DirectCode.preferAddress("192.168.178.53", "192.168.49.7", group, online = true))
        assertEquals("192.168.178.54", DirectCode.preferAddress("192.168.178.53", "192.168.178.54", group, online = true))
        assertEquals("192.168.178.53", DirectCode.preferAddress(null, "192.168.178.53", group, online = true))
    }
}
