package io.github.ernstkl.mtpv

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.ChecksSdkIntAtLeast
import io.github.ernstkl.mtpv.core.Paging
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Role { NONE, CONTROLLER, SECONDARY }

/** Discovery needs NsdManager.registerServiceInfoCallback (Android 14); older tablets can only be secondaries. */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
val controllerSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * A secondary as the controller remembers it. Host and port may be outdated; the UUID is the identity.
 * [token] is the secret the secondary handed out when it was paired with this controller.
 */
data class KnownSecondary(val uuid: String, val name: String, val host: String?, val port: Int, val token: String? = null)

/** A controller this secondary accepts commands from (secondary side). */
data class PairedController(val uuid: String, val name: String, val token: String)

/** What the controller restores at the next start. */
data class ControllerSession(
    val fileId: String?,
    val fileName: String?,
    val pageCount: Int,
    val start: Int,
    val arrangement: List<String>,
    val known: Map<String, KnownSecondary>,
)

/** What a secondary shows. page 0 means blank. */
data class DisplayState(val fileId: String?, val page: Int, val version: Long, val controller: String?)

class Prefs(private val context: Context) {
    private val sp = context.getSharedPreferences("mtpv", Context.MODE_PRIVATE)

    val deviceUuid: String =
        sp.getString("uuid", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("uuid", it).apply() }

    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    var role: Role
        get() = runCatching { Role.valueOf(sp.getString("role", null)!!) }.getOrDefault(Role.NONE)
        set(value) = sp.edit().putString("role", value.name).apply()

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
            fileId = sp.getString("fileId", null),
            fileName = sp.getString("fileName", null),
            pageCount = sp.getInt("pageCount", 0),
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
            .putString("fileId", s.fileId)
            .putString("fileName", s.fileName)
            .putInt("pageCount", s.pageCount)
            .putInt("start", s.start)
            .putString("arrangement", JSONArray(s.arrangement).toString())
            .putString("known", known.toString())
            .apply()
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
    )

    fun saveDisplay(d: DisplayState) {
        sp.edit()
            .putString("displayFileId", d.fileId)
            .putInt("displayPage", d.page)
            .putLong("displayVersion", d.version)
            .putString("displayController", d.controller)
            .apply()
    }
}
