package io.github.ernstkl.mtpv.secondary

import io.github.ernstkl.mtpv.core.HashMismatchException
import io.github.ernstkl.mtpv.core.PdfCache
import io.github.ernstkl.mtpv.net.Protocol
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** The secondary's HTTP API, see [Protocol]. Each request runs on its own NanoHTTPD thread. */
class SecondaryServer(port: Int, private val rt: SecondaryRuntime) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        rt.onContact()
        route(session)
    } catch (e: HashMismatchException) {
        error(Response.Status.CONFLICT, Protocol.ERR_HASH, e.message)
    } catch (e: Exception) {
        error(Response.Status.INTERNAL_ERROR, "internal", e.toString())
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri
        return when {
            s.method == Method.GET && path == "/v1/info" -> ok(rt.info())
            s.method == Method.GET && path == "/v1/files" -> ok(JSONObject().put("files", JSONArray(rt.cache.ids())))
            s.method == Method.GET && path == "/v1/state" -> ok(rt.stateJson())
            s.method == Method.PUT && path.startsWith("/v1/files/") -> putFile(s, path.removePrefix("/v1/files/"))
            s.method == Method.POST && path == "/v1/display" -> {
                val body = JSONObject(String(readBody(s), Charsets.UTF_8))
                rt.applyDisplay(
                    controller = body.getString("controller"),
                    version = body.getLong("version"),
                    fileId = body.optString("fileId").ifEmpty { null },
                    page = body.optInt("page"),
                )
            }
            else -> error(Response.Status.NOT_FOUND, "not_found", path)
        }
    }

    private fun putFile(s: IHTTPSession, id: String): Response {
        if (!PdfCache.isValidId(id)) return error(Response.Status.BAD_REQUEST, "bad_id", id)
        val length = contentLength(s) ?: return error(Response.Status.BAD_REQUEST, "length_required", null)
        if (rt.cache.has(id)) {
            drain(s.inputStream, length)
        } else {
            rt.cache.store(s.inputStream, expectedId = id, length = length)
            rt.afterStore(id)
        }
        return ok(JSONObject().put("id", id))
    }

    private fun contentLength(s: IHTTPSession): Long? = s.headers["content-length"]?.toLongOrNull()

    private fun readBody(s: IHTTPSession): ByteArray {
        val length = contentLength(s)?.toInt() ?: 0
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = s.inputStream.read(buf, off, length - off)
            if (n < 0) break
            off += n
        }
        return buf
    }

    private fun drain(input: InputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    companion object {
        fun ok(json: JSONObject): Response = json(Response.Status.OK, json)

        fun json(status: Response.Status, json: JSONObject): Response =
            newFixedLengthResponse(status, "application/json", json.toString())

        fun error(status: Response.Status, code: String, message: String?, extra: JSONObject = JSONObject()): Response =
            json(status, extra.put("error", code).put("message", message ?: code))
    }
}
