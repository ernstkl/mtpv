package io.github.ernstkl.multitabletpdfviewer.core

/**
 * One PDF of a set. [name] is its file name, which also keys its place in the stored order;
 * [fileId] names the PDF's content and [notesKey] the song's notes (see [NotesKey]).
 * An [excluded] song stays in the set's list but adds no pages to the stream.
 */
data class Song(val name: String, val fileId: String, val notesKey: String, val pageCount: Int, val excluded: Boolean = false) {
    val shownPages get() = if (excluded) 0 else pageCount
}

/** A page of a song: the PDF and the 1-based page in it to show, and where its notes are. */
data class SongPage(val fileId: String, val notesKey: String, val page: Int) {
    val key get() = PageKey(notesKey, page)
}

/**
 * Where a page sits in its set: song [song] of the [songs] shown, page [page] of the song's [pages].
 * [skip]: the screen offers « and » to the previous and next song, which only the leftmost one does.
 */
data class PagePosition(val song: Int, val songs: Int, val page: Int, val pages: Int, val skip: Boolean = false) {
    /** "3 of 10" for the song; null in a set of one song (a single PDF), which has no songs to tell apart. */
    val songLabel get() = if (songs > 1) "$song of $songs" else null

    /** "Page 1 of 3", the page counted within its song. */
    val pageLabel get() = "Page $page of $pages"

    val hasPrevious get() = song > 1
    val hasNext get() = song < songs
}

/**
 * Songs shown as one stream of pages, as if they were one PDF: page 1 is the first page of the
 * first song, and the pages of the next song follow right after the last page of the one before.
 * Pages are 1-based; a single PDF is a set of one song. Excluded songs are skipped.
 */
class SongSet private constructor(val name: String, val songs: List<Song>) {
    /** Global page of each song's first page, plus one entry past the end. */
    private val firsts: IntArray = IntArray(songs.size + 1).also { f ->
        f[0] = 1
        songs.forEachIndexed { i, s -> f[i + 1] = f[i] + s.shownPages }
    }

    val totalPages: Int get() = firsts.last() - 1

    /** Global page of the first page of song [index]; for an excluded song, that of the next song shown. */
    fun firstPageOf(index: Int): Int = firsts[index]

    /** Song that global [page] belongs to, or -1 past the end. Never an excluded song. */
    fun songIndexAt(page: Int): Int {
        if (page !in 1..totalPages) return -1
        // The last song starting at or before page: an excluded song starts where the next one does.
        var lo = 0
        var hi = songs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (firsts[mid] <= page) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Position of song [index] among the songs shown, 1-based; 0 for an excluded song. */
    fun numberOf(index: Int): Int =
        if (songs[index].excluded) 0 else (0..index).count { !songs[it].excluded }

    val shownSongs: Int get() = songs.count { !it.excluded }

    /** First page of the song shown after the one at global [page], or null if that is the last. */
    fun nextSongPage(page: Int): Int? {
        val i = songIndexAt(page)
        if (i < 0) return null
        return (i + 1 until songs.size).firstOrNull { !songs[it].excluded }?.let(::firstPageOf)
    }

    /** First page of the song shown before the one at global [page], or null if that is the first. */
    fun previousSongPage(page: Int): Int? {
        val i = songIndexAt(page)
        if (i < 0) return null
        return (i - 1 downTo 0).firstOrNull { !songs[it].excluded }?.let(::firstPageOf)
    }

    /** Song page shown at global [page], or null for a blank screen. */
    fun locate(page: Int): SongPage? {
        val i = songIndexAt(page)
        return if (i < 0) null else songs[i].let { SongPage(it.fileId, it.notesKey, page - firsts[i] + 1) }
    }

    /** Song and page within it shown at global [page], or null for a blank screen. */
    fun positionAt(page: Int): PagePosition? {
        val i = songIndexAt(page)
        return if (i < 0) null else PagePosition(numberOf(i), shownSongs, page - firsts[i] + 1, songs[i].pageCount)
    }

    /** Global page that shows [key], or 0 if the set does not contain it (or leaves its song out). */
    fun pageOf(key: PageKey): Int {
        val i = songs.indexOfFirst { it.notesKey == key.notesKey && key.page in 1..it.shownPages }
        return if (i < 0) 0 else firsts[i] + key.page - 1
    }

    fun move(index: Int, delta: Int): SongSet = SongSet(name, songs.moved(index, delta))

    fun exclude(index: Int, excluded: Boolean): SongSet =
        SongSet(name, songs.mapIndexed { i, s -> if (i == index) s.copy(excluded = excluded) else s })

    /** The same set with the song whose notes are [from] taking notes [to]. */
    fun withNotesKey(from: String, to: String): SongSet =
        SongSet(name, songs.map { if (it.notesKey == from) it.copy(notesKey = to) else it })

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
