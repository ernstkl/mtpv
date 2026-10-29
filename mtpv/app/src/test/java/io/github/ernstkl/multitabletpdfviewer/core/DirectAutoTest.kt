package io.github.ernstkl.multitabletpdfviewer.core

import io.github.ernstkl.multitabletpdfviewer.core.DirectAuto.Join
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAutoTest {
    @Test
    fun controllerStartsAsSoonAsAScreenIsOutOfReach() {
        assertTrue(DirectAuto.controllerStart(running = false, stoppedByHand = false, missing = true))
        assertFalse(DirectAuto.controllerStart(running = false, stoppedByHand = false, missing = false))
    }

    @Test
    fun controllerDoesNotStartWhenRunningOrStoppedByHand() {
        assertFalse(DirectAuto.controllerStart(running = true, stoppedByHand = false, missing = true))
        assertFalse(DirectAuto.controllerStart(running = false, stoppedByHand = true, missing = true))
    }

    @Test
    fun controllerStopsTheNetworkWhenNotNeeded() {
        val unneeded = DirectAuto.UNNEEDED_MS
        assertTrue(DirectAuto.controllerStop(byItself = true, clients = 0, reachableFor = unneeded))
        assertFalse(DirectAuto.controllerStop(byItself = true, clients = 0, reachableFor = unneeded - 1))
        assertFalse(DirectAuto.controllerStop(byItself = true, clients = 0, reachableFor = null))
        assertFalse(DirectAuto.controllerStop(byItself = true, clients = 1, reachableFor = unneeded))
        assertFalse(DirectAuto.controllerStop(byItself = false, clients = 0, reachableFor = unneeded))
    }

    @Test
    fun screensStartsOnlyWhenNothingIsReachable() {
        assertTrue(DirectAuto.startOnScreens(running = false, anyOnline = false, nearby = 0))
        assertFalse(DirectAuto.startOnScreens(running = false, anyOnline = true, nearby = 0))
        assertFalse(DirectAuto.startOnScreens(running = false, anyOnline = false, nearby = 1))
        assertFalse(DirectAuto.startOnScreens(running = true, anyOnline = false, nearby = 0))
    }

    @Test
    fun secondaryLooksForTheNetworkOnlyWithoutContact() {
        val away = DirectAuto.NO_CONTACT_MS
        assertEquals(Join.START, secondary(active = false, sinceContact = away))
        assertEquals(Join.NONE, secondary(active = false, sinceContact = away - 1))
        assertEquals(Join.NONE, secondary(code = null, active = false, sinceContact = away))
    }

    @Test
    fun secondaryLooksSoonerWhenNoControllerCameSinceTheStart() {
        val first = DirectAuto.FIRST_CONTACT_MS
        assertEquals(Join.START, secondary(active = false, sinceContact = first, contacted = false))
        assertEquals(Join.NONE, secondary(active = false, sinceContact = first - 1, contacted = false))
        assertEquals(Join.NONE, secondary(active = false, sinceContact = first, contacted = true))
    }

    @Test
    fun secondaryLooksAtOnceWithoutWifi() {
        assertEquals(Join.START, secondary(active = false, sinceContact = 0, noWifi = true))
        assertEquals(Join.NONE, secondary(code = null, active = false, sinceContact = 0, noWifi = true))
    }

    @Test
    fun secondaryStopsLookingWhenReachedAnotherWay() {
        assertEquals(Join.STOP, secondary(contactWhileLooking = true))
        assertEquals(Join.NONE, secondary(joined = true, contactWhileLooking = true))
        assertEquals(Join.NONE, secondary(manual = true, contactWhileLooking = true))
        assertEquals(Join.NONE, secondary(sinceContact = 60_000))
    }

    @Test
    fun secondaryGoesOnLookingAfterLosingTheNetwork() {
        // The controller reached it a moment ago, through the network now gone.
        assertEquals(Join.NONE, secondary(sinceContact = 500, contactWhileLooking = false))
    }

    private fun secondary(
        code: String? = "1", active: Boolean = true, joined: Boolean = false, manual: Boolean = false,
        sinceContact: Long = 0, contactWhileLooking: Boolean = false, noWifi: Boolean = false,
        contacted: Boolean = true,
    ) = DirectAuto.secondary(code, active, joined, manual, sinceContact, contactWhileLooking, noWifi, contacted)
}
