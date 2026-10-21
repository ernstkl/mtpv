package io.github.ernstkl.mtpv.controller

import io.github.ernstkl.mtpv.core.PdfCache
import io.github.ernstkl.mtpv.net.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** What one secondary should show. page 0 is blank. */
data class Target(val fileId: String?, val page: Int)

data class LinkStatus(
    val online: Boolean = false,
    val shownPage: Int? = null,
    val upload: Float? = null,
    val error: String? = null,
)

/**
 * Keeps one secondary in line with its [Target]: verifies its identity, uploads the PDF if it is
 * missing, sends the page, and keeps checking it every few seconds. Only the latest target matters,
 * so missed page turns are never replayed. A second loop forwards page turns entered on the
 * secondary to [onInput].
 */
class SecondaryLink(
    private val uuid: String,
    private val controllerId: String,
    private val cache: PdfCache,
    private val versions: VersionClock,
    private val report: (LinkStatus) -> Unit,
    private val onInput: (String) -> Unit,
) {
    @Volatile private var target: Target? = null
    @Volatile private var endpoint: Endpoint? = null

    /** The endpoint whose identity has been checked; null while offline. */
    @Volatile private var verified: Endpoint? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun setTarget(t: Target?) {
        if (t == target) return
        target = t
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
        var files = emptySet<String>()
        var backoff = 1_000L

        while (kotlin.coroutines.coroutineContext.isActive) {
            val ep = endpoint
            if (ep == null) {
                publish(LinkStatus(error = "not found yet"))
                wake.receive()
                continue
            }
            try {
                val client = SecondaryClient(ep)
                if (verified != ep) {
                    val info = client.info()
                    if (info.uuid != uuid) throw IOException("a different device answers at $ep")
                    files = client.files()
                    applied = null
                    verified = ep
                    publish(LinkStatus(online = true))
                }
                val want = target
                if (want != null && want != applied) {
                    val fileId = want.fileId
                    if (fileId != null && fileId !in files) {
                        if (!cache.has(fileId)) throw IOException("PDF missing on this device")
                        publish(status.copy(upload = 0f))
                        client.upload(fileId, cache.file(fileId)) { publish(status.copy(upload = it)) }
                        files = files + fileId
                    }
                    when (val r = client.display(controllerId, versions.next(), fileId, want.page)) {
                        SecondaryClient.DisplayResult.Ok -> applied = want
                        is SecondaryClient.DisplayResult.Stale -> versions.bumpTo(r.currentVersion)
                        SecondaryClient.DisplayResult.UnknownFile -> files = files - fileId.orEmpty()
                    }
                    publish(LinkStatus(online = true, shownPage = applied?.page))
                    continue
                }
                backoff = 1_000L
                val woken = withTimeoutOrNull(PING_MS) { wake.receive() }
                if (woken == null) {
                    // Ping, and resend if the secondary shows something else (restarted, other controller).
                    val st = client.state()
                    if (applied != null && (st.fileId != applied.fileId || st.page != applied.page)) applied = null
                }
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
                val (seq, events) = SecondaryClient(ep).input(after)
                after = seq
                events.forEach(onInput)
            } catch (e: IOException) {
                delay(1_000)
            }
        }
    }

    companion object {
        private const val PING_MS = 3_000L
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
