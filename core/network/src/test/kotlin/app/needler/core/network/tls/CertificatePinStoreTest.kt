package app.needler.core.network.tls

import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does pinning a certificate actually make that certificate acceptable?
 *
 * The test that was missing. "Trust this certificate" wrote a fingerprint to the disk and nothing
 * ever reached this store, and when something finally did, the two halves disagreed about the
 * encoding: a pin written as hex was compared as base64, so [CertificatePinStore.matches] answered
 * false for the very leaf the user had just confirmed. Nothing in the suite asked the one question
 * that would have caught either fault - pin a certificate, then ask whether it matches - so these
 * tests ask it against real certificates rather than fingerprints typed into a fixture.
 *
 * REQUIREMENTS.md "Self-signed certificates" is the contract: show the fingerprint and let the user
 * "pin that exact certificate for this server", pin "the leaf certificate for the one host, never
 * disable validation globally", and a changed fingerprint "must fail loudly and require
 * re-confirmation". Each of those is a test here, and the last two are the ones that must survive
 * any future change to how a pin is stored.
 *
 * The two certificates are real, self-signed, and generated once with `openssl` rather than at test
 * time: a fixed certificate means a fixed fingerprint, so the expected values below are the output
 * of `openssl x509 -fingerprint -sha256` and not of the code under test. [REISSUED_PEM] stands in for
 * the self-hosted server that regenerated its certificate - the case the loud failure exists for.
 */
class CertificatePinStoreTest {

    private val leaf: X509Certificate = certificate(SELF_SIGNED_PEM)
    private val reissued: X509Certificate = certificate(REISSUED_PEM)

    // ------------------------------------------------------------------ the encoding

    /**
     * Locks the canonical rendering to what a user comparing by eye against their server sees, and
     * to what `openssl` prints. If this fails, every stored pin in the field has become unreadable.
     */
    @Test
    fun `a fingerprint is the colon separated uppercase hex of the der leaf`() {
        assertEquals(SELF_SIGNED_HEX, CertificatePinStore.sha256Hex(leaf))
        assertEquals(SELF_SIGNED_HEX, CertificateDetails.of(HOST, leaf).sha256Hex)
    }

    /** Lower case, no separators, spaces, dashes: all the same 32 bytes, all the same pin. */
    @Test
    fun `any spelling of one fingerprint normalises to the canonical one`() {
        val digits: String = SELF_SIGNED_HEX.replace(":", "")
        val spaced: String = digits.chunked(2).joinToString(" ")
        val dashed: String = digits.chunked(2).joinToString("-")

        assertEquals(SELF_SIGNED_HEX, CertificatePinStore.normalisePin(digits.lowercase()))
        assertEquals(SELF_SIGNED_HEX, CertificatePinStore.normalisePin(spaced))
        assertEquals(SELF_SIGNED_HEX, CertificatePinStore.normalisePin(dashed))
    }

    /**
     * A value that is not a SHA-256 comes back unusable rather than being massaged into something
     * that might match. Denying access is the only safe direction for an unparseable pin.
     */
    @Test
    fun `something that is not a fingerprint cannot be normalised into one`() {
        assertEquals("NOT-A-FINGERPRINT", CertificatePinStore.normalisePin(" not-a-fingerprint "))
        assertFalse(MutableCertificatePinStore(mapOf(HOST to setOf("nonsense"))).matches(HOST, leaf))
    }

    // ------------------------------------------------------------------ pin, then match

