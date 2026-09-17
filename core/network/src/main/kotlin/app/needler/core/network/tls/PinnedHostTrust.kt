package app.needler.core.network.tls

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Platform trust, plus a per-host exception for leaves the user explicitly pinned.
 *
 * Order of business on every handshake:
 *  1. ask the platform trust manager — a real certificate from a real CA passes here and the
 *     pin store is never consulted;
 *  2. only if the platform rejects it, and only if we can name the host being dialled, accept
 *     the chain when its **leaf** matches a pin the user confirmed for that host.
 *
 * That is the shape REQUIREMENTS.md §"Self-signed certificates" asks for: one host, one leaf,
 * and a changed fingerprint fails loudly. If the host cannot be determined (the bare two-argument
 * `checkServerTrusted` overload, which carries no peer information) the platform failure is
 * re-thrown — this class never grants a global exception.
 */
public class PinnedHostTrustManager(
    private val delegate: X509ExtendedTrustManager,
    private val pins: CertificatePinStore,
) : X509ExtendedTrustManager() {

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
        delegate.checkClientTrusted(chain, authType, socket)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        delegate.checkClientTrusted(chain, authType, engine)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // No peer information on this overload, so no host to scope a pin to: platform only.
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) {
        try {
            delegate.checkServerTrusted(chain, authType, socket)
        } catch (rejected: CertificateException) {
            acceptPinnedOrRethrow(chain, hostOf(socket), rejected)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        try {
            delegate.checkServerTrusted(chain, authType, engine)
        } catch (rejected: CertificateException) {
            acceptPinnedOrRethrow(chain, engine?.peerHost, rejected)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun acceptPinnedOrRethrow(
        chain: Array<out X509Certificate>,
        host: String?,
        rejected: CertificateException,
    ) {
        val leaf = chain.firstOrNull() ?: throw rejected
        if (host.isNullOrBlank()) throw rejected
        if (!pins.matches(host, leaf)) throw rejected
        // Pinned by the user for exactly this host: accept, but still refuse an expired leaf so
        // a stale pin cannot keep a dead certificate alive forever.
        leaf.checkValidity()
    }

    private fun hostOf(socket: Socket?): String? {
        val ssl = socket as? SSLSocket ?: return socket?.inetAddress?.hostName
        val fromHandshake = runCatching { ssl.handshakeSession?.peerHost }.getOrNull()
        if (!fromHandshake.isNullOrBlank()) return fromHandshake
        val fromSession = runCatching { ssl.session?.peerHost }.getOrNull()
        if (!fromSession.isNullOrBlank()) return fromSession
        return ssl.inetAddress?.hostName
    }

    public companion object {

        /** The platform's own trust manager, wrapped so pinned hosts get their exception. */
        public fun platformDelegate(): X509ExtendedTrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers
                .filterIsInstance<X509ExtendedTrustManager>()
                .firstOrNull()
                ?: error("No X509ExtendedTrustManager available from the platform")
        }

        /**
         * The `(socketFactory, trustManager)` pair OkHttp needs.
         *
         * Returns `null` when the platform does not expose an extended trust manager, in which
         * case the caller must leave OkHttp on its defaults — i.e. pinning is unavailable rather
         * than validation being weakened.
         */
        public fun socketFactory(pins: CertificatePinStore): Pair<SSLSocketFactory, X509TrustManager>? {
            val platform = runCatching { platformDelegate() }.getOrNull() ?: return null
            val manager = PinnedHostTrustManager(platform, pins)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<javax.net.ssl.TrustManager>(manager), null)
            return context.socketFactory to manager
        }
    }
}

/**
 * Hostname verification that also accepts a user-pinned leaf.
 *
 * Self-signed certificates routinely have no SAN for the LAN address they are served on, so
 * hostname verification fails even after the trust manager has accepted the pin. This verifier
 * defers to the platform first and only then checks the pin store for that exact host.
 */
public class PinnedHostnameVerifier(
    private val pins: CertificatePinStore,
    private val delegate: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (delegate.verify(hostname, session)) return true
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (unverified: SSLPeerUnverifiedException) {
            null
        } ?: return false
        return pins.matches(hostname, leaf)
    }
}
