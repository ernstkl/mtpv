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
import io.github.ernstkl.mtpv.core.SetRecord
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
 * document ID, not of the URI, so the same folder is recognised however it was reached.
 */
class Folders(private val context: Context) {
    private val dir = File(context.filesDir, "sets").apply { mkdirs() }
    private val resolver get() = context.contentResolver

    /** Keeps read access to the picked [tree] across restarts. */
    fun keepAccess(tree: Uri) = resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)

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

    fun load(folder: Uri): SetRecord? {
        val f = file(folder)
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
            SetRecord(o.getString("folder"), o.getString("name"), order, files, excluded)
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
        val o = JSONObject().put("folder", r.folder).put("name", r.name).put("order", JSONArray(r.order)).put("files", files)
            .put("excluded", JSONArray(r.excluded.toList()))
            .put("lastOpened", System.currentTimeMillis())
        val f = file(folder)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun file(folder: Uri): File {
        val id = "${folder.authority}\n${DocumentsContract.getDocumentId(folder)}"
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key.json")
    }

    companion object {
        /** The folder a URI stands for: a picked tree becomes the document URI of its top folder. */
        fun folderOf(uri: Uri): Uri =
            if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.size < 4) {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            } else {
                uri
            }
    }
}
