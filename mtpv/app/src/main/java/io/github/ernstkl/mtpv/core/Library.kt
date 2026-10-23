package io.github.ernstkl.mtpv.core

/**
 * A folder in the library as the Sets dialog lists it. [pdfs] and [folders] count its direct
 * children: PDFs, and subfolders that are not hidden.
 */
data class LibraryEntry(val name: String, val documentId: String, val pdfs: Int = 0, val folders: Int = 0)

/** What tapping a folder in the Sets dialog does. */
enum class LibraryTap { OPEN_AS_SET, BROWSE_INTO }

/**
 * The library: one folder picked by the user, browsed one level at a time. Any folder with PDFs
 * directly in it is a set, the library folder itself included.
 */
object Library {
    /** The deepest path the title shows in full; deeper ones skip the folders in between. */
    private const val TITLE_LEVELS = 3

    fun isHidden(name: String) = name.startsWith(".")

    fun isPdf(name: String, mime: String?) = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

    /** The folders worth showing: neither hidden nor without PDFs and subfolders, in natural order. */
    fun visible(entries: List<LibraryEntry>): List<LibraryEntry> =
        entries.filter { !isHidden(it.name) && (it.pdfs > 0 || it.folders > 0) }.sortedWith(compareBy(NaturalOrder) { it.name })

    /** A folder of songs without subfolders opens right away; one with subfolders is browsed into. */
    fun tapAction(e: LibraryEntry): LibraryTap = if (e.folders == 0) LibraryTap.OPEN_AS_SET else LibraryTap.BROWSE_INTO

    /** "Library / Gigs / 2026" for the folder names from the library down; deep paths become "Library / … / 2026". */
    fun title(names: List<String>): String =
        if (names.size <= TITLE_LEVELS) names.joinToString(" / ")
        else (listOf(names.first(), "…") + names.takeLast(TITLE_LEVELS - 1)).joinToString(" / ")
}
