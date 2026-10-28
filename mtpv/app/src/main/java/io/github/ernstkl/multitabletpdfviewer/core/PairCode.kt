package io.github.ernstkl.multitabletpdfviewer.core

import java.security.MessageDigest
import java.util.Random

/**
 * The pairing code, compared by eye on both screens (numeric comparison, as in Bluetooth). It comes
 * from both device certificates as each side sees them in its TLS session and from a nonce of each
 * side, so with someone in the middle the two screens show different codes.
 *
 * The controller commits to its nonce ([commit]) before it learns the secondary's, and reveals it
 * only after; an attacker in the middle has to fix its own nonce on each side before it sees the
 * other, so it cannot try certificates or nonces until the codes match and gets one guess in 10^6.
 *
 * Fingerprints, nonces and commitments are lowercase hex, as on the wire.
 */
object PairCode {
    const val DIGITS = 6
    const val NONCE_BYTES = 32

    private val LABEL = "MTPV pair v2".encodeToByteArray()

    /** SHA-256 of a certificate's DER encoding, the device's identity. */
    fun fingerprint(der: ByteArray): String = hex(sha256(der))

    /** [random] should be a SecureRandom. */
    fun newNonce(random: Random): String = hex(ByteArray(NONCE_BYTES).also { random.nextBytes(it) })

    fun commit(nonce: String): String {
        require(isHex(nonce, NONCE_BYTES)) { "bad nonce" }
        return hex(sha256(nonce.encodeToByteArray()))
    }

    /** Whether [nonce] is the one [commit] was made from, compared in constant time. */
    fun verify(commit: String, nonce: String): Boolean =
        isHex(nonce, NONCE_BYTES) && isHex(commit, 32) &&
            MessageDigest.isEqual(commit.encodeToByteArray(), commit(nonce).encodeToByteArray())

    /**
     * The code both screens show: SHA-256 of a label, the secondary's fingerprint [fpS], the
     * controller's [fpC] and the nonces [nc] and [ns] (all fixed length, in this order), its first 8
     * bytes as an unsigned number mod 10^6, with leading zeros.
     */
    fun derive(fpS: String, fpC: String, nc: String, ns: String): String {
        require(isHex(fpS, 32) && isHex(fpC, 32)) { "bad fingerprint" }
        require(isHex(nc, NONCE_BYTES) && isHex(ns, NONCE_BYTES)) { "bad nonce" }
        val d = MessageDigest.getInstance("SHA-256")
        d.update(LABEL)
        for (s in listOf(fpS, fpC, nc, ns)) d.update(s.encodeToByteArray())
        val n = d.digest().take(8).fold(0UL) { acc, b -> (acc shl 8) or b.toUByte().toULong() }
        return (n % 1_000_000UL).toString().padStart(DIGITS, '0')
    }

    /** "123 456". */
    fun format(code: String): String = code.chunked(3).joinToString(" ")

    /** Exactly [bytes] bytes as lowercase hex. */
    fun isHex(s: String, bytes: Int): Boolean = s.length == 2 * bytes && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
