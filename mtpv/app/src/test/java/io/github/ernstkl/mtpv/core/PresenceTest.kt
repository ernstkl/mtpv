package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    private val none = emptyList<String>()
    private val a = listOf("A")

    @Test
    fun onlyAnUnpairedSecondaryIsOpen() {
        assertTrue(Presence.open(none))
        assertFalse(Presence.open(a))
    }

    @Test
    fun pairedRefusesOtherControllers() {
        assertTrue(Presence.mayPair(none, "B"))
        assertTrue(Presence.mayPair(a, "A"))
        assertFalse(Presence.mayPair(a, "B"))
    }
}
