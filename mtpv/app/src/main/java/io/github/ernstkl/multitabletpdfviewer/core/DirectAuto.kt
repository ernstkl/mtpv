package io.github.ernstkl.multitabletpdfviewer.core

/**
 * When the direct network starts and stops by itself: the controller starts it as soon as a screen
 * is out of reach (so also right after it comes to the screen) and stops it again when the screens
 * are reached otherwise; a secondary joins it when no controller reaches it otherwise. The group is
 * up early on purpose: a join started before the group exists finds it only 4-10 s after it
 * appears, one started afterwards within 0.1-3.5 s. Both check only while MTPV is on screen; in the
 * background the network is off anyway.
 */
object DirectAuto {
    /** Started by itself, the network stops when all screens have been reached otherwise this long and none is in it. */
    const val UNNEEDED_MS = 30_000L

    /** No request from a controller for this long makes a secondary look for the network: two missed pings. */
    const val NO_CONTACT_MS = 7_000L

    /**
     * The same before any controller has reached it since MTPV came to the screen. A running
     * controller tries again at least every 5 s; looking without need stops at its first request.
     */
    const val FIRST_CONTACT_MS = 3_500L

    /**
     * Controller, checked every second: start if [auto], not [running], not stopped by hand this
     * session, and a screen in the arrangement is out of reach ([missing]).
     */
    fun controllerStart(auto: Boolean, running: Boolean, stoppedByHand: Boolean, missing: Boolean): Boolean =
        auto && !running && !stoppedByHand && missing

    /**
     * Controller, checked every second: stop the network it started by itself ([byItself]) when
     * no screen is in it ([clients]) and all have been reached otherwise for [reachableFor] ms
     * (null: one is out of reach).
     */
    fun controllerStop(byItself: Boolean, clients: Int, reachableFor: Long?): Boolean =
        byItself && clients == 0 && reachableFor != null && reachableFor >= UNNEEDED_MS

    /** Controller, when Screens is opened: start if nothing can be reached here, so the others can join. */
    fun startOnScreens(auto: Boolean, running: Boolean, anyOnline: Boolean, nearby: Int): Boolean =
        auto && !running && !anyOnline && nearby == 0

    enum class Join { START, STOP, NONE }

    /**
     * Secondary, checked every second. With a [code], start looking for the network when no
     * controller has reached this device for [sinceContact] ms (shorter while none has since MTPV
     * came to the screen, ![contacted]), at once without a Wi-Fi network ([noWifi]). Stop looking once a controller
     * reaches it another way, i.e. while looking ([contactWhileLooking]; contact from before came
     * through the network just lost, e.g. when the controller left the screen), unless the code was
     * just typed ([manual]). In the network already ([joined]), it stays.
     */
    fun secondary(
        code: String?, active: Boolean, joined: Boolean, manual: Boolean, sinceContact: Long, contactWhileLooking: Boolean,
        noWifi: Boolean, contacted: Boolean,
    ): Join = when {
        code == null -> Join.NONE
        !active && (noWifi || sinceContact >= if (contacted) NO_CONTACT_MS else FIRST_CONTACT_MS) -> Join.START
        active && !joined && !manual && contactWhileLooking -> Join.STOP
        else -> Join.NONE
    }
}
