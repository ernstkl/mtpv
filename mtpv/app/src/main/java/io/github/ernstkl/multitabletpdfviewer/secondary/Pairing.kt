package io.github.ernstkl.multitabletpdfviewer.secondary

import io.github.ernstkl.multitabletpdfviewer.PairedController
import io.github.ernstkl.multitabletpdfviewer.Prefs
import io.github.ernstkl.multitabletpdfviewer.core.PairCode
import io.github.ernstkl.multitabletpdfviewer.core.Presence
import io.github.ernstkl.multitabletpdfviewer.net.Tls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** A controller asking to be paired, waiting for Allow or Deny on this screen. [fingerprint] is its certificate's. */
data class PairRequest(val id: String, val controller: String, val name: String, val fingerprint: String, val code: String, val createdAt: Long)

/**
 * Which controllers this secondary obeys, by the fingerprint of their certificate. A controller asks
 * (POST /v1/pair) with a commitment to its nonce and gets this side's nonce; once it reveals its
 * nonce, the person at this screen sees its name and a code derived from both certificates and both
 * nonces ([PairCode]), which the controller shows too, and taps Allow if they match. Then the
 * controller's fingerprint is stored. Matching codes prove no one is in between, and being able to
 * tap this screen proves the person is here, so a crowded network does not matter. Paired with a
 * controller, it refuses others until that one is removed here ([Presence]). [onChange] follows
 * each change of the paired list.
 */
class Pairing(private val prefs: Prefs, private val onChange: () -> Unit) {
    private val random = SecureRandom()

    private val _paired = MutableStateFlow(prefs.loadPaired())
    val paired: StateFlow<List<PairedController>> = _paired

    /** Revealed requests, shown on screen. */
    private val _requests = MutableStateFlow<List<PairRequest>>(emptyList())
    val requests: StateFlow<List<PairRequest>> = _requests

    /** Requests whose nonce has not been revealed yet; not shown. */
    private class Pending(val id: String, val controller: String, val name: String, val fingerprint: String, val commit: String, val nonce: String, val createdAt: Long)
    private val pending = LinkedHashMap<String, Pending>()

    /** Answers given on this screen, until the controller has picked them up: request ID to its fingerprint and whether allowed. */
    private val answers = HashMap<String, Pair<String, Boolean>>()

    /**
     * From the client with certificate [fingerprint], with [commit] checked to be well-formed. Returns
     * the request ID and this side's nonce; null if paired with another controller.
     */
    @Synchronized
    fun request(controller: String, name: String, fingerprint: String, commit: String): Pair<String, String>? {
        if (!Presence.mayPair(_paired.value.map { it.uuid }, controller)) return null
        expire()
        // A repeated request from the same controller replaces its earlier one; few strangers fit on screen.
        pending.values.removeAll { it.controller == controller }
        while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        val p = Pending(hex(8), controller, name.take(60), fingerprint, commit, PairCode.newNonce(random), now())
        pending[p.id] = p
        return p.id to p.nonce
    }

    /**
     * The controller reveals its [nonce]. If it matches the commitment, the request goes on screen
     * with its code; otherwise it is dropped. Returns the status as for [status], or null on a bad nonce.
     */
    @Synchronized
    fun reveal(id: String, fingerprint: String, nonce: String): JSONObject? {
        expire()
        val p = pending[id]?.takeIf { it.fingerprint == fingerprint } ?: return status(id, fingerprint)
        pending.remove(id)
        if (!PairCode.verify(p.commit, nonce)) return null
        if (!Presence.mayPair(_paired.value.map { it.uuid }, p.controller)) return JSONObject().put("status", "denied")
        val code = PairCode.derive(Tls.identity().fingerprint, p.fingerprint, nonce, p.nonce)
        val others = _requests.value.filter { it.controller != p.controller }.takeLast(MAX_PENDING - 1)
        _requests.value = others + PairRequest(p.id, p.controller, p.name, p.fingerprint, code, p.createdAt)
        return JSONObject().put("status", "pending")
    }

    @Synchronized
    fun answer(id: String, allow: Boolean) {
        val req = _requests.value.firstOrNull { it.id == id } ?: return
        _requests.value = _requests.value - req
        answers[id] = req.fingerprint to allow
        if (allow) {
            val p = PairedController(req.controller, req.name, req.fingerprint)
            _paired.value = _paired.value.filter { it.uuid != req.controller } + p
            prefs.savePaired(_paired.value)
            onChange()
        }
    }

    /** For GET /v1/pair/{id}: pending, allowed or denied (once), or unknown. Only the asking client learns more than unknown. */
    @Synchronized
    fun status(id: String, fingerprint: String): JSONObject {
        expire()
        val asking = pending[id]?.fingerprint ?: _requests.value.firstOrNull { it.id == id }?.fingerprint
        if (asking == fingerprint) return JSONObject().put("status", "pending")
        val a = answers[id]?.takeIf { it.first == fingerprint } ?: return JSONObject().put("status", "unknown")
        answers.remove(id)
        return JSONObject().put("status", if (a.second) "allowed" else "denied")
    }

    /** The paired controller with certificate [fingerprint], or null. */
    fun controllerFor(fingerprint: String?): String? {
        if (fingerprint.isNullOrEmpty()) return null
        val given = fingerprint.toByteArray()
        return _paired.value.firstOrNull { MessageDigest.isEqual(it.fingerprint.toByteArray(), given) }?.uuid
    }

    @Synchronized
    fun remove(uuid: String) {
        _paired.value = _paired.value.filter { it.uuid != uuid }
        prefs.savePaired(_paired.value)
        onChange()
    }

    private fun expire() {
        val cutoff = now() - REQUEST_TTL_MS
        _requests.value = _requests.value.filter { it.createdAt > cutoff }
        pending.values.removeAll { it.createdAt <= cutoff }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val REQUEST_TTL_MS = 60_000L
        private const val MAX_PENDING = 3
    }
}
