package io.github.ernstkl.multitabletpdfviewer.controller

import io.github.ernstkl.multitabletpdfviewer.core.NotesJson
import io.github.ernstkl.multitabletpdfviewer.core.PairCode
import io.github.ernstkl.multitabletpdfviewer.core.PagePosition
import io.github.ernstkl.multitabletpdfviewer.core.Stroke
import io.github.ernstkl.multitabletpdfviewer.core.ToolState
import io.github.ernstkl.multitabletpdfviewer.core.ToolSync
import io.github.ernstkl.multitabletpdfviewer.net.Endpoint
import io.github.ernstkl.multitabletpdfviewer.net.Protocol
import io.github.ernstkl.multitabletpdfviewer.net.Tls
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The secondary does not (or no longer) accept this controller; it has to be paired again. */
class NotPairedException : IOException("not paired")

/** The secondary is paired with another controller. */
class AlreadyPairedException : IOException("paired with another controller")

/** The secondary shows another certificate than the one pinned at pairing: reinstalled, or someone else. */
class KeyChangedException(cause: Throwable) : IOException("new key", cause)

/** The secondary speaks API version [api] of [Protocol], not ours. */
class VersionMismatchException(api: Int) : IOException("API version $api")

/**
 * Blocking calls to one secondary's HTTPS API, trusting its certificate as [tls] says: pinned for a
 * paired secondary, any for probes. Every failure is an IOException.
 */
class SecondaryClient(private val ep: Endpoint, private val tls: Tls.Client = Tls.any()) {

    /** [open]: paired with no controller, so it may be paired here. */
    data class Info(val uuid: String, val name: String, val open: Boolean, val apiVersion: Int, val app: String)
    /** [tools] is null if the secondary sent none it could read. */
    data class State(val fileId: String?, val page: Int, val version: Long, val tools: ToolState?)
    data class Notes(
        val fileId: String?,
        val notesKey: String?,
        val page: Int,
        val baseRev: Long,
        val rev: Long,
        val dirty: Boolean,
        val strokes: List<Stroke>,
    )

    sealed interface DisplayResult {
        data object Ok : DisplayResult
        data class Stale(val currentVersion: Long) : DisplayResult
        data object UnknownFile : DisplayResult
        data object NotesUnsaved : DisplayResult
    }

    /** Shorter timeouts for probing an address that may have no secondary, or no device at all. */
    fun info(probe: Boolean = false): Info =
        (if (probe) request("GET", "/v1/info", readTimeoutMs = PROBE_READ_MS, connectTimeoutMs = PROBE_CONNECT_MS)
        else request("GET", "/v1/info")).let { (_, json) ->
        Info(json.getString("uuid"), json.getString("name"), json.optBoolean("open"), json.optInt("apiVersion"), json.optString("app"))
    }

    /** Asks to be paired, committing to a nonce ([PairCode.commit]); returns the request ID and the secondary's nonce. */
    fun pairRequest(controller: String, name: String, commit: String): Pair<String, String> {
        val body = JSONObject().put("controller", controller).put("name", name).put("commit", commit).toString().toByteArray()
        val (code, json) = request("POST", "/v1/pair", body, acceptErrors = true)
        if (code == 409 && json.optString("error") == Protocol.ERR_ALREADY_PAIRED) throw AlreadyPairedException()
        if (code != 200) throw IOException("POST /v1/pair: HTTP $code")
        val nonce = json.getString("nonce")
        if (!PairCode.isHex(nonce, PairCode.NONCE_BYTES)) throw IOException("bad nonce from $ep")
        return json.getString("requestId") to nonce
    }

    /** Reveals the nonce committed to; returns the status as [pairStatus]. */
    fun pairNonce(requestId: String, nonce: String): String {
        val body = JSONObject().put("nonce", nonce).toString().toByteArray()
        return request("POST", "/v1/pair/$requestId/nonce", body).second.getString("status")
    }

    /** pending, allowed, denied or unknown. */
    fun pairStatus(requestId: String): String = request("GET", "/v1/pair/$requestId").second.getString("status")

    fun files(): Set<String> = request("GET", "/v1/files").let { (_, json) ->
        json.getJSONArray("files").let { a -> List(a.length()) { a.getString(it) }.toSet() }
    }

    fun state(): State = request("GET", "/v1/state").let { (_, json) -> parseState(json) }

    fun notes(): Notes = request("GET", "/v1/notes").let { (_, json) ->
        Notes(
            fileId = json.optString("fileId").ifEmpty { null },
            notesKey = json.optString("notesKey").ifEmpty { null },
            page = json.optInt("page"),
            baseRev = json.optLong("baseRev"),
            rev = json.optLong("rev"),
            dirty = json.optBoolean("dirty"),
            strokes = NotesJson.fromJson(json.optJSONArray("strokes")),
        )
    }

