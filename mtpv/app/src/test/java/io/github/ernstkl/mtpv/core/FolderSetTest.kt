package io.github.ernstkl.mtpv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderSetTest {
    private val a = FolderFile("A.pdf", "doc:A", 1000, 5)
    private val record = SetRecord("uri", "Gig", listOf("A.pdf"), listOf(StoredFile(a, "id-a", 3)))

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
        val r = SetRecord("uri", "Gig", emptyList(), listOf(StoredFile(a.copy(lastModified = 0), "id-a", 3)))
        assertNull(r.unchanged(a.copy(lastModified = 0)))
    }
}
