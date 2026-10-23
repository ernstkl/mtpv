package io.github.ernstkl.mtpv.core

/** A PDF as the folder lists it. [lastModified] is 0 if the provider does not know it. */
data class FolderFile(val name: String, val documentId: String, val size: Long, val lastModified: Long)

/** A folder file already copied into the cache. [pageCount] 0 means it is no readable PDF. */
data class StoredFile(val file: FolderFile, val fileId: String, val pageCount: Int)

/**
 * What the app remembers about a folder of PDFs: the song order and the songs left out of the
 * stream (both by file name), and the files it copied last time, so that unchanged files need
 * not be read and hashed again.
 */
data class SetRecord(
    val folder: String,
    val name: String,
    val order: List<String>,
    val files: List<StoredFile>,
    val excluded: Set<String> = emptySet(),
) {
    /** The stored copy of [f], if the folder still has the same file: same document, size and date. */
    fun unchanged(f: FolderFile): StoredFile? =
        files.firstOrNull { it.file == f && f.lastModified > 0 }
}
