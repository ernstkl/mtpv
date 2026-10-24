package io.github.ernstkl.mtpv.controller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.ernstkl.mtpv.core.FolderFile
import io.github.ernstkl.mtpv.core.Library
import io.github.ernstkl.mtpv.core.LibraryEntry
import io.github.ernstkl.mtpv.core.LibraryLevel
import io.github.ernstkl.mtpv.core.NotesKey
import io.github.ernstkl.mtpv.core.SetRecord
import io.github.ernstkl.mtpv.core.SongEntry
import io.github.ernstkl.mtpv.core.StoredFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Folders of PDFs, reached through the Storage Access Framework, and what the app remembers about
 * each of them in filesDir/sets/<key>.json. A folder is a document URI below a granted tree: the
 * picked folder itself, or one below the library folder. The key is the hash of the folder's
 * document ID, not of the URI, so the same folder is recognised however it was reached. A single
 * PDF opened without a folder has a record of its own, keyed by its document.
 */
class Folders(private val context: Context) {
    private val dir = File(context.filesDir, "sets").apply { mkdirs() }
    private val resolver get() = context.contentResolver

    /** Keeps read access to the picked [tree] across restarts. */
    fun keepAccess(tree: Uri) = resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Releases every kept tree except those that [keep] (trees, or folders below them) are reached through. */
    fun releaseAllBut(keep: List<Uri>) {
        val needed = keep.mapNotNull { treeKey(it) }.toSet()
        resolver.persistedUriPermissions.filter { treeKey(it.uri) !in needed }.forEach {
            resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Authority and tree document ID: the same for a tree URI and every document URI built on it. */
    private fun treeKey(uri: Uri): Pair<String?, String>? =
        runCatching { uri.authority to DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

    fun name(folder: Uri): String =
        resolver.query(folder, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: DocumentsContract.getDocumentId(folder).substringAfterLast('/')

    /** PDFs directly in the folder; subfolders are not looked into. */
    fun pdfs(folder: Uri): List<FolderFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val files = mutableListOf<FolderFile>()
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == Document.MIME_TYPE_DIR) continue
                if (!Library.isPdf(name, mime)) continue
                files += FolderFile(
                    name, c.getString(0),
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw IOException("cannot read the folder")
        return files
    }

    /**
     * What the Sets dialog shows of [folder]: its PDFs and, for each subfolder, how many PDFs and
     * subfolders that holds. One query per subfolder, nothing deeper.
     */
    fun children(folder: Uri): LibraryLevel {
        val kids = list(folder) ?: throw IOException("cannot read the folder")
        val entries = kids.filter { it.isDir && !Library.isHidden(it.name) }.map { d ->
            val inner = list(DocumentsContract.buildDocumentUriUsingTree(folder, d.id)).orEmpty()
            LibraryEntry(d.name, d.id, inner.count { it.isPdf }, inner.count { it.isDir && !Library.isHidden(it.name) })
        }
        return LibraryLevel(kids.count { it.isPdf }, Library.visible(entries))
    }

    /** Document IDs from the top of [tree] down to [documentId], or null if the provider cannot tell. */
    fun pathTo(tree: Uri, documentId: String): List<String>? = runCatching {
        DocumentsContract.findDocumentPath(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId))?.path
    }.getOrNull()

    /** The folder at [path], folder names from the top of [tree] down; null if there is none. */
    fun find(tree: Uri, path: List<String>): Uri? {
        var folder = folderOf(tree)
        for (name in path) {
            val child = list(folder)?.firstOrNull { it.isDir && it.name == name } ?: return null
            folder = DocumentsContract.buildDocumentUriUsingTree(folder, child.id)
        }
        return folder
    }

    private class Child(val id: String, val name: String, val mime: String?) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
        val isPdf get() = !isDir && Library.isPdf(name, mime)
    }

    private fun list(folder: Uri): List<Child>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(children, columns, null, null, null)?.use { c ->
            val kids = mutableListOf<Child>()
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                kids += Child(c.getString(0), name, c.getString(2))
            }
            kids
        }
    }

    fun open(folder: Uri, f: FolderFile): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(folder, f.documentId))
            ?: throw IOException("cannot read ${f.name}")

    fun load(folder: Uri): SetRecord? = read(file(folder))

    /** The records of all sets, to find a moved set or other copies of a song. */
    fun all(): List<SetRecord> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull(::read)

    /** Forgets the record [r], after it moved to another folder. */
    fun remove(r: SetRecord) {
        file(Uri.parse(r.folder)).delete()
    }

    private fun read(f: File): SetRecord? {
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val order = o.getJSONArray("order").let { a -> List(a.length()) { a.getString(it) } }
            val files = o.getJSONArray("files").let { a ->
                List(a.length()) { a.getJSONObject(it) }.map {
                    StoredFile(
                        FolderFile(it.getString("name"), it.getString("documentId"), it.getLong("size"), it.getLong("lastModified")),
                        it.getString("fileId"), it.getInt("pageCount"),
                    )
                }
            }
            val excluded = o.optJSONArray("excluded")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().toSet()
            val songs = o.optJSONObject("songs")?.let { j ->
                j.keys().asSequence().associateWith { n -> j.getJSONObject(n).let { SongEntry(it.getString("fileId"), it.getString("notesId")) } }
            }.orEmpty()
            val path = o.optJSONArray("path")?.let { a -> List(a.length()) { a.getString(it) } }
            SetRecord(
                o.optString("id").ifEmpty { NotesKey.newId() }, o.getString("folder"), o.getString("name"), order, files,
                excluded, songs, path,
            )
        }.getOrNull()
    }

    fun save(folder: Uri, r: SetRecord) {
        val files = JSONArray()
        r.files.forEach {
            files.put(
                JSONObject().put("name", it.file.name).put("documentId", it.file.documentId).put("size", it.file.size)
                    .put("lastModified", it.file.lastModified).put("fileId", it.fileId).put("pageCount", it.pageCount)
            )
        }
        val songs = JSONObject()
        r.songs.forEach { (n, e) -> songs.put(n, JSONObject().put("fileId", e.fileId).put("notesId", e.notesId)) }
        val o = JSONObject().put("id", r.id).put("folder", r.folder).put("name", r.name).put("order", JSONArray(r.order))
            .put("files", files).put("excluded", JSONArray(r.excluded.toList())).put("songs", songs)
            .put("lastOpened", System.currentTimeMillis())
        r.path?.let { o.put("path", JSONArray(it)) }
        val f = file(folder)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    /** Keyed by the document ID; a single PDF from a provider without document IDs by its URI. */
    private fun file(folder: Uri): File {
        val doc = runCatching { DocumentsContract.getDocumentId(folder) }.getOrDefault(folder.toString())
        val id = "${folder.authority}\n$doc"
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key.json")
    }

    companion object {
        /** The top folder of a picked [tree], as a document URI. */
        fun folderOf(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }
}
