package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongSetTest {
    // A: 2 pages, B: 3 pages, C: 1 page -> global pages 1-2, 3-5, 6.
    private val set = SongSet.of("Gig", listOf(Song("A", "a", 2), Song("B", "b", 3), Song("C", "c", 1)))

    @Test
    fun pagesRunOnAcrossSongs() {
        assertEquals(6, set.totalPages)
        assertEquals(
            listOf(PageKey("a", 1), PageKey("a", 2), PageKey("b", 1), PageKey("b", 2), PageKey("b", 3), PageKey("c", 1)),
            (1..6).map { set.locate(it) },
        )
    }

    @Test
    fun pastTheEndIsBlank() {
        assertNull(set.locate(0))
        assertNull(set.locate(7))
        assertEquals(-1, set.songIndexAt(7))
    }

    @Test
    fun songsAndTheirFirstPages() {
        assertEquals(listOf(1, 3, 6), (0..2).map { set.firstPageOf(it) })
        assertEquals(listOf(0, 0, 1, 1, 1, 2), (1..6).map { set.songIndexAt(it) })
    }

    @Test
    fun pageOfIsTheInverseOfLocate() {
        (1..6).forEach { assertEquals(it, set.pageOf(set.locate(it)!!)) }
        assertEquals(0, set.pageOf(PageKey("x", 1)))
        assertEquals(0, set.pageOf(PageKey("a", 3)))
    }

    @Test
    fun filesWithoutPagesAreLeftOut() {
        val s = SongSet.of("Gig", listOf(Song("A", "a", 2), Song("Empty", "e", 0), Song("C", "c", 1)))
        assertEquals(listOf("A", "C"), s.songs.map { it.name })
        assertEquals(PageKey("c", 1), s.locate(3))
    }

    @Test
    fun emptySet() {
        val s = SongSet.of("Gig", emptyList())
        assertEquals(0, s.totalPages)
        assertNull(s.locate(1))
    }

    @Test
    fun singlePdfIsOneSong() {
        val s = SongSet.of("x.pdf", listOf(Song("x.pdf", "x", 4)))
        assertEquals(PageKey("x", 4), s.locate(4))
        assertNull(s.locate(5))
    }

    @Test
    fun moveSong() {
        val moved = set.move(2, -2)
        assertEquals(listOf("C", "A", "B"), moved.songs.map { it.name })
        assertEquals(PageKey("a", 1), moved.locate(2))
        assertEquals(set, set.move(0, -1))
    }

    @Test
    fun excludedSongsAddNoPages() {
        val s = set.exclude(1, true)
        assertEquals(3, s.totalPages)
        assertEquals(listOf(PageKey("a", 1), PageKey("a", 2), PageKey("c", 1)), (1..3).map { s.locate(it) })
        assertEquals(listOf(0, 0, 2), (1..3).map { s.songIndexAt(it) })
        assertEquals(listOf("A", "B", "C"), s.songs.map { it.name })
        assertEquals(listOf(1, 0, 2), (0..2).map { s.numberOf(it) })
        assertEquals(2, s.shownSongs)
        assertEquals(0, s.pageOf(PageKey("b", 1)))
        assertEquals(3, s.pageOf(PageKey("c", 1)))
        assertEquals(set, s.exclude(1, false))
    }

    @Test
    fun excludedAtTheEdges() {
        val first = set.exclude(0, true)
        assertEquals(listOf(1, 1, 1, 2), (1..4).map { first.songIndexAt(it) })
        assertEquals(PageKey("b", 1), first.locate(1))
        val last = set.exclude(2, true)
        assertEquals(5, last.totalPages)
        assertEquals(1, last.songIndexAt(5))
        assertNull(last.locate(6))
    }

    @Test
    fun allExcluded() {
        val s = (0..2).fold(set) { acc, i -> acc.exclude(i, true) }
        assertEquals(0, s.totalPages)
        assertNull(s.locate(1))
        assertEquals(-1, s.songIndexAt(1))
    }

    @Test
    fun equalByContent() {
        assertEquals(set, SongSet.of("Gig", set.songs.toList()))
    }
}

class SetOrderTest {
    @Test
    fun newFolderIsInNaturalOrder() {
        assertEquals(listOf("2 b.pdf", "10 a.pdf", "Zz.pdf"), SetOrder.merge(emptyList(), listOf("Zz.pdf", "10 a.pdf", "2 b.pdf")))
    }

    @Test
    fun keptFilesKeepTheirPlaces() {
        assertEquals(listOf("C", "A", "B"), SetOrder.merge(listOf("C", "A", "B"), listOf("A", "B", "C")))
    }

    @Test
    fun missingFilesDropOut() {
        assertEquals(listOf("C", "B"), SetOrder.merge(listOf("C", "A", "B"), listOf("B", "C")))
    }

    @Test
    fun newFilesGoToTheEnd() {
        assertEquals(listOf("C", "A", "B", "D", "E"), SetOrder.merge(listOf("C", "A"), listOf("E", "A", "B", "C", "D")))
    }

    @Test
    fun reorderedKeepsFilesThatAreNoSongs() {
        assertEquals(listOf("B", "A", "Broken"), SetOrder.reordered(listOf("A", "Broken", "B"), listOf("B", "A")))
    }

    @Test
    fun renamedFileIsNew() {
        assertEquals(listOf("B", "A2"), SetOrder.merge(listOf("A", "B"), listOf("A2", "B")))
    }
}

class NaturalOrderTest {
    private fun sorted(vararg s: String) = s.sortedWith(NaturalOrder)

    @Test
    fun numbersByValue() {
        assertEquals(listOf("Song 2", "Song 10", "Song 100"), sorted("Song 100", "Song 2", "Song 10"))
        assertEquals(listOf("1", "02", "3"), sorted("3", "02", "1"))
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"))
    }

    @Test
    fun prefixFirst() {
        assertEquals(listOf("Song", "Song 1"), sorted("Song 1", "Song"))
    }

    @Test
    fun consistentForEqualLookingNames() {
        assertEquals(listOf("01", "1"), sorted("1", "01"))
        assertEquals(listOf("A", "a"), sorted("a", "A"))
    }
}

class MovedTest {
    @Test
    fun movesWithinRange() {
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(1, -1))
        assertEquals(listOf(2, 3, 1), listOf(1, 2, 3).moved(0, 2))
    }

    @Test
    fun outOfRangeKeepsList() {
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(2, 1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(5, 0))
    }
}
