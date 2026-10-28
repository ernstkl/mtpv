package io.github.ernstkl.multitabletpdfviewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderSetTest {
    private val a = FolderFile("A.pdf", "doc:A", 1000, 5)
    private val record = SetRecord("set", "uri", "Gig", listOf("A.pdf"), listOf(StoredFile(a, "id-a", 3)))

    @Test
    fun sameFileIsReused() {
        assertEquals("id-a", record.unchanged(a)?.fileId)
    }

    @Test
    fun changedSizeOrDateIsCopiedAgain() {
        assertNull(record.unchanged(a.copy(size = 1001)))
        assertNull(record.unchanged(a.copy(lastModified = 6)))
        assertNull(record.unchanged(a.copy(documentId = "doc:B")))
    }

    @Test
    fun unknownDateIsNeverTrusted() {
        val r = SetRecord("set", "uri", "Gig", emptyList(), listOf(StoredFile(a.copy(lastModified = 0), "id-a", 3)))
        assertNull(r.unchanged(a.copy(lastModified = 0)))
    }
}

class SongReconcileTest {
    private val old = mapOf("A.pdf" to SongEntry("ha", "na"), "B.pdf" to SongEntry("hb", "nb"))
    private var next = 0
    private fun newId() = "new${next++}"

    private fun reconcile(
        present: Map<String, String?>,
        old: Map<String, SongEntry> = this.old,
        oldDocs: Map<String, String> = emptyMap(),
        newDocs: Map<String, String> = emptyMap(),
    ) = SongReconcile.reconcile(old, oldDocs, present, newDocs, ::newId)

    @Test
    fun unchangedFolderKeepsItsSongs() {
        val c = reconcile(mapOf("A.pdf" to "ha", "B.pdf" to "hb"))
        assertEquals(old, c.songs)
        assertEquals(emptyList<String>(), c.removed + c.added)
    }

    @Test
    fun newFileIsANewSong() {
        val c = reconcile(mapOf("A.pdf" to "ha", "B.pdf" to "hb", "C.pdf" to "hc"))
        assertEquals(listOf("C.pdf"), c.added)
        assertEquals(SongEntry("hc", "new0"), c.songs["C.pdf"])
    }

    @Test
    fun copyInTheSameSetIsASongOfItsOwn() {
        val c = reconcile(mapOf("A.pdf" to "ha", "B.pdf" to "hb", "A after break.pdf" to "ha"))
        assertEquals(listOf("A after break.pdf"), c.added)
        assertEquals("na", c.songs.getValue("A.pdf").notesId)
        assertEquals("new0", c.songs.getValue("A after break.pdf").notesId)
    }

    @Test
    fun removedFileTakesItsNotes() {
        val c = reconcile(mapOf("A.pdf" to "ha"))
        assertEquals(listOf("nb"), c.removed)
        assertEquals(setOf("A.pdf"), c.songs.keys)
    }

    @Test
    fun unreadableFileStays() {
        val c = reconcile(mapOf("A.pdf" to "ha", "B.pdf" to null))
        assertEquals(old, c.songs)
        assertEquals(emptyList<String>(), c.removed)
    }

    @Test
    fun unreadableNewFileIsNoSongYet() {
        val c = reconcile(mapOf("A.pdf" to "ha", "B.pdf" to "hb", "C.pdf" to null))
        assertEquals(emptyList<String>(), c.added)
        assertEquals(old, c.songs)
    }

    @Test
    fun changedContentIsRemovalPlusNewSong() {
        val c = reconcile(mapOf("A.pdf" to "ha2", "B.pdf" to "hb"))
        assertEquals(listOf("na"), c.removed)
        assertEquals(listOf("A.pdf"), c.added)
        assertEquals(SongEntry("ha2", "new0"), c.songs["A.pdf"])
    }

