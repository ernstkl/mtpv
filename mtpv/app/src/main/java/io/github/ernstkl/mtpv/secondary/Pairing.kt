package io.github.ernstkl.mtpv.secondary

import io.github.ernstkl.mtpv.PairedController
import io.github.ernstkl.mtpv.Prefs
import io.github.ernstkl.mtpv.core.Presence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** A controller asking to be paired, waiting for Allow or Deny on this screen. */
data class PairRequest(val id: String, val controller: String, val name: String, val code: String, val createdAt: Long)

/**
 * Which controllers this secondary obeys. A controller asks (POST /v1/pair); the person at this
 * screen sees its name and a code that the controller shows too, and taps Allow. Only then does
 * the controller get a token, which it must send with every other request. Being able to tap this
 * screen is the proof, so a crowded network does not matter. Paired with a controller, it refuses
 * others until that one is removed here ([Presence]). [onChange] follows each change of the paired list.
 */
class Pairing(private val prefs: Prefs, private val onChange: () -> Unit) {
    private val random = SecureRandom()

    private val _paired = MutableStateFlow(prefs.loadPaired())
    val paired: StateFlow<List<PairedController>> = _paired

    private val _requests = MutableStateFlow<List<PairRequest>>(emptyList())
    val requests: StateFlow<List<PairRequest>> = _requests

    /** Answers given on this screen, until the controller has picked them up. */
    private val answers = HashMap<String, PairedController?>()

    /** Null if paired with another controller. */
    @Synchronized
    fun request(controller: String, name: String): PairRequest? {
        if (!Presence.mayPair(_paired.value.map { it.uuid }, controller)) return null
        expire()
        // A repeated request from the same controller replaces its earlier one; few strangers fit on screen.
        val others = _requests.value.filter { it.controller != controller }.takeLast(MAX_PENDING - 1)
        val req = PairRequest(hex(8), controller, name.take(60), "%04d".format(random.nextInt(10_000)), now())
        _requests.value = others + req
        return req
    }

    @Synchronized
    fun answer(id: String, allow: Boolean) {
        val req = _requests.value.firstOrNull { it.id == id } ?: return
        _requests.value = _requests.value - req
        answers[id] = if (allow) {
            val p = PairedController(req.controller, req.name, hex(32))
            _paired.value = _paired.value.filter { it.uuid != req.controller } + p
            prefs.savePaired(_paired.value)
            onChange()
            p
        } else {
            null
        }
    }

    /** For GET /v1/pair/{id}: pending, allowed (once, with the token), denied or unknown. */
    @Synchronized
    fun status(id: String): JSONObject {
        expire()
        if (_requests.value.any { it.id == id }) return JSONObject().put("status", "pending")
        if (!answers.containsKey(id)) return JSONObject().put("status", "unknown")
        val p = answers.remove(id)
        return if (p == null) JSONObject().put("status", "denied") else JSONObject().put("status", "allowed").put("token", p.token)
    }

    /** The controller a token belongs to, or null. */
    fun controllerFor(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val given = token.toByteArray()
        return _paired.value.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), given) }?.uuid
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
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val REQUEST_TTL_MS = 60_000L
        private const val MAX_PENDING = 3
    }
}
