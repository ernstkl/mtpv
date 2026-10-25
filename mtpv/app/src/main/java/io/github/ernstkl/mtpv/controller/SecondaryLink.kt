package io.github.ernstkl.mtpv.controller

import io.github.ernstkl.mtpv.core.NotesStore
import io.github.ernstkl.mtpv.core.PagePosition
import io.github.ernstkl.mtpv.core.PageKey
import io.github.ernstkl.mtpv.core.PdfCache
import io.github.ernstkl.mtpv.core.ToolState
import io.github.ernstkl.mtpv.net.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * What one secondary should show: a PDF's page and the key of its song's notes, and where that page
 * sits in the set. page 0 is blank, without a position.
 */
data class Target(val fileId: String?, val notesKey: String?, val page: Int, val position: PagePosition? = null)

data class LinkStatus(
    val online: Boolean = false,
    /** The secondary refuses this controller: pair it (again). */
    val notPaired: Boolean = false,
    val shownPage: Int? = null,
    val upload: Float? = null,
    val error: String? = null,
)

/** How links hand notes to the controller. Implemented by [ControllerRuntime]. */
interface NotesHub {
    /** Saves notes edited on secondary [uuid]. */
    fun saveFromSecondary(uuid: String, notes: SecondaryClient.Notes)

    /** Waits (briefly) until no other reachable secondary still holds unsaved notes of [key]. */
    suspend fun awaitFree(key: PageKey, uuid: String)

    fun load(key: PageKey): NotesStore.Page

    /** Secondary [uuid] now shows [key] (null: nothing); its notes there may change until fetched. */
    fun hold(uuid: String, key: PageKey?)
}

/**
 * Keeps one secondary in line with its [Target]: verifies its identity, uploads the PDF if it is
 * missing, sends the page, and keeps checking it every few seconds. Only the latest target matters,
 * so missed page turns are never replayed. Before a secondary gets a new page, the notes on its
 * current page are fetched and saved, so they are never lost. Once the page is right, note tool and
 * finger switch follow, and requests to come to the front. A second loop forwards page turns and tool picks entered on the secondary to [onInput].
 */
