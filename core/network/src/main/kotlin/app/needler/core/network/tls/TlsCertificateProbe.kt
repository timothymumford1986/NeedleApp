package app.needler.core.network.tls

import app.needler.core.network.ServerUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Reads the certificate a host presents, so the Connect screen can show its fingerprint, subject
 * and expiry and ask the user whether to pin it (REQUIREMENTS.md §"Self-signed certificates").
 *
 * This is the one place that completes a handshake without validating the chain, and it is
 * deliberately inert: the socket is used to read the peer certificates and is then closed. No
 * request is ever written to it, nothing it returns is trusted, and the returned
 * [CertificateDetails] only becomes an exception once the **user** pins it into a
 * [MutableCertificatePinStore]. Application traffic always goes through [PinnedHostTrustManager],
 * which validates against the platform first.
 */
public class TlsCertificateProbe(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
) {

    /** Probe the host and port of [server]. Returns `null` for a cleartext (`http://`) server. */
    public suspend fun probe(server: ServerUrl): CertificateDetails? {
        if (server.scheme != "https") return null
        return probe(server.host, server.effectivePort())
    }

    /**
     * @throws IOException when the host cannot be reached at all, which is a different problem
     *   from an untrusted certificate and must be reported as such.
     */
    public suspend fun probe(host: String, port: Int): CertificateDetails? = withContext(ioDispatcher) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(CollectingTrustManager), null)
        val factory = context.socketFactory
        var socket: SSLSocket? = null
        try {
            socket = factory.createSocket() as SSLSocket
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            // Send SNI so a virtual host returns the certificate it would return to the app.
            socket.sslParameters = socket.sslParameters.withSni(host)
            socket.startHandshake()
            val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            leaf?.let { CertificateDetails.of(host, it) }
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun SSLParameters.withSni(host: String): SSLParameters = apply {
        val name = runCatching { SNIHostName(host) }.getOrNull()
        if (name != null) serverNames = listOf(name)
    }

    /**
     * Accepts any chain so the peer certificate can be *read*. Never installed on the client
     * used for application traffic — see the class documentation.
     */
    private object CollectingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
