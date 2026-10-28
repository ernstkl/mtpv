package io.github.ernstkl.multitabletpdfviewer.core

import java.util.Random
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The code of a direct network (Wi-Fi Direct group): 16 random digits, shown on the controller and
 * typed on each secondary. They are the network's password and define its name, so a secondary
 * needs nothing but the code. Joining the network gives no control over the screens: MTPV's own
 * pairing still applies.
 */
object DirectCode {
    const val DIGITS = 16

    /** Wi-Fi Direct group names must start with "DIRECT-" and two characters. */
    const val NAME_PREFIX = "DIRECT-mt-MTPV-"

    /** The group owner (the controller) is always 192.168.49.1; the others get addresses next to it. */
    const val GROUP_PREFIX = "192.168.49."

    private val SALT = "MTPV direct network".encodeToByteArray()
    private const val ITERATIONS = 100_000

    /** [random] should be a SecureRandom. */
    fun generate(random: Random): String = buildString { repeat(DIGITS) { append('0' + random.nextInt(10)) } }

    /** The code typed, without spaces and dashes; null unless it is exactly 16 digits. */
    fun normalize(input: String): String? =
        input.filterNot { it.isWhitespace() || it == '-' }.takeIf { s -> s.length == DIGITS && s.all { it in '0'..'9' } }

    /** In groups of four: "4821 0937 5612 0384". */
    fun format(code: String): String = code.chunked(4).joinToString(" ")

    /**
     * The network name: [NAME_PREFIX] and 4 hex characters. The name is broadcast, so they come
     * from a slow function (PBKDF2, some 50 ms on a tablet): a fast hash would let an attacker rule
     * out most codes without a handshake, which would make 16 digits as weak as about 11.
     */
    fun networkName(code: String): String {
        val spec = PBEKeySpec(code.toCharArray(), SALT, ITERATIONS, 16)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return NAME_PREFIX + bits.joinToString("") { "%02x".format(it) }
    }

    fun passphrase(code: String): String = code

    /**
     * The address to keep for a secondary found at [found] while [stored] is known, on a device
     * that may be in the direct network and the home Wi-Fi at once. While the link is [online], an
     * address in the direct network ([groupPrefix]) is not given up for one outside it, so mDNS on
     * the home Wi-Fi does not move the secondary off the direct link. Offline, any found address wins.
     */
    fun preferAddress(stored: String?, found: String, groupPrefix: String, online: Boolean): String =
        if (online && stored != null && stored.startsWith(groupPrefix) && !found.startsWith(groupPrefix)) stored else found
}