    @Test
    fun `pinning a certificate makes that certificate match for that host`() {
        val store = MutableCertificatePinStore()
        assertFalse(store.matches(HOST, leaf))

        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))

        assertTrue(store.matches(HOST, leaf))
    }

    /** The user's own `CertificateDetails`, pinned as the Connect screen hands it over. */
    @Test
    fun `pinning the details the user was shown pins the certificate they looked at`() {
        val store = MutableCertificatePinStore()

        store.pin(CertificateDetails.of(HOST, leaf))

        assertTrue(store.matches(HOST, leaf))
    }

    /**
     * The restart, at this layer: a pin read back off the disk is a string, not a certificate, and
     * it has to match the certificate it was computed from.
     */
    @Test
    fun `a pin restored from storage matches the certificate it was taken from`() {
        val restored = MutableCertificatePinStore(mapOf(HOST to setOf(SELF_SIGNED_HEX)))

        assertTrue(restored.matches(HOST, leaf))
    }

    /** And one restored in any other spelling of the same bytes matches too. */
    @Test
    fun `a pin restored in another spelling still matches`() {
        val restored = MutableCertificatePinStore(
            mapOf(HOST.uppercase() to setOf(SELF_SIGNED_HEX.replace(":", "").lowercase())),
        )

        assertTrue(restored.matches(HOST, leaf))
    }

    // ------------------------------------------------------------------ one host only

    @Test
    fun `a pin is an exception for one host and no other`() {
        val store = MutableCertificatePinStore()

        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))

        assertFalse(store.matches(OTHER_HOST, leaf))
        assertTrue(store.pinsFor(OTHER_HOST).isEmpty())
    }

    /** Case and a trailing dot are the same host; a different name is not. */
    @Test
    fun `a host is matched canonically`() {
        val store = MutableCertificatePinStore()

        store.pin("Music.YourHome.net.", CertificatePinStore.sha256Hex(leaf))

        assertTrue(store.matches(HOST, leaf))
        assertTrue(store.matches(HOST.uppercase(), leaf))
    }

    @Test
    fun `an empty store trusts nothing`() {
        assertFalse(CertificatePinStore.Empty.matches(HOST, leaf))
        assertTrue(CertificatePinStore.Empty.pinsFor(HOST).isEmpty())
    }

    // ------------------------------------------------------- a changed certificate

    /**
     * The loud failure. A server that regenerated its certificate presents a leaf the pin does not
     * cover, so the handshake fails and the user is asked again - which is what
     * `NeedlerError.CertificateChanged` is raised from.
     */
    @Test
    fun `a reissued certificate does not inherit the pin of the one it replaced`() {
        val store = MutableCertificatePinStore()

        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))

        assertFalse(store.matches(HOST, reissued))
    }

    /**
     * And confirming the new one does not leave the old one acceptable. The persisted side holds
     * one fingerprint per host, so a set that grew would disagree with the disk until the next
     * launch quietly narrowed it back - and the superseded certificate would never fail again.
     */
    @Test
    fun `confirming a new certificate replaces the old pin instead of joining it`() {
        val store = MutableCertificatePinStore()
        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))

        store.pin(HOST, CertificatePinStore.sha256Hex(reissued))

        assertEquals(setOf(CertificatePinStore.sha256Hex(reissued)), store.pinsFor(HOST))
        assertTrue(store.matches(HOST, reissued))
        assertFalse(store.matches(HOST, leaf))
    }

    @Test
    fun `dropping a host's pins revokes the exception`() {
        val store = MutableCertificatePinStore()
        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))

        store.clear(HOST)

        assertFalse(store.matches(HOST, leaf))
    }

    // ------------------------------------------------------------------ eviction

    /**
     * A pin that does not take effect until the connection pool happens to recycle is the same bug
     * one layer down, so `NeedlerHttpClient` subscribes to this and evicts pooled connections.
     * Every mutation has to fire it.
     */
    @Test
    fun `every change to the pin set is announced so pooled connections can be evicted`() {
        var changes = 0
        val store = MutableCertificatePinStore()
        store.onChanged = { changes += 1 }

        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))
        store.pin(HOST, CertificatePinStore.sha256Hex(reissued))
        store.clear(HOST)
        store.replaceAll(mapOf(HOST to setOf(SELF_SIGNED_HEX)))

        assertEquals(4, changes)
    }

    /** Re-pinning the same fingerprint changes nothing, so it must not churn the pool either. */
    @Test
    fun `pinning what is already pinned announces nothing`() {
        var changes = 0
        val store = MutableCertificatePinStore(mapOf(HOST to setOf(SELF_SIGNED_HEX)))
        store.onChanged = { changes += 1 }

        store.pin(HOST, SELF_SIGNED_HEX)
        store.clear(OTHER_HOST)

        assertEquals(0, changes)
    }

    // ----------------------------------------------------- the handshake itself

    /**
     * The whole point, through the object OkHttp actually calls: the platform rejects a self-signed
     * leaf, and the pin - and only the pin, for only that host - turns that into an acceptance.
     */
    @Test
    fun `the trust manager accepts a rejected chain only for the host that pinned it`() {
        val store = MutableCertificatePinStore()
        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))
        val manager = PinnedHostTrustManager(RejectingTrustManager, store)
        val chain: Array<X509Certificate> = arrayOf(leaf)

        // Pinned host: accepted.
        manager.checkServerTrusted(chain, "ECDHE_ECDSA", engineFor(HOST))

        // Any other host, and the bare overload that carries no host at all: still rejected.
        assertRejected { manager.checkServerTrusted(chain, "ECDHE_ECDSA", engineFor(OTHER_HOST)) }
        assertRejected { manager.checkServerTrusted(chain, "ECDHE_ECDSA") }
        assertRejected {
            manager.checkServerTrusted(arrayOf(reissued), "ECDHE_ECDSA", engineFor(HOST))
        }
    }

    /**
     * Self-signed certificates routinely have no SAN for the LAN address they are served on, so
     * hostname verification fails even once the trust manager has accepted the pin.
     */
    @Test
    fun `the hostname verifier accepts a pinned leaf the platform would not`() {
        val store = MutableCertificatePinStore()
        store.pin(HOST, CertificatePinStore.sha256Hex(leaf))
        // A delegate that refuses everything: the platform's own verifier, faced with a
        // certificate whose SAN does not cover the address it is being served on.
        val verifier = PinnedHostnameVerifier(store, HostnameVerifier { _, _ -> false })

        assertTrue(verifier.verify(HOST, sessionFor(leaf)))
        assertFalse(verifier.verify(OTHER_HOST, sessionFor(leaf)))
        assertFalse(verifier.verify(HOST, sessionFor(reissued)))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("the chain was accepted when it should have been rejected")
        } catch (expected: CertificateException) {
            assertTrue(expected.toString(), expected.message?.contains("platform") == true)
        }
    }

    /**
     * A real engine rather than a mock: `createSSLEngine(host, port)` is how OkHttp names the peer
     * it is dialling, and `peerHost` is the only thing the trust manager reads off it.
     */
    private fun engineFor(host: String): SSLEngine =
        SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine(host, 443)

    /**
     * [SSLSession] is an interface, so a proxy answering the one method the verifier calls is
     * enough - and is one less library between the test and what it is asserting.
     */
    private fun sessionFor(certificate: X509Certificate): SSLSession =
        Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf<Certificate>(certificate)
                else -> null
            }
        } as SSLSession

    /** Stands in for a platform trust manager that trusts no self-signed certificate. */
    private object RejectingTrustManager : X509ExtendedTrustManager() {

        private fun reject(): Nothing = throw CertificateException("platform: not trusted")

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            reject()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            socket: Socket?,
        ): Unit = reject()

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ): Unit = reject()

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
            reject()

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            socket: Socket?,
        ): Unit = reject()

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ): Unit = reject()

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {

        const val HOST: String = "music.yourhome.net"
        const val OTHER_HOST: String = "music.elsewhere.net"

        /** `openssl x509 -noout -fingerprint -sha256`, for [SELF_SIGNED_PEM]. */
        const val SELF_SIGNED_HEX: String =
            "22:2D:19:1A:BF:81:7A:C6:14:8E:AB:68:4C:6C:BA:1F:49:98:6B:92:2F:87:82:28:B0:11:53:5C:66:B4:8D:D7"

        /**
         * A self-signed `CN=music.yourhome.net`, valid until 2126 so that a pinned leaf is never
         * refused here for being expired - which `PinnedHostTrustManager` does on purpose, and
         * which is a different test from this one.
         */
        val SELF_SIGNED_PEM: String =
            """
            -----BEGIN CERTIFICATE-----
            MIIB3zCCAYagAwIBAgIUbdsKwBZ7qKDtR67WPUeyNY4VO/MwCgYIKoZIzj0EAwIw
            NTEbMBkGA1UEAwwSbXVzaWMueW91cmhvbWUubmV0MRYwFAYDVQQKDA1Ecm9wcGVk
            TmVlZGxlMCAXDTI2MTAwMjExNTUwOVoYDzIxMjYwOTA4MTE1NTA5WjA1MRswGQYD
            VQQDDBJtdXNpYy55b3VyaG9tZS5uZXQxFjAUBgNVBAoMDURyb3BwZWROZWVkbGUw
            WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARo5CtzcrMKIZT4+qgtSqnn5wffuvi4
            8lybbmOrclVaWgO4jA98Un24Sc/U0GDmjMdsnUYHHKRxbP/1V8LuM++ho3IwcDAd
            BgNVHQ4EFgQUdq/0WWfMSVmpGr3tDioMbdlTLswwHwYDVR0jBBgwFoAUdq/0WWfM
            SVmpGr3tDioMbdlTLswwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHREEFjAUghJtdXNp
            Yy55b3VyaG9tZS5uZXQwCgYIKoZIzj0EAwIDRwAwRAIga1Zah4uhvQAtoAx0CmzB
            QBs1cF/VPcZupjzKA27y6QoCIGIU+KS6QUABtzMByvUTonxKIBNbYjjgvY/fRt/n
            6M0r
            -----END CERTIFICATE-----
            """.trimIndent()

        /** The same subject, a new key: the self-hosted server that regenerated its certificate. */
        val REISSUED_PEM: String =
            """
            -----BEGIN CERTIFICATE-----
            MIIB4DCCAYagAwIBAgIUNA+9nOVL6x2oTTWy46WpxJpwShowCgYIKoZIzj0EAwIw
            NTEbMBkGA1UEAwwSbXVzaWMueW91cmhvbWUubmV0MRYwFAYDVQQKDA1Ecm9wcGVk
            TmVlZGxlMCAXDTI2MTAwMjExNTUwOVoYDzIxMjYwOTA4MTE1NTA5WjA1MRswGQYD
            VQQDDBJtdXNpYy55b3VyaG9tZS5uZXQxFjAUBgNVBAoMDURyb3BwZWROZWVkbGUw
            WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATI2Cisz4dRbSxa1pVWAXWil8+DUJwx
            i3waikQ6/3GNbAupsGSyk7a0SYFUVuZCdp5J9Zgs0KbQE3am/iXfYBNRo3IwcDAd
            BgNVHQ4EFgQUF7nAVd9Snj4ohD/6PrdTWg7Lm6owHwYDVR0jBBgwFoAUF7nAVd9S
            nj4ohD/6PrdTWg7Lm6owDwYDVR0TAQH/BAUwAwEB/zAdBgNVHREEFjAUghJtdXNp
            Yy55b3VyaG9tZS5uZXQwCgYIKoZIzj0EAwIDSAAwRQIhANgIerSjiWv8iOeEY82h
            VvtaMHy+xww+92nZCFGm+1D2AiBJ8sGifGBVIC6mwJJPvyt5bVjxFLWV2J0PsrqU
            HMBPUg==
            -----END CERTIFICATE-----
            """.trimIndent()

        fun certificate(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem.toByteArray()))
                as X509Certificate
    }
}