class SecondaryLink(
    private val uuid: String,
    private val controllerId: String,
    private val cache: PdfCache,
    private val versions: VersionClock,
    private val notes: NotesHub,
    private val report: (LinkStatus) -> Unit,
    private val onInput: (String) -> Unit,
) {
    @Volatile private var target: Target? = null
    @Volatile private var tools: ToolState? = null
    /** Time of a pending request to come to the front, or 0. */
    @Volatile private var frontAt = 0L
    @Volatile private var endpoint: Endpoint? = null
    @Volatile private var token: String? = null

    /** The endpoint whose identity has been checked; null while offline. */
    @Volatile private var verified: Endpoint? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Flushes asked for and done so far, see [flush]. */
    private val flushWanted = AtomicLong(0)
    private val flushDone = MutableStateFlow(0L)

    fun setTarget(t: Target?) {
        if (t == target) return
        target = t
        wake.trySend(Unit)
    }

    fun setTools(t: ToolState) {
        if (t == tools) return
        tools = t
        wake.trySend(Unit)
    }

    /** Asks the secondary to come to the front, once it shows its page; dropped if that takes too long. */
    fun bringToFront() {
        frontAt = System.currentTimeMillis()
        wake.trySend(Unit)
    }

    /**
     * Fetches and saves the notes on the secondary's page now, by sending that page again: the
     * secondary has no unsaved notes afterwards. Returns once done; waits while it is offline.
     */
    suspend fun flush() {
        val n = flushWanted.incrementAndGet()
        wake.trySend(Unit)
        flushDone.first { it >= n }
    }

    fun setToken(t: String?) {
        if (t == token) return
        token = t
        verified = null
        wake.trySend(Unit)
    }

    fun setEndpoint(ep: Endpoint?) {
        if (ep == endpoint) return
        endpoint = ep
        wake.trySend(Unit)
    }

    fun launchIn(scope: CoroutineScope) = scope.launch {
        launch { run() }
        launch { pollInput() }
    }

    private suspend fun run() {
        var status = LinkStatus()
        fun publish(s: LinkStatus) {
            if (s != status) {
                status = s
                report(s)
            }
        }

        var applied: Target? = null
        var appliedTools: ToolState? = null
        var files = emptySet<String>()
        var backoff = 1_000L

        while (kotlin.coroutines.coroutineContext.isActive) {
            val ep = endpoint
            val tok = token
            if (ep == null || tok == null) {
                publish(if (tok == null) LinkStatus(notPaired = true, error = "not paired") else LinkStatus(error = "not found yet"))
                wake.receive()
                continue
            }
            try {
                val client = SecondaryClient(ep, tok)
                if (verified != ep) {
                    val info = client.info()
                    if (info.uuid != uuid) throw IOException("a different device answers at $ep")
                    files = client.files()
                    applied = null
                    appliedTools = null
                    verified = ep
                    publish(LinkStatus(online = true))
                }
                val want = target
                val flushTo = flushWanted.get()
                if (flushTo > flushDone.value) {
                    if (want == null) flushDone.value = flushTo else applied = null
                }
                if (want != null && want != applied) {
                    val fileId = want.fileId
                    if (fileId != null && fileId !in files) {
                        if (!cache.has(fileId)) throw IOException("PDF missing on this device")
                        publish(status.copy(upload = 0f))
                        client.upload(fileId, cache.file(fileId)) { publish(status.copy(upload = it)) }
                        files = files + fileId
                    }
                    // Save what was written on the current page before it is replaced.
                    val back = client.notes()
                    if (back.dirty && back.notesKey != null && back.page > 0) notes.saveFromSecondary(uuid, back)
                    notes.hold(uuid, null)
                    val key = want.notesKey?.takeIf { fileId != null && want.page > 0 }?.let { PageKey(it, want.page) }
                    key?.let { notes.awaitFree(it, uuid) }
                    val page = key?.let { notes.load(it) }
                    val r = client.display(
                        controllerId, versions.next(), fileId, key?.notesKey, want.page, want.position,
                        page?.strokes.orEmpty(), page?.rev ?: 0, back.rev,
                    )
                    when (r) {
                        SecondaryClient.DisplayResult.Ok -> {
                            applied = want
                            notes.hold(uuid, key)
                            flushDone.value = maxOf(flushDone.value, flushTo)
                        }
                        is SecondaryClient.DisplayResult.Stale -> versions.bumpTo(r.currentVersion)
                        SecondaryClient.DisplayResult.UnknownFile -> files = files - fileId.orEmpty()
                        // Being written on, or written to meanwhile: fetch again shortly.
                        SecondaryClient.DisplayResult.NotesUnsaved -> delay(100)
                    }
                    publish(LinkStatus(online = true, shownPage = applied?.page))
                    continue
                }
                val t = tools
                if (t != null && t != appliedTools) {
                    client.tools(t)
                    appliedTools = t
                    continue
                }
                val at = frontAt
                if (at != 0L) {
                    frontAt = 0L
                    if (System.currentTimeMillis() - at < FRONT_MAX_AGE_MS) client.front()
                    continue
                }
                backoff = 1_000L
                val woken = withTimeoutOrNull(PING_MS) { wake.receive() }
                if (woken == null) {
                    // Ping, and resend if the secondary shows something else (restarted, other controller).
                    val st = client.state()
                    if (applied != null && (st.fileId != applied.fileId || st.page != applied.page)) applied = null
                    if (st.tools != appliedTools) appliedTools = null
                }
            } catch (e: NotPairedException) {
                // Only a new pairing (setToken) helps; wait for it.
                verified = null
                publish(LinkStatus(online = true, notPaired = true, error = "not paired"))
                wake.receive()
            } catch (e: IOException) {
                verified = null
                publish(LinkStatus(error = e.message ?: e.javaClass.simpleName))
                withTimeoutOrNull(backoff) { wake.receive() }
                backoff = (backoff * 2).coerceAtMost(5_000L)
            }
        }
    }

    private suspend fun pollInput() {
        var after = -1L
        var polled: Endpoint? = null
        while (kotlin.coroutines.coroutineContext.isActive) {
            val ep = verified
            if (ep == null) {
                delay(500)
                continue
            }
            if (ep != polled) {
                after = -1
                polled = ep
            }
            try {
                val (seq, events) = SecondaryClient(ep, token).input(after)
                after = seq
                events.forEach(onInput)
            } catch (e: IOException) {
                delay(1_000)
            }
        }
    }

    companion object {
        private const val PING_MS = 3_000L
        private const val FRONT_MAX_AGE_MS = 10_000L
    }
}

/** Display command versions: strictly increasing, also across app restarts. */
class VersionClock(private val load: () -> Long, private val save: (Long) -> Unit) {
    private var last = load()

    @Synchronized
    fun next(): Long {
        last = maxOf(last + 1, System.currentTimeMillis())
        save(last)
        return last
    }

    @Synchronized
    fun bumpTo(v: Long) {
        if (v > last) {
            last = v
            save(v)
        }
    }
}
