package io.github.ernstkl.mtpv.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** [Tls] over loopback: Keystore keys on both ends, pins, the peer in serve(), connection reuse. */
@RunWith(AndroidJUnit4::class)
class TlsTest {
    private lateinit var serverId: Tls.Identity
    private lateinit var clientId: Tls.Identity
    private lateinit var server: TestServer

    @Before
    fun setUp() {
        for (alias in listOf(SERVER, CLIENT, OTHER)) Tls.Identity.delete(alias)
        serverId = Tls.Identity.load(SERVER)
        clientId = Tls.Identity.load(CLIENT)
        server = TestServer(PORT, serverId).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
    }

    @After
    fun tearDown() {
        server.stop()
        for (alias in listOf(SERVER, CLIENT, OTHER)) Tls.Identity.delete(alias)
    }

    @Test
    fun identityIsKept() {
        val again = Tls.Identity.load(SERVER)
        assertEquals(serverId.fingerprint, again.fingerprint)
        assertNotEquals(serverId.fingerprint, clientId.fingerprint)
        assertEquals(64, serverId.fingerprint.length)
    }

    @Test
    fun mutualHandshakeWithPin() {
        val body = whoami(Tls.Client(clientId, Tls.PinnedTrust(serverId.fingerprint)))
        assertEquals(clientId.fingerprint, body.getString("peer"))
        assertEquals("TLSv1.3", body.getString("protocol"))
    }

    @Test
    fun wrongPinIsRefused() {
        try {
            whoami(Tls.Client(clientId, Tls.PinnedTrust("00".repeat(32))))
            fail("connected despite a wrong pin")
        } catch (e: IOException) {
            assertTrue(e.toString(), Tls.isPinMismatch(e))
        }
    }

    @Test
    fun anyAcceptsUnknownServer() {
        assertEquals(clientId.fingerprint, whoami(Tls.Client(clientId, Tls.AnyTrust)).getString("peer"))
    }

    @Test
    fun pairingRecordsTheFirstPeerAndKeepsIt() {
        val client = Tls.Client(clientId, Tls.RecordingTrust())
        whoami(client)
        assertEquals(serverId.fingerprint, client.peer)
        whoami(client)

        server.stop()
        server = TestServer(PORT, Tls.Identity.load(OTHER)).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        try {
            whoami(client, close = true)
            fail("pairing went on with another device")
        } catch (e: IOException) {
            assertTrue(e.toString(), Tls.isPinMismatch(e))
        }
        assertEquals(serverId.fingerprint, client.peer)
    }

    @Test
    fun clientWithoutCertificateIsRefused() {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(Tls.AnyTrust), null) }
        try {
            open(ctx.socketFactory, Tls.AnyTrust, "/whoami").inputStream.use { it.readBytes() }
            fail("server accepted a client without certificate")
        } catch (e: IOException) {
            // The handshake fails, on the client as an SSLException or a reset connection.
        }
    }

    /** One Client: its factory and verifier stay the same objects, so the pool keeps the connection. */
    @Test
    fun keepAliveReusesTheConnection() {
        val client = Tls.Client(clientId, Tls.PinnedTrust(serverId.fingerprint))
        repeat(5) { whoami(client) }
        assertEquals(1, server.connections.get())
    }

    private fun whoami(client: Tls.Client, close: Boolean = false): JSONObject {
        val conn = open(client.socketFactory, client.hostnameVerifier, "/whoami")
        if (close) conn.setRequestProperty("Connection", "close")
        try {
            return JSONObject(conn.inputStream.use { it.reader().readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun open(factory: javax.net.ssl.SSLSocketFactory, verifier: javax.net.ssl.HostnameVerifier, path: String) =
        (URL("https", "127.0.0.1", PORT, path).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = factory
            hostnameVerifier = verifier
            connectTimeout = 3_000
            readTimeout = 10_000
        }

    /** As the secondary's server does it: TLS sockets from [Tls], the peer's fingerprint per connection. */
    private class TestServer(port: Int, identity: Tls.Identity) : NanoHTTPD("127.0.0.1", port) {
        private val peer = ThreadLocal<String?>()
        private val protocol = ThreadLocal<String?>()
        val connections = AtomicInteger()

        init {
            setServerSocketFactory(Tls.serverSocketFactory(identity))
        }

        override fun createClientHandler(socket: Socket, input: InputStream): ClientHandler {
            connections.incrementAndGet()
            return object : ClientHandler(input, socket) {
                override fun run() {
                    peer.set(Tls.peerFingerprint(socket))
                    protocol.set(runCatching { (socket as javax.net.ssl.SSLSocket).session.protocol }.getOrNull())
                    super.run()
                }
            }
        }

        override fun serve(session: IHTTPSession): Response =
            newFixedLengthResponse(
                Response.Status.OK, "application/json",
                JSONObject().put("peer", peer.get() ?: "").put("protocol", protocol.get() ?: "").toString(),
            )
    }

    private companion object {
        const val SERVER = "mtpv-test-server"
        const val CLIENT = "mtpv-test-client"
        const val OTHER = "mtpv-test-other"
        const val PORT = 8767
    }
}
