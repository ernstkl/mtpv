package io.github.ernstkl.multitabletpdfviewer.core

/**
 * Who may pair with a secondary. Paired with a controller, it is off the market: only that
 * controller finds it. Without one, it is open for pairing.
 */
object Presence {
    /** Open for pairing: paired with no controller. */
    fun open(paired: Collection<String>): Boolean = paired.isEmpty()

    /** [controller] may ask to be paired: when open, or again by a controller paired already. */
    fun mayPair(paired: Collection<String>, controller: String): Boolean = paired.isEmpty() || controller in paired
}
