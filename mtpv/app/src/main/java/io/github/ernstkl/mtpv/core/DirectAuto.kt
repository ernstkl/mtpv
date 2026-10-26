package io.github.ernstkl.mtpv.core

/**
 * When the direct network starts and stops by itself: the controller starts it when it cannot reach
 * its screens otherwise, a secondary joins it when no controller reaches it otherwise. Both check
 * only while MTPV is on screen; in the background the network is off anyway.
 */
object DirectAuto {
    /** A paired screen out of reach this long makes the controller start its network. */
    const val MISSING_MS = 10_000L

    /** No request from a controller for this long makes a secondary look for the network. */
    const val NO_CONTACT_MS = 15_000L

    /**
     * Controller, checked every few seconds: start if [auto], not [running], not stopped by hand
     * this session, and a screen in the arrangement has been out of reach for [missingFor] ms
     * (null: all reachable).
     */
    fun controllerStart(auto: Boolean, running: Boolean, stoppedByHand: Boolean, missingFor: Long?): Boolean =
        auto && !running && !stoppedByHand && missingFor != null && missingFor >= MISSING_MS

    /** Controller, when Screens is opened: start if nothing can be reached here, so the others can join. */
    fun startOnScreens(auto: Boolean, running: Boolean, anyOnline: Boolean, nearby: Int): Boolean =
        auto && !running && !anyOnline && nearby == 0

    enum class Join { START, STOP, NONE }

    /**
     * Secondary, checked every few seconds. With a [code], start looking for the network when no
     * controller has reached this device for [sinceContact] ms. Stop looking once a controller
     * reaches it another way, unless the code was just typed ([manual]). In the network already
     * ([joined]), it stays.
     */
    fun secondary(code: String?, active: Boolean, joined: Boolean, manual: Boolean, sinceContact: Long): Join = when {
        code == null -> Join.NONE
        !active && sinceContact >= NO_CONTACT_MS -> Join.START
        active && !joined && !manual && sinceContact < NO_CONTACT_MS -> Join.STOP
        else -> Join.NONE
    }
}
