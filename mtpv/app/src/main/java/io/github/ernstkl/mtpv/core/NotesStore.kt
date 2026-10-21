package io.github.ernstkl.mtpv.core

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
 * filesDir/notes/<fileId>/<page>.json. Each save increments the page's revision, which lets
 * [NotesMerge] tell whether a secondary's edits can simply replace the stored version.
 */
class NotesStore(context: Context) {
    data class Page(val rev: Long, val strokes: List<Stroke>)

    private val dir = File(context.filesDir, "notes")
    private val loaded = HashMap<PageKey, Page>()

    /** Increments on every change, so the UI can reload. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    @Synchronized
    fun load(key: PageKey): Page = loaded.getOrPut(key) {
        val f = file(key)
        if (!f.isFile) {
            Page(0, emptyList())
        } else {
            runCatching {
                val o = JSONObject(f.readText())
                Page(o.optLong("rev"), NotesJson.fromJson(o.optJSONArray("strokes")))
            }.getOrDefault(Page(0, emptyList()))
        }
    }

    @Synchronized
    fun save(key: PageKey, strokes: List<Stroke>): Page {
        val page = Page(load(key).rev + 1, strokes)
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

    private fun file(key: PageKey): File {
        require(PdfCache.isValidId(key.fileId)) { "invalid file id" }
        return File(File(dir, key.fileId), "${key.page}.json")
    }
}
