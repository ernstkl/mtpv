package io.github.ernstkl.multitabletpdfviewer.secondary

import io.github.ernstkl.multitabletpdfviewer.core.DirectCode
import io.github.ernstkl.multitabletpdfviewer.core.HashMismatchException
import io.github.ernstkl.multitabletpdfviewer.core.NotesJson
import io.github.ernstkl.multitabletpdfviewer.core.PairCode
import io.github.ernstkl.multitabletpdfviewer.core.PagePosition
import io.github.ernstkl.multitabletpdfviewer.core.PdfCache
import io.github.ernstkl.multitabletpdfviewer.core.ToolSync
import io.github.ernstkl.multitabletpdfviewer.net.Protocol
import io.github.ernstkl.multitabletpdfviewer.net.Tls
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The secondary's HTTPS API, see [Protocol]. Each connection has its own NanoHTTPD thread, which
 * serves its requests one after another; the client's certificate decides what they may do.
 */
class SecondaryServer(port: Int, private val rt: SecondaryRuntime, identity: Tls.Identity) : NanoHTTPD(port) {

    /** The fingerprint of the client certificate on this thread's connection, "" without one. */
    private val peer = ThreadLocal<String>()

    init {
        setServerSocketFactory(Tls.serverSocketFactory(identity))
    }

    /**
     * Closing a TLS socket sends a close_notify, which Android forbids on the main thread, where
     * pause() stops the server; NanoHTTPD would log that and leave the connections open, still
     * served in the background. So the closing runs on a thread of its own, waited for briefly.
     */
    override fun stop() {
        thread(name = "mtpv-server-stop") { super.stop() }.join(STOP_WAIT_MS)
    }

    override fun createClientHandler(socket: Socket, input: InputStream): ClientHandler {
        // NanoHTTPD writes headers and body separately; without TCP_NODELAY, Nagle holds the body back
        // until the controller's delayed ACK, about 40 ms on every request.
        socket.tcpNoDelay = true
        return object : ClientHandler(input, socket) {
            override fun run() {
                peer.set(Tls.peerFingerprint(socket).orEmpty())
                super.run()
            }
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: BodyTooLargeException) {
        error(Response.Status.PAYLOAD_TOO_LARGE, Protocol.ERR_TOO_LARGE, e.message)
    } catch (e: HashMismatchException) {
        error(Response.Status.CONFLICT, Protocol.ERR_HASH, e.message)
    } catch (e: Exception) {
        error(Response.Status.INTERNAL_ERROR, "internal", e.toString())
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri
        val fingerprint = peer.get().orEmpty()
        // Open to any client: who am I, and asking to be paired.
        when {
            s.method == Method.GET && path == "/v1/info" -> return ok(rt.info())
            s.method == Method.POST && path == "/v1/pair" -> {
                val body = JSONObject(String(readBody(s), Charsets.UTF_8))
                val commit = body.optString("commit")
                if (fingerprint.isEmpty() || !PairCode.isHex(commit, 32)) return error(Response.Status.BAD_REQUEST, Protocol.ERR_BAD_NONCE, "commit")
                val (id, nonce) = rt.pairing.request(body.getString("controller"), body.optString("name").ifEmpty { "Controller" }, fingerprint, commit)
                    ?: return error(Response.Status.CONFLICT, Protocol.ERR_ALREADY_PAIRED, "paired with another controller")
                return ok(JSONObject().put("requestId", id).put("nonce", nonce))
            }
            s.method == Method.POST && path.startsWith("/v1/pair/") && path.endsWith("/nonce") -> {
                val body = JSONObject(String(readBody(s), Charsets.UTF_8))
                val id = path.removePrefix("/v1/pair/").removeSuffix("/nonce")
                return rt.pairing.reveal(id, fingerprint, body.optString("nonce"))?.let { ok(it) }
                    ?: error(Response.Status.BAD_REQUEST, Protocol.ERR_BAD_NONCE, "nonce does not match the commit")
            }
            s.method == Method.GET && path.startsWith("/v1/pair/") -> return ok(rt.pairing.status(path.removePrefix("/v1/pair/"), fingerprint))
        }
        // Everything else only for paired controllers.
        val controller = rt.pairing.controllerFor(fingerprint)
            ?: return error(Response.Status.UNAUTHORIZED, Protocol.ERR_NOT_PAIRED, "pair this controller first")
        rt.onContact()
        return when {
            s.method == Method.GET && path == "/v1/files" -> ok(JSONObject().put("files", JSONArray(rt.cache.ids())))
            s.method == Method.GET && path == "/v1/state" -> ok(rt.stateJson())
            s.method == Method.GET && path == "/v1/notes" -> ok(rt.notesJson())
            s.method == Method.GET && path == "/v1/input" -> {
                val after = s.parameters["after"]?.firstOrNull()?.toLongOrNull() ?: -1
                ok(rt.awaitInput(after, Protocol.INPUT_WAIT_MS))
            }
            s.method == Method.POST && path == "/v1/tool" -> {
                val body = JSONObject(String(readBody(s), Charsets.UTF_8))
                val tools = ToolSync.fromJson(body)
                    ?: return error(Response.Status.BAD_REQUEST, Protocol.ERR_BAD_TOOL, body.optString("tool"))
                rt.toolsFromController(tools)
                ok(ToolSync.toJson(tools))
            }
            s.method == Method.POST && path == "/v1/direct" -> {
                val code = JSONObject(String(readBody(s), Charsets.UTF_8)).optString("code")
                if (code.isNotEmpty() && DirectCode.normalize(code) != code) {
                    return error(Response.Status.BAD_REQUEST, Protocol.ERR_BAD_CODE, null)
                }
                rt.directFromController(code.ifEmpty { null })
                ok(JSONObject().put("code", code))
            }
            s.method == Method.PUT && path.startsWith("/v1/files/") -> putFile(s, path.removePrefix("/v1/files/"))
            s.method == Method.POST && path == "/v1/display" -> {
                val body = JSONObject(String(readBody(s), Charsets.UTF_8))
                if (body.optString("controller") != controller) {
                    return error(Response.Status.UNAUTHORIZED, Protocol.ERR_NOT_PAIRED, "controller does not match its certificate")
                }
                rt.applyDisplay(
                    controller = controller,
                    version = body.getLong("version"),
                    fileId = body.optString("fileId").ifEmpty { null },
                    notesKey = body.optString("notesKey").ifEmpty { null },
                    page = body.optInt("page"),
                    position = body.optInt("song").takeIf { it > 0 }
                        ?.let { PagePosition(it, body.optInt("songs"), body.optInt("page"), body.optInt("pages")) },
                    notes = NotesJson.fromJson(body.optJSONArray("notes")),
                    notesRev = body.optLong("notesRev"),
                    savedRev = body.optLong("savedRev", -1),
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
        val declared = contentLength(s) ?: 0
        // Checked before allocating: /v1/pair is open to any client on the network.
        if (declared !in 0..Protocol.MAX_JSON_BODY) throw BodyTooLargeException("Content-Length $declared")
        val length = declared.toInt()
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

    private class BodyTooLargeException(message: String) : Exception(message)

    companion object {
        private const val STOP_WAIT_MS = 1_000L

        fun ok(json: JSONObject): Response = json(Response.Status.OK, json)

        fun json(status: Response.Status, json: JSONObject): Response =
            newFixedLengthResponse(status, "application/json", json.toString())

        fun error(status: Response.Status, code: String, message: String?, extra: JSONObject = JSONObject()): Response =
            json(status, extra.put("error", code).put("message", message ?: code))
    }
}
