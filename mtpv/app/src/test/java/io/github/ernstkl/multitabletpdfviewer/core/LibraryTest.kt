package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    @Test
    fun hiddenAndEmptyFoldersAreLeftOut() {
        val entries = listOf(
            LibraryEntry("Gigs", "g", folders = 2),
            LibraryEntry(".thumbnails", "t", pdfs = 3),
            LibraryEntry("Empty", "e"),
            LibraryEntry("Lieder", "l", pdfs = 12),
        )
        assertEquals(listOf("Gigs", "Lieder"), Library.visible(entries).map { it.name })
    }

    @Test
    fun foldersAreInNaturalOrder() {
        val entries = listOf("10 Mai", "2 April", "b", "A").map { LibraryEntry(it, it, pdfs = 1) }
        assertEquals(listOf("2 April", "10 Mai", "A", "b"), Library.visible(entries).map { it.name })
    }

    @Test
    fun leafFolderOpensAsSetOthersAreBrowsed() {
        assertEquals(LibraryTap.OPEN_AS_SET, Library.tapAction(LibraryEntry("Lieder", "l", pdfs = 12)))
        assertEquals(LibraryTap.BROWSE_INTO, Library.tapAction(LibraryEntry("Gigs", "g", folders = 2)))
        assertEquals(LibraryTap.BROWSE_INTO, Library.tapAction(LibraryEntry("Mixed", "m", pdfs = 3, folders = 1)))
    }

    @Test
    fun titleShortensDeepPaths() {
        assertEquals("Library", Library.title(listOf("Library")))
        assertEquals("Library / Gigs / 2026", Library.title(listOf("Library", "Gigs", "2026")))
        assertEquals("Library / … / 2026 / May", Library.title(listOf("Library", "Gigs", "2026", "May")))
    }

    @Test
    fun pdfByTypeOrName() {
        assertTrue(Library.isPdf("song", "application/pdf"))
        assertTrue(Library.isPdf("Song.PDF", "application/octet-stream"))
        assertFalse(Library.isPdf("notes.txt", "text/plain"))
    }
}
