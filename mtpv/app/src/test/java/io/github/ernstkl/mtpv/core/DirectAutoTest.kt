package io.github.ernstkl.mtpv.core

import io.github.ernstkl.mtpv.core.DirectAuto.Join
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAutoTest {
    private val long = DirectAuto.MISSING_MS

    @Test
    fun controllerStartsWhenAScreenStaysAway() {
        assertTrue(DirectAuto.controllerStart(auto = true, running = false, stoppedByHand = false, missingFor = long))
        assertFalse(DirectAuto.controllerStart(auto = true, running = false, stoppedByHand = false, missingFor = long - 1))
        assertFalse(DirectAuto.controllerStart(auto = true, running = false, stoppedByHand = false, missingFor = null))
    }

    @Test
    fun controllerDoesNotStartWhenOffRunningOrStoppedByHand() {
        assertFalse(DirectAuto.controllerStart(auto = false, running = false, stoppedByHand = false, missingFor = long))
        assertFalse(DirectAuto.controllerStart(auto = true, running = true, stoppedByHand = false, missingFor = long))
        assertFalse(DirectAuto.controllerStart(auto = true, running = false, stoppedByHand = true, missingFor = long))
    }

    @Test
    fun screensStartsOnlyWhenNothingIsReachable() {
        assertTrue(DirectAuto.startOnScreens(auto = true, running = false, anyOnline = false, nearby = 0))
        assertFalse(DirectAuto.startOnScreens(auto = true, running = false, anyOnline = true, nearby = 0))
        assertFalse(DirectAuto.startOnScreens(auto = true, running = false, anyOnline = false, nearby = 1))
        assertFalse(DirectAuto.startOnScreens(auto = false, running = false, anyOnline = false, nearby = 0))
        assertFalse(DirectAuto.startOnScreens(auto = true, running = true, anyOnline = false, nearby = 0))
    }

    @Test
    fun secondaryLooksForTheNetworkOnlyWithoutContact() {
        val away = DirectAuto.NO_CONTACT_MS
        assertEquals(Join.START, DirectAuto.secondary("1", active = false, joined = false, manual = false, sinceContact = away))
        assertEquals(Join.NONE, DirectAuto.secondary("1", active = false, joined = false, manual = false, sinceContact = away - 1))
        assertEquals(Join.NONE, DirectAuto.secondary(null, active = false, joined = false, manual = false, sinceContact = away))
    }

    @Test
    fun secondaryStopsLookingWhenReachedAnotherWay() {
        assertEquals(Join.STOP, DirectAuto.secondary("1", active = true, joined = false, manual = false, sinceContact = 0))
        assertEquals(Join.NONE, DirectAuto.secondary("1", active = true, joined = true, manual = false, sinceContact = 0))
        assertEquals(Join.NONE, DirectAuto.secondary("1", active = true, joined = false, manual = true, sinceContact = 0))
        assertEquals(Join.NONE, DirectAuto.secondary("1", active = true, joined = false, manual = false, sinceContact = 60_000))
    }
}
