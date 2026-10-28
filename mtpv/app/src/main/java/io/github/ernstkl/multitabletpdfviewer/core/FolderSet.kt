package io.github.ernstkl.multitabletpdfviewer.core

/** A PDF as the folder lists it. [lastModified] is 0 if the provider does not know it. */
data class FolderFile(val name: String, val documentId: String, val size: Long, val lastModified: Long)

/** A folder file already copied into the cache. [pageCount] 0 means it is no readable PDF. */
data class StoredFile(val file: FolderFile, val fileId: String, val pageCount: Int)

/** A song of a set as its record keeps it: the hash of its PDF when it was last read, and the ID of its notes. */
data class SongEntry(val fileId: String, val notesId: String)

/**
 * What the app remembers about a folder of PDFs (or a single PDF): the song order and the songs
 * left out of the stream (both by file name), the files it copied last time, so that unchanged
 * files need not be read and hashed again, and the songs with their notes. [id] is random and
 * stays when the record moves to another folder (see [SetAdoption]); the songs' notes hang off it.
 * [path] holds the folder names from the library folder down to this one, null if not known.
 */
data class SetRecord(
    val id: String,
    val folder: String,
    val name: String,
    val order: List<String>,
    val files: List<StoredFile>,
    val excluded: Set<String> = emptySet(),
    val songs: Map<String, SongEntry> = emptyMap(),
    val path: List<String>? = null,
) {
    /** The stored copy of [f], if the folder still has the same file: same document, size and date. */
    fun unchanged(f: FolderFile): StoredFile? =
        files.firstOrNull { it.file == f && f.lastModified > 0 }

    /** Key of the notes of song [name], see [NotesKey]. */
    fun notesKey(name: String): String? = songs[name]?.let { NotesKey.of(id, it.notesId) }
}

/** What reading a folder again did to its songs, see [SongReconcile]. */
data class SongChanges(
    val songs: Map<String, SongEntry>,
    /** Old name to new name of each renamed song. */
    val renamed: Map<String, String>,
    /** Notes IDs of the songs that are gone: their file left the folder, or its content changed. */
    val removed: List<String>,
    /** Names of the new songs; [songs] has them with new notes IDs. */
    val added: List<String>,
)

/**
 * A song is a file name in a set. Its notes go with it while the file keeps its name and content,
 * and with a rename; they are dropped when the file leaves the folder or its content changes.
 */
object SongReconcile {
    /**
     * [present] holds each PDF the folder lists now with its hash, null if it could not be read
     * this time (the song then stays as it is). Renames are a name gone and a new one with the
     * same hash; if several files qualify, document IDs ([oldDocs] by old name, [newDocs] by name)
     * pair them, and the rest count as removed and new.
     */
    fun reconcile(
        old: Map<String, SongEntry>,
        oldDocs: Map<String, String>,
        present: Map<String, String?>,
        newDocs: Map<String, String>,
        newId: () -> String,
    ): SongChanges {
        val songs = LinkedHashMap<String, SongEntry>()
        val removed = mutableListOf<String>()
        val fresh = mutableListOf<String>()
        for ((name, hash) in present) {
            val o = old[name]
            when {
                o == null -> if (hash != null) fresh += name
                hash == null || hash == o.fileId -> songs[name] = o
                else -> {
                    removed += o.notesId
                    fresh += name
                }
            }
        }
        val gone = old.keys.filter { it !in present }
        val renamed = LinkedHashMap<String, String>()
        val freshByHash = fresh.groupBy { present.getValue(it) }
        for ((hash, from) in gone.groupBy { old.getValue(it).fileId }) {
            val to = freshByHash[hash] ?: continue
            if (from.size == 1 && to.size == 1) {
                renamed[from[0]] = to[0]
            } else {
                for (g in from) {
                    val doc = oldDocs[g] ?: continue
                    to.firstOrNull { newDocs[it] == doc && it !in renamed.values }?.let { renamed[g] = it }
                }
            }
        }
        renamed.forEach { (from, to) -> songs[to] = old.getValue(from) }
        removed += gone.filter { it !in renamed }.map { old.getValue(it).notesId }
        val added = fresh.filter { it !in renamed.values }
        added.forEach { songs[it] = SongEntry(present.getValue(it)!!, newId()) }
        return SongChanges(songs, renamed, removed, added)
    }
}

/**
 * Moving the library gives each folder a new document ID, so its record is not found by key any
 * more. A folder without a record takes over the one with the same path below the library folder,
 * if most of that record's songs are still there.
 */
object SetAdoption {
    fun pick(records: List<SetRecord>, path: List<String>, present: Collection<String>): SetRecord? {
        val names = present.toSet()
        return records
            .filter { it.path == path }
            .map { it to it.songs.keys.count { n -> n in names } }
            .filter { (r, kept) -> 2 * kept >= r.songs.size }
            .maxByOrNull { it.second }?.first
    }
}

/** A song whose notes another song with the same PDF can copy: song [name] of set [set]. */
data class NotesSource(val set: String, val name: String, val notesKey: String)

/**
 * A new set starts without notes; a song gets those of another song only when asked to, from any
 * set, the same one included, whose PDF has exactly the same content.
 */
object NotesSources {
    /** The songs in [records] whose PDF is [fileId], except the one with notes [except]. */
    fun of(records: List<SetRecord>, fileId: String, except: String): List<NotesSource> =
        records.flatMap { r ->
            r.songs.filter { (_, e) -> e.fileId == fileId }.keys
                .map { NotesSource(r.name, it, r.notesKey(it)!!) }
                .filter { it.notesKey != except }
        }
}
