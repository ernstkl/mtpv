package io.github.ernstkl.mtpv.core

/** One PDF of a set. [name] is its file name, which also keys its place in the stored order. */
data class Song(val name: String, val fileId: String, val pageCount: Int)

/**
 * Songs shown as one stream of pages, as if they were one PDF: page 1 is the first page of the
 * first song, and the pages of the next song follow right after the last page of the one before.
 * Pages are 1-based; a single PDF is a set of one song.
 */
class SongSet private constructor(val name: String, val songs: List<Song>) {
    /** Global page of each song's first page, plus one entry past the end. */
    private val firsts: IntArray = IntArray(songs.size + 1).also { f ->
        f[0] = 1
        songs.forEachIndexed { i, s -> f[i + 1] = f[i] + s.pageCount }
    }

    val totalPages: Int get() = firsts.last() - 1

    /** Global page of the first page of song [index]. */
    fun firstPageOf(index: Int): Int = firsts[index]

    /** Song that global [page] belongs to, or -1 past the end. */
    fun songIndexAt(page: Int): Int {
        if (page !in 1..totalPages) return -1
        val i = firsts.binarySearch(page)
        return if (i >= 0) i else -i - 2
    }

    /** File and page within it shown at global [page], or null for a blank screen. */
    fun locate(page: Int): PageKey? {
        val i = songIndexAt(page)
        return if (i < 0) null else PageKey(songs[i].fileId, page - firsts[i] + 1)
    }

    /** Global page that shows [key], or 0 if the set does not contain it. The first song with that file wins. */
    fun pageOf(key: PageKey): Int {
        val i = songs.indexOfFirst { it.fileId == key.fileId && key.page in 1..it.pageCount }
        return if (i < 0) 0 else firsts[i] + key.page - 1
    }

    fun move(index: Int, delta: Int): SongSet = SongSet(name, songs.moved(index, delta))

    override fun equals(other: Any?) = other is SongSet && name == other.name && songs == other.songs

    override fun hashCode() = name.hashCode() * 31 + songs.hashCode()

    override fun toString() = "SongSet($name, $songs)"

    companion object {
        /** Files without pages are left out quietly. */
        fun of(name: String, songs: List<Song>) = SongSet(name, songs.filter { it.pageCount > 0 })
    }
}

/** Order of the songs in a folder, kept by file name. */
object SetOrder {
    /**
     * Stored order for a folder that may have changed: files still present keep their positions,
     * missing ones drop out, new ones go to the end in natural order.
     */
    fun merge(stored: List<String>, present: Collection<String>): List<String> {
        val presentSet = present.toSet()
        val kept = stored.distinct().filter { it in presentSet }
        val keptSet = kept.toSet()
        return kept + present.filter { it !in keptSet }.distinct().sortedWith(NaturalOrder)
    }

    /**
     * Stored order after the songs were rearranged to [songs]. Files that are no songs (unreadable,
     * no pages) keep their names in the order, at the end, so they are not taken for new files.
     */
    fun reordered(stored: List<String>, songs: List<String>): List<String> {
        val songSet = songs.toSet()
        return songs + stored.filter { it !in songSet }
    }
}

/** Case-insensitive, with numbers compared by value: "2 Song" comes before "10 Song". */
object NaturalOrder : Comparator<String> {
    private val RUN = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ra = RUN.findAll(a).map { it.value }.iterator()
        val rb = RUN.findAll(b).map { it.value }.iterator()
        while (ra.hasNext() && rb.hasNext()) {
            val c = compareRuns(ra.next(), rb.next())
            if (c != 0) return c
        }
        return when {
            ra.hasNext() -> 1
            rb.hasNext() -> -1
            else -> a.compareTo(b)
        }
    }

    private fun compareRuns(x: String, y: String): Int {
        if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0')
            val ny = y.trimStart('0')
            if (nx.length != ny.length) return nx.length - ny.length
            return nx.compareTo(ny)
        }
        return x.lowercase().compareTo(y.lowercase())
    }
}

/** Moves the element at [index] by [delta] places; out of range leaves the list as it is. */
fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val to = index + delta
    if (index !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(index)) }
}
