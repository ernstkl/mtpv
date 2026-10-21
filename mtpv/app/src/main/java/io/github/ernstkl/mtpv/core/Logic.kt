package io.github.ernstkl.mtpv.core

/** Which page each screen shows. Slot 0 shows [start], slot i shows start + i. Pages are 1-based. */
object Paging {
    /** Slot id of the controller itself in an arrangement; other slots are secondary UUIDs. */
    const val SELF = "self"

    /** Page shown in [slot], or 0 if that screen is blank (past the end). */
    fun pageFor(start: Int, slot: Int, pageCount: Int): Int {
        val page = start + slot
        return if (page in 1..pageCount) page else 0
    }

    /** Largest start that still fills as many screens as possible: the last page lands on the last slot. */
    fun maxStart(pageCount: Int, slots: Int): Int = maxOf(1, pageCount - slots + 1)

    fun clampStart(start: Int, pageCount: Int, slots: Int): Int = start.coerceIn(1, maxStart(pageCount, slots))
}

/**
 * Secondaries apply a display command only if it is newer than the last one from the same controller.
 * A command from a different controller always wins, so a reinstalled controller can take over.
 */
object VersionGate {
    fun accepts(lastController: String?, lastVersion: Long, controller: String, version: Long): Boolean =
        controller != lastController || version > lastVersion
}

/** Picks cached files to delete: least recently used first, never the ones in `keep`. */
object Eviction {
    data class Entry(val id: String, val size: Long, val lastUsed: Long)

    fun victims(entries: List<Entry>, keep: Set<String>, maxFiles: Int, maxBytes: Long): List<String> {
        var files = 0
        var bytes = 0L
        // Kept files count towards the limits first.
        val (kept, others) = entries.partition { it.id in keep }
        kept.forEach { files++; bytes += it.size }
        val victims = mutableListOf<String>()
        for (e in others.sortedByDescending { it.lastUsed }) {
            if (files + 1 > maxFiles || bytes + e.size > maxBytes) {
                victims += e.id
            } else {
                files++
                bytes += e.size
            }
        }
        return victims
    }
}
