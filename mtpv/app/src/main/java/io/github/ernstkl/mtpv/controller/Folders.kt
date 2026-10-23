package io.github.ernstkl.mtpv.controller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.ernstkl.mtpv.core.FolderFile
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
 * each of them in filesDir/sets/<key>.json. The key is the hash of the folder's document ID, not of
 * the tree URI, so the same folder is recognised however it was picked.
 */
class Folders(private val context: Context) {
    private val dir = File(context.filesDir, "sets").apply { mkdirs() }
    private val resolver get() = context.contentResolver

    /** Keeps read access to [tree] across restarts. */
    fun keepAccess(tree: Uri) = resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun name(tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: tree.lastPathSegment ?: "Folder"
    }

    /** PDFs directly in the folder; subfolders are not looked into. */
    fun pdfs(tree: Uri): List<FolderFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
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
                if (mime != "application/pdf" && !name.endsWith(".pdf", ignoreCase = true)) continue
                files += FolderFile(
                    name, c.getString(0),
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    lastModified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw IOException("cannot read the folder")
        return files
    }

    fun open(tree: Uri, f: FolderFile): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, f.documentId))
            ?: throw IOException("cannot read ${f.name}")

    fun load(tree: Uri): SetRecord? {
        val f = file(tree)
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
            SetRecord(o.getString("treeUri"), o.getString("name"), order, files)
        }.getOrNull()
    }

    fun save(tree: Uri, r: SetRecord) {
        val files = JSONArray()
        r.files.forEach {
            files.put(
                JSONObject().put("name", it.file.name).put("documentId", it.file.documentId).put("size", it.file.size)
                    .put("lastModified", it.file.lastModified).put("fileId", it.fileId).put("pageCount", it.pageCount)
            )
        }
        val o = JSONObject().put("treeUri", r.treeUri).put("name", r.name).put("order", JSONArray(r.order)).put("files", files)
            .put("lastOpened", System.currentTimeMillis())
        val f = file(tree)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    private fun file(tree: Uri): File {
        val id = "${tree.authority}\n${DocumentsContract.getTreeDocumentId(tree)}"
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key.json")
    }
}
