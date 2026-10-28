package io.github.ernstkl.multitabletpdfviewer.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fi.iki.elonen.NanoHTTPD
import io.github.ernstkl.multitabletpdfviewer.core.PairCode
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * TLS between controller and secondary, without a CA or hostnames: each device has one key pair in
 * the Android Keystore with a self-signed certificate ([Identity]), and pairing pins the peer's
 * certificate by its fingerprint (SHA-256 of the DER encoding, [PairCode.fingerprint]). Both sides
 * present their certificate; the secondary decides by the controller's fingerprint what it may do.
 *
 * Connection reuse: Android pools a connection only for the same SSLSocketFactory and
 * HostnameVerifier objects, and SSLContext.socketFactory makes a new factory on every call. So each
 * [Client] makes its factory once, and [pinned] keeps one Client per pin.
 */
object Tls {
    const val ALIAS = "mtpv-identity"
    private const val PROTOCOL = "TLSv1.3"

    @Volatile private var own: Identity? = null
    private val pinnedClients = ConcurrentHashMap<String, Client>()
    @Volatile private var anyClient: Client? = null

    /** This device's identity, made on first use (blocks some 100 ms then: not on the main thread). */
    fun identity(): Identity = own ?: synchronized(this) { own ?: Identity.load(ALIAS).also { own = it } }

    /** This device's identity if [identity] has loaded it already. */
    fun loaded(): Identity? = own

    /** For a paired secondary: only the certificate with fingerprint [pin]. */
    fun pinned(pin: String): Client = pinnedClients.getOrPut(pin) { Client(identity(), PinnedTrust(pin)) }

    /**
     * For GET /v1/info probes (discovery, address scan, adding by address): any certificate. The
     * answer is only a hint; a paired link checks the UUID again over a pinned connection.
     */
    fun any(): Client = anyClient ?: synchronized(this) { anyClient ?: Client(identity(), AnyTrust).also { anyClient = it } }

    /**
     * For one pairing: accepts the first certificate it sees, and from then on only that one, so all
     * calls of the pairing talk to the same device. Its fingerprint goes into the code both screens
     * show, which is what proves no one is in between.
     */
    fun pairing(): Client = Client(identity(), RecordingTrust())

    /** Server sockets that speak TLS 1.3 only and require a client certificate (any; see [peerFingerprint]). */
    fun serverSocketFactory(identity: Identity): NanoHTTPD.ServerSocketFactory {
        val ctx = context(identity, AnyTrust)
        return NanoHTTPD.ServerSocketFactory {
            (ctx.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                enabledProtocols = arrayOf(PROTOCOL)
                needClientAuth = true
            }
        }
    }

    /** The fingerprint of the client certificate on an accepted socket, after the handshake; null without one. */
    fun peerFingerprint(socket: Socket): String? = runCatching {
        (socket as SSLSocket).session.peerCertificates.firstOrNull()?.let { PairCode.fingerprint(it.encoded) }
    }.getOrNull()

    /** Whether [e] (or a cause) is a pinned peer presenting another certificate. */
    fun isPinMismatch(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any { it is PinMismatchException }

    private fun context(identity: Identity, trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(identity.keyManager), arrayOf(trust), null) }

    /** One way to connect to secondaries: this device's certificate and a rule which peers to accept. */
    class Client internal constructor(identity: Identity, private val trust: PeerTrust) {
        val socketFactory: SSLSocketFactory = context(identity, trust).socketFactory

        /** Checks the peer's certificate again after the handshake, by the same rule as the trust manager. */
        val hostnameVerifier: HostnameVerifier = trust

        /** For [pairing]: the fingerprint of the peer, once connected. */
        val peer: String? get() = (trust as? RecordingTrust)?.recorded
    }

    /** This device's key in the Android Keystore; the private key never leaves it. */
    class Identity private constructor(private val alias: String, private val key: PrivateKey, val certificate: X509Certificate) {
        val fingerprint: String = PairCode.fingerprint(certificate.encoded)

        /** Hands TLS the one key, as client and as server. */
        val keyManager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
            override fun getCertificateChain(alias: String?) = arrayOf(certificate)
            override fun getPrivateKey(alias: String?) = key
        }

        companion object {
            /**
             * Loads the key [alias], or makes it. It is not in backups and is gone after a
             * reinstall; then peers refuse the new one and the device has to be paired again.
             */
            fun load(alias: String): Identity {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val key = ks.getKey(alias, null) as? PrivateKey
                val cert = ks.getCertificate(alias) as? X509Certificate
                if (key != null && cert != null) return Identity(alias, key, cert)
                generate(alias)
                return Identity(alias, ks.getKey(alias, null) as PrivateKey, ks.getCertificate(alias) as X509Certificate)
            }

            /** For tests. */
            fun delete(alias: String) {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }

            private fun generate(alias: String) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                            .setCertificateSubject(X500Principal("CN=MTPV"))
                            .setCertificateSerialNumber(BigInteger.ONE)
                            // Pins replace validity: the certificate never expires, whatever the device's clock says.
                            .setCertificateNotBefore(Date(0))
                            .setCertificateNotAfter(Date(4102444800_000L)) // 2100-01-01
                            .build(),
                    )
                    generateKeyPair()
                }
            }
        }
    }

    /** A pinned peer presented another certificate: reinstalled, or someone else. */
    class PinMismatchException : CertificateException("certificate not pinned")

    /**
     * Which peer certificates to accept, judged by fingerprint only. There is no CA: a peer proves in
     * the handshake that it holds the key of the certificate it shows, and the fingerprint says
     * whose key that is. The same rule serves as hostname check, since addresses change and keys do not.
     */
    internal abstract class PeerTrust : X509TrustManager, HostnameVerifier {
        protected abstract fun check(fingerprint: String)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = checkChain(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = checkChain(chain)
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

        override fun verify(hostname: String?, session: SSLSession?): Boolean = runCatching {
            checkChain(session?.peerCertificates?.map { it as X509Certificate }?.toTypedArray())
        }.isSuccess

        private fun checkChain(chain: Array<out X509Certificate>?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            check(PairCode.fingerprint(cert.encoded))
        }
    }

    internal class PinnedTrust(private val pin: String) : PeerTrust() {
        override fun check(fingerprint: String) {
            if (fingerprint != pin) throw PinMismatchException()
        }
    }

    /**
     * Any certificate: for probes, where the answer is only a hint, and on the secondary's server,
     * which decides by the client's fingerprint what it may do (unknown ones only /v1/info and pairing).
     */
    internal object AnyTrust : PeerTrust() {
        override fun check(fingerprint: String) = Unit
    }

    internal class RecordingTrust : PeerTrust() {
        @Volatile var recorded: String? = null
            private set

        @Synchronized
        override fun check(fingerprint: String) {
            val r = recorded ?: fingerprint.also { recorded = it }
            if (r != fingerprint) throw PinMismatchException()
        }
    }
}
