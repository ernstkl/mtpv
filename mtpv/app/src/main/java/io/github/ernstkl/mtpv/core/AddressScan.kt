package io.github.ernstkl.mtpv.core

/**
 * Addresses the controller probes when mDNS finds no secondary: every host of each local private
 * IPv4 network, at most the /24 around the own address (a home network, a phone hotspot, a Wi-Fi
 * Direct group). Addresses are IPv4 as Int, most significant byte first.
 */
object AddressScan {
    /** Larger networks are scanned only in the /24 around the own address. */
    const val MIN_PREFIX = 24

    /**
     * Hosts to probe for the own [locals] (address to prefix length): each network in the given
     * order, ascending, without network and broadcast address and without own addresses. Networks
     * that are not private (RFC 1918) are skipped, so a mobile data link is never scanned.
     */
    fun candidates(locals: List<Pair<Int, Int>>): List<Int> {
        val own = locals.map { it.first }.toSet()
        return locals.filter { (a, prefix) -> isPrivate(a) && prefix in 0..32 }.flatMap { (a, prefix) ->
            val p = maxOf(prefix, MIN_PREFIX)
            val mask = if (p == 0) 0 else -1 shl (32 - p)
            val network = a and mask
            val size = 1L shl (32 - p)
            // /31 and /32 have no network or broadcast address (RFC 3021).
            val hosts = if (size <= 2) 0L until size else 1L until size - 1
            hosts.map { network + it.toInt() }
        }.distinct().filter { it !in own }
    }

    /** 10/8, 172.16/12 and 192.168/16. */
    fun isPrivate(a: Int): Boolean =
        a ushr 24 == 10 || a ushr 20 == 0xAC1 || a ushr 16 == 0xC0A8

    fun fromBytes(b: ByteArray): Int {
        require(b.size == 4)
        return b.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xFF) }
    }

    fun format(a: Int): String = (3 downTo 0).joinToString(".") { ((a ushr (8 * it)) and 0xFF).toString() }

    /** Dotted IPv4 address, or null. */
    fun parse(s: String): Int? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        return parts.fold(0) { acc, p -> (acc shl 8) or (p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null) }
    }
}
