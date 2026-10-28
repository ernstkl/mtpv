package io.github.ernstkl.multitabletpdfviewer.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object NotesJson {
    fun toJson(strokes: List<Stroke>): JSONArray = JSONArray().apply {
        strokes.forEach { s ->
            val pts = JSONArray()
            // Four decimals are well below a pixel on any tablet and keep the files small.
            s.points.forEach { pts.put(Math.round(it * 10_000) / 10_000.0) }
            put(
                JSONObject()
                    .put("id", s.id)
                    .put("tool", s.tool.name)
                    .put("color", s.color)
                    .put("width", s.width.toDouble())
                    .put("points", pts)
            )
        }
    }

    fun fromJson(a: JSONArray?): List<Stroke> {
        if (a == null) return emptyList()
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val pts = o.getJSONArray("points")
            Stroke(
                id = o.getLong("id"),
                tool = runCatching { Tool.valueOf(o.getString("tool")) }.getOrDefault(Tool.PEN),
                color = o.getInt("color"),
                width = o.getDouble("width").toFloat(),
                points = FloatArray(pts.length()) { pts.getDouble(it).toFloat() },
            )
        }
    }
}

/**
 * The controller's notes, the authoritative copy: one JSON file per page under
 * filesDir/notes/<set ID>/<notes ID>/<page>.json (see [NotesKey]). Each save increments the page's
 * revision, which lets [NotesMerge] tell whether a secondary's edits can simply replace the stored
 * version. A file's last-modified time is when the page last changed: it is written only on a change.
 */
class NotesStore(context: Context) {
    data class Page(val rev: Long, val strokes: List<Stroke>)

    private val dir = File(context.filesDir, "notes")
    private val loaded = HashMap<PageKey, Page>()
    /** Songs whose notes were deleted; late edits from a secondary must not bring them back. */
    private val deleted = HashSet<String>()

    /** Increments on every change, so the UI can reload. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    init {
        // Notes once were keyed by the PDF's hash alone; those are dropped.
        dir.listFiles { f -> PdfCache.isValidId(f.name) }?.forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun load(key: PageKey): Page = loaded.getOrPut(key) { read(file(key)) ?: Page(0, emptyList()) }

    private fun read(f: File): Page? {
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            Page(o.optLong("rev"), NotesJson.fromJson(o.optJSONArray("strokes")))
        }.getOrNull()
    }

    @Synchronized
    fun save(key: PageKey, strokes: List<Stroke>): Page {
        val page = Page(load(key).rev + 1, strokes)
        if (key.notesKey in deleted) return page
        val f = file(key)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(JSONObject().put("rev", page.rev).put("strokes", NotesJson.toJson(strokes)).toString())
        tmp.renameTo(f)
        loaded[key] = page
        _changes.value++
        return page
    }

    /** Stores notes edited on a secondary; returns true if they had to be merged with other changes. */
    @Synchronized
    fun saveFromSecondary(key: PageKey, strokes: List<Stroke>, baseRev: Long): Boolean {
        val cur = load(key)
        val (merged, conflict) = NotesMerge.merge(cur.strokes, cur.rev, strokes, baseRev)
        save(key, merged)
        return conflict
    }

    /** When the notes of song [notesKey] last changed, or null if it has none. */
    @Synchronized
    fun lastChanged(notesKey: String): Long? {
        val pages = pageFiles(notesKey)
        if (pages.none { read(it)?.strokes?.isNotEmpty() == true }) return null
        return pages.maxOf { it.lastModified() }
    }

    /** Gives song [to] a copy of the notes of song [from], with their times. */
    @Synchronized
    fun copy(from: String, to: String) {
        val target = songDir(to).apply { mkdirs() }
        pageFiles(from).forEach { f ->
            val copy = f.copyTo(File(target, f.name), overwrite = true)
            copy.setLastModified(f.lastModified())
        }
        loaded.keys.removeAll { it.notesKey == to }
        _changes.value++
    }

    /** Deletes the notes of song [notesKey], for good. */
    @Synchronized
    fun delete(notesKey: String) {
        deleted += notesKey
        loaded.keys.removeAll { it.notesKey == notesKey }
        songDir(notesKey).deleteRecursively()
        songDir(notesKey).parentFile?.delete() // the set's folder, if that was its last song
        _changes.value++
    }

    private fun pageFiles(notesKey: String): List<File> =
        songDir(notesKey).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().toList()

    private fun songDir(notesKey: String): File {
        require(NotesKey.isValid(notesKey)) { "invalid notes key" }
        return File(dir, notesKey)
    }

    private fun file(key: PageKey) = File(songDir(key.notesKey), "${key.page}.json")
}