    /** Sets note tool and finger switch on the secondary. */
    fun tools(tools: ToolState) {
        request("POST", "/v1/tool", ToolSync.toJson(tools).toString().toByteArray())
    }

    /** Hands over the code of the direct network, "" if there is none. */
    fun direct(code: String) {
        request("POST", "/v1/direct", JSONObject().put("code", code).toString().toByteArray())
    }

    /** Waits for page turns and tool picks entered on the secondary; returns the new sequence number and the events. */
    fun input(after: Long): Pair<Long, List<String>> {
        val (_, json) = request("GET", "/v1/input?after=$after", readTimeoutMs = (Protocol.INPUT_WAIT_MS + 5_000).toInt())
        val events = json.getJSONArray("events").let { a -> List(a.length()) { a.getString(it) } }
        return json.getLong("seq") to events
    }

    fun display(
        controller: String,
        version: Long,
        fileId: String?,
        notesKey: String?,
        page: Int,
        position: PagePosition?,
        notes: List<Stroke>,
        notesRev: Long,
        savedRev: Long,
    ): DisplayResult {
        val body = JSONObject()
            .put("controller", controller)
            .put("version", version)
            .put("fileId", fileId ?: "")
            .put("notesKey", notesKey ?: "")
            .put("page", page)
            .put("song", position?.song ?: 0)
            .put("songs", position?.songs ?: 0)
            .put("pages", position?.pages ?: 0)
            .put("songSkip", position?.skip == true)
            .put("notes", NotesJson.toJson(notes))
            .put("notesRev", notesRev)
            .put("savedRev", savedRev)
        val (code, json) = request("POST", "/v1/display", body.toString().toByteArray(), acceptErrors = true)
        return when {
            code == 200 -> DisplayResult.Ok
            json.optString("error") == Protocol.ERR_STALE -> DisplayResult.Stale(json.optLong("version"))
            json.optString("error") == Protocol.ERR_UNKNOWN_FILE -> DisplayResult.UnknownFile
            json.optString("error") == Protocol.ERR_NOTES_UNSAVED -> DisplayResult.NotesUnsaved
            else -> throw IOException("display failed: HTTP $code ${json.optString("message")}")
        }
    }

    fun upload(id: String, file: File, onProgress: (Float) -> Unit) = pinChecked {
        val conn = open("PUT", "/v1/files/$id", readTimeoutMs = 120_000)
        try {
            val total = file.length()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/pdf")
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        if (sent - lastReport > 256 * 1024 || sent == total) {
                            onProgress(sent.toFloat() / total)
                            lastReport = sent
                        }
                    }
                }
            }
            val code = conn.responseCode
            if (code == 401) throw NotPairedException()
            if (code != 200) throw IOException("upload failed: HTTP $code ${readJson(conn).optString("message")}")
        } finally {
            conn.disconnect()
        }
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        acceptErrors: Boolean = false,
        readTimeoutMs: Int = 5_000,
        connectTimeoutMs: Int = 3_000,
    ): Pair<Int, JSONObject> = pinChecked {
        val conn = open(method, path, readTimeoutMs, connectTimeoutMs)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 401) throw NotPairedException()
            val json = readJson(conn)
            if (code != 200 && !acceptErrors) throw IOException("$method $path: HTTP $code")
            code to json
        } catch (e: org.json.JSONException) {
            throw IOException("bad response from $ep", e)
        } finally {
            conn.disconnect()
        }
    }

    /** A handshake refused because the pinned certificate changed becomes a [KeyChangedException]. */
    private inline fun <T> pinChecked(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw if (e !is KeyChangedException && Tls.isPinMismatch(e)) KeyChangedException(e) else e
    }

    private fun open(method: String, path: String, readTimeoutMs: Int = 5_000, connectTimeoutMs: Int = 3_000): HttpsURLConnection {
        val host = if (ep.host.contains(':')) "[${ep.host}]" else ep.host
        return (URL("https", host, ep.port, path).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = tls.socketFactory
            hostnameVerifier = tls.hostnameVerifier
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    companion object {
        private const val PROBE_CONNECT_MS = 700
        private const val PROBE_READ_MS = 1_500

        fun parseState(json: JSONObject) = State(
            fileId = json.optString("fileId").ifEmpty { null },
            page = json.optInt("page"),
            version = json.optLong("version"),
            tools = ToolSync.fromJson(json),
        )
    }
}
