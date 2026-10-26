package io.github.ernstkl.mtpv

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.ernstkl.mtpv.core.LibraryEntry
import io.github.ernstkl.mtpv.core.PagePosition
import io.github.ernstkl.mtpv.core.Paging
import io.github.ernstkl.mtpv.core.Song
import io.github.ernstkl.mtpv.core.SongSet
import io.github.ernstkl.mtpv.core.ToolState
import io.github.ernstkl.mtpv.core.ToolSync
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Role { NONE, CONTROLLER, SECONDARY }

/**
 * A secondary as the controller remembers it. Host and port may be outdated; the UUID is the identity.
 * [token] is the secret the secondary handed out when it was paired with this controller.
 */
data class KnownSecondary(val uuid: String, val name: String, val host: String?, val port: Int, val token: String? = null)

/** A controller this secondary accepts commands from (secondary side). */
data class PairedController(val uuid: String, val name: String, val token: String)

/**
 * What the controller restores at the next start. [start] is a page in the stream of the set's
 * pages; [folder] is the document URI of the folder the set was read from, null for a single PDF.
 */
data class ControllerSession(
    val set: SongSet?,
    val folder: String?,
    val start: Int,
    val arrangement: List<String>,
    val known: Map<String, KnownSecondary>,
)

/** What a secondary shows, and where that page sits in the set. page 0 means blank. */
data class DisplayState(val fileId: String?, val page: Int, val version: Long, val controller: String?, val position: PagePosition? = null)

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("mtpv", Context.MODE_PRIVATE)

    val deviceUuid: String =
        sp.getString("uuid", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("uuid", it).apply() }

    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    var role: Role
        get() = runCatching { Role.valueOf(sp.getString("role", null)!!) }.getOrDefault(Role.NONE)
        set(value) = sp.edit().putString("role", value.name).apply()

    /** The about dialog opens by itself until it has been closed once. */
    var aboutShown: Boolean
        get() = sp.getBoolean("aboutShown", false)
        set(value) = sp.edit().putBoolean("aboutShown", value).apply()

    /** Note tool, finger switch and dark pages, the same on all screens: the controller's own, or the last it sent here. */
    var tools: ToolState
        get() = sp.getString("tools", null)?.let { runCatching { ToolSync.fromJson(JSONObject(it)) }.getOrNull() } ?: ToolState()
        set(value) = sp.edit().putString("tools", ToolSync.toJson(value).toString()).apply()

    /** The library folder: the tree URI picked under Library folder, null until one is chosen. */
    var library: String?
        get() = sp.getString("library", null)
        set(value) = sp.edit().putString("library", value).remove("libraryPath").apply()

    /** Where the Sets dialog was left: the folders from the library folder down (names and document IDs). */
    var libraryPath: List<LibraryEntry>
        get() = sp.getString("libraryPath", null)?.let { s ->
            runCatching {
                JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it) }.map { LibraryEntry(it.getString("name"), it.getString("id")) } }
            }.getOrNull()
        }.orEmpty()
        set(value) {
            val a = JSONArray()
            value.forEach { a.put(JSONObject().put("name", it.name).put("id", it.documentId)) }
            sp.edit().putString("libraryPath", a.toString()).apply()
        }

    /** Controller: the code of its direct network, kept until New code; null until first needed. */
    var directCode: String?
        get() = sp.getString("directCode", null)
        set(value) = sp.edit().putString("directCode", value).apply()

    /** Controller: the direct network starts by itself when the screens cannot be reached otherwise. */
    var directAuto: Boolean
        get() = sp.getBoolean("directAuto", true)
        set(value) = sp.edit().putBoolean("directAuto", value).apply()

    /** Secondary: the code of the direct network it joined last, null after Leave. */
    var directJoin: String?
        get() = sp.getString("directJoin", null)
        set(value) = sp.edit().putString("directJoin", value).apply()

    var lastVersion: Long
        get() = sp.getLong("lastVersion", 0)
        set(value) = sp.edit().putLong("lastVersion", value).apply()

    fun loadSession(): ControllerSession {
        val arrangement = sp.getString("arrangement", null)?.let { s ->
            JSONArray(s).let { a -> List(a.length()) { a.getString(it) } }
        } ?: listOf(Paging.SELF)
        val known = sp.getString("known", null)?.let { s ->
            JSONArray(s).let { a ->
                List(a.length()) { a.getJSONObject(it) }.map {
                    KnownSecondary(
                        it.getString("uuid"), it.getString("name"), it.optString("host").ifEmpty { null }, it.optInt("port"),
                        it.optString("token").ifEmpty { null },
                    )
                }
            }
        }.orEmpty().associateBy { it.uuid }
        return ControllerSession(
            set = sp.getString("set", null)?.let { runCatching { setFromJson(JSONObject(it)) }.getOrNull() },
            folder = sp.getString("folder", null),
            start = sp.getInt("start", 1),
            arrangement = arrangement,
            known = known,
        )
    }

    fun saveSession(s: ControllerSession) {
        val known = JSONArray()
        s.known.values.forEach {
            known.put(
                JSONObject().put("uuid", it.uuid).put("name", it.name).put("host", it.host ?: "").put("port", it.port)
                    .put("token", it.token ?: "")
            )
        }
        sp.edit()
            .putString("set", s.set?.let { setToJson(it).toString() })
            .putString("folder", s.folder)
            .putInt("start", s.start)
            .putString("arrangement", JSONArray(s.arrangement).toString())
            .putString("known", known.toString())
            .apply()
    }

    private fun setToJson(set: SongSet): JSONObject {
        val songs = JSONArray()
        set.songs.forEach {
            songs.put(
                JSONObject().put("name", it.name).put("fileId", it.fileId).put("notesKey", it.notesKey)
                    .put("pageCount", it.pageCount).put("excluded", it.excluded)
            )
        }
        return JSONObject().put("name", set.name).put("songs", songs)
    }

    private fun setFromJson(o: JSONObject): SongSet {
        val songs = o.getJSONArray("songs").let { a ->
            List(a.length()) { a.getJSONObject(it) }.map {
                Song(it.getString("name"), it.getString("fileId"), it.getString("notesKey"), it.getInt("pageCount"), it.optBoolean("excluded"))
            }
        }
        return SongSet.of(o.getString("name"), songs)
    }

    fun loadPaired(): List<PairedController> = sp.getString("paired", null)?.let { s ->
        JSONArray(s).let { a ->
            List(a.length()) { a.getJSONObject(it) }.map { PairedController(it.getString("uuid"), it.getString("name"), it.getString("token")) }
        }
    }.orEmpty()

    fun savePaired(list: List<PairedController>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("uuid", it.uuid).put("name", it.name).put("token", it.token)) }
        sp.edit().putString("paired", a.toString()).apply()
    }

    fun loadDisplay() = DisplayState(
        fileId = sp.getString("displayFileId", null),
        page = sp.getInt("displayPage", 0),
        version = sp.getLong("displayVersion", 0),
        controller = sp.getString("displayController", null),
        position = sp.getInt("displaySong", 0).takeIf { it > 0 }?.let {
            PagePosition(it, sp.getInt("displaySongs", 0), sp.getInt("displayPage", 0), sp.getInt("displayPages", 0))
        },
    )

    fun saveDisplay(d: DisplayState) {
        sp.edit()
            .putString("displayFileId", d.fileId)
            .putInt("displayPage", d.page)
            .putLong("displayVersion", d.version)
            .putString("displayController", d.controller)
            .putInt("displaySong", d.position?.song ?: 0)
            .putInt("displaySongs", d.position?.songs ?: 0)
            .putInt("displayPages", d.position?.pages ?: 0)
            .apply()
    }
}
