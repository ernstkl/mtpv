package io.github.ernstkl.mtpv.controller

import io.github.ernstkl.mtpv.core.NotesJson
import io.github.ernstkl.mtpv.core.Stroke
import io.github.ernstkl.mtpv.net.Endpoint
import io.github.ernstkl.mtpv.net.Protocol
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Blocking calls to one secondary's HTTP API. Every failure is an IOException. */
class SecondaryClient(private val ep: Endpoint) {

    data class Info(val uuid: String, val name: String)
    data class State(val fileId: String?, val page: Int, val version: Long)
    data class Notes(val fileId: String?, val page: Int, val baseRev: Long, val rev: Long, val dirty: Boolean, val strokes: List<Stroke>)

    sealed interface DisplayResult {
        data object Ok : DisplayResult
        data class Stale(val currentVersion: Long) : DisplayResult
        data object UnknownFile : DisplayResult
        data object NotesUnsaved : DisplayResult
    }

    fun info(): Info = request("GET", "/v1/info").let { (_, json) ->
        Info(json.getString("uuid"), json.getString("name"))
    }

    fun files(): Set<String> = request("GET", "/v1/files").let { (_, json) ->
        json.getJSONArray("files").let { a -> List(a.length()) { a.getString(it) }.toSet() }
    }

    fun state(): State = request("GET", "/v1/state").let { (_, json) -> parseState(json) }

    fun notes(): Notes = request("GET", "/v1/notes").let { (_, json) ->
        Notes(
            fileId = json.optString("fileId").ifEmpty { null },
            page = json.optInt("page"),
            baseRev = json.optLong("baseRev"),
            rev = json.optLong("rev"),
            dirty = json.optBoolean("dirty"),
            strokes = NotesJson.fromJson(json.optJSONArray("strokes")),
        )
    }

    /** Waits for page turns entered on the secondary; returns the new sequence number and the actions. */
    fun input(after: Long): Pair<Long, List<String>> {
        val (_, json) = request("GET", "/v1/input?after=$after", readTimeoutMs = (Protocol.INPUT_WAIT_MS + 5_000).toInt())
        val events = json.getJSONArray("events").let { a -> List(a.length()) { a.getString(it) } }
        return json.getLong("seq") to events
    }

    fun display(
        controller: String,
        version: Long,
        fileId: String?,
        page: Int,
        notes: List<Stroke>,
        notesRev: Long,
        savedRev: Long,
    ): DisplayResult {
        val body = JSONObject()
            .put("controller", controller)
            .put("version", version)
            .put("fileId", fileId ?: "")
            .put("page", page)
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

    fun upload(id: String, file: File, onProgress: (Float) -> Unit) {
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
    ): Pair<Int, JSONObject> {
        val conn = open(method, path, readTimeoutMs)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val json = readJson(conn)
            if (code != 200 && !acceptErrors) throw IOException("$method $path: HTTP $code")
            return code to json
        } catch (e: org.json.JSONException) {
            throw IOException("bad response from $ep", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String, readTimeoutMs: Int = 5_000): HttpURLConnection {
        val host = if (ep.host.contains(':')) "[${ep.host}]" else ep.host
        return (URL("http", host, ep.port, path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
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
        fun parseState(json: JSONObject) = State(
            fileId = json.optString("fileId").ifEmpty { null },
            page = json.optInt("page"),
            version = json.optLong("version"),
        )
    }
}