    @Test
    fun renameKeepsTheNotes() {
        val c = reconcile(mapOf("A2.pdf" to "ha", "B.pdf" to "hb"))
        assertEquals(mapOf("A.pdf" to "A2.pdf"), c.renamed)
        assertEquals(SongEntry("ha", "na"), c.songs["A2.pdf"])
        assertEquals(emptyList<String>(), c.removed + c.added)
    }

    @Test
    fun ambiguousRenamesArePairedByDocument() {
        val old = mapOf("X1.pdf" to SongEntry("hx", "n1"), "X2.pdf" to SongEntry("hx", "n2"))
        val c = reconcile(
            mapOf("Y1.pdf" to "hx", "Y2.pdf" to "hx"), old,
            oldDocs = mapOf("X1.pdf" to "d1", "X2.pdf" to "d2"), newDocs = mapOf("Y1.pdf" to "d2", "Y2.pdf" to "d9"),
        )
        assertEquals(mapOf("X2.pdf" to "Y1.pdf"), c.renamed)
        assertEquals("n2", c.songs.getValue("Y1.pdf").notesId)
        assertEquals(listOf("n1"), c.removed)
        assertEquals(listOf("Y2.pdf"), c.added)
    }
}

class SetAdoptionTest {
    private fun record(id: String, path: List<String>?, vararg songs: String) =
        SetRecord(id, "uri-$id", "Set", songs.toList(), emptyList(), songs = songs.associateWith { SongEntry("h", it) }, path = path)

    @Test
    fun samePathAndMostFilesAdopts() {
        val r = record("1", listOf("Gigs", "May"), "A.pdf", "B.pdf", "C.pdf")
        assertEquals(r, SetAdoption.pick(listOf(r), listOf("Gigs", "May"), listOf("A.pdf", "B.pdf", "D.pdf")))
    }

    @Test
    fun otherPathOrOtherFilesDoNot() {
        val r = record("1", listOf("Gigs", "May"), "A.pdf", "B.pdf", "C.pdf")
        assertNull(SetAdoption.pick(listOf(r), listOf("Gigs", "June"), listOf("A.pdf", "B.pdf", "C.pdf")))
        assertNull(SetAdoption.pick(listOf(r), listOf("Gigs", "May"), listOf("A.pdf", "X.pdf", "Y.pdf")))
        assertNull(SetAdoption.pick(listOf(record("2", null, "A.pdf")), emptyList(), listOf("A.pdf")))
    }

    @Test
    fun bestMatchWins() {
        val a = record("1", listOf("S"), "A.pdf", "B.pdf")
        val b = record("2", listOf("S"), "A.pdf", "C.pdf")
        assertEquals(b, SetAdoption.pick(listOf(a, b), listOf("S"), listOf("A.pdf", "C.pdf")))
    }
}

class NotesSourcesTest {
    private fun record(id: String, vararg songs: Pair<String, String>) =
        SetRecord(id, "uri-$id", "Set $id", emptyList(), emptyList(), songs = songs.associate { (n, h) -> n to SongEntry(h, "n-$n") })

    @Test
    fun sameContentInAnySetButNotTheSongItself() {
        val a = record("a", "Song.pdf" to "h1", "Copy.pdf" to "h1", "Other.pdf" to "h2")
        val b = record("b", "Song (1).pdf" to "h1")
        val sources = NotesSources.of(listOf(a, b), "h1", a.notesKey("Song.pdf")!!)
        assertEquals(
            listOf(NotesSource("Set a", "Copy.pdf", "a/n-Copy.pdf"), NotesSource("Set b", "Song (1).pdf", "b/n-Song (1).pdf")),
            sources,
        )
    }
}

class NotesKeyTest {
    @Test
    fun keysAreTwoRandomIds() {
        val key = NotesKey.of(NotesKey.newId(), NotesKey.newId())
        assert(NotesKey.isValid(key))
        assert(!NotesKey.isValid("../x"))
        assert(!NotesKey.isValid("a".repeat(64)))
        assert(!NotesKey.isValid(NotesKey.newId()))
    }
}
