package app.needler.core.data.repository

import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.security.CredentialStoreState
import app.needler.core.data.security.FakeSharedPreferences
import app.needler.core.data.security.SecureCredentialStore
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.Outcome
import app.needler.core.network.ServerUrl
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.tls.MutableCertificatePinStore
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What tapping "Trust this certificate" actually does, and whether it is still done after a
 * relaunch.
 *
 * REQUIREMENTS.md "Self-signed certificates" promises the user can "pin that exact certificate for
 * this server". Three separate breaks meant they could not, and none of them was visible from
 * either side alone, which is why these tests run the real credential store over a fake preferences
 * file and the real pin store the TLS trust manager consults:
 *
 *  * the fingerprint was persisted and the in-memory store the handshake reads was never told, so
 *    the next request failed identically;
 *  * nothing seeded that store at startup, so even a persisted pin was gone on the next launch;
 *  * the two halves spelled the same 32 bytes differently - hex on the disk, base64 in the
 *    comparison - so a pin that did arrive still could not match.
 *
 * `CertificatePinStoreTest` in `:core:network` asserts the last of those against real certificates.
 * This one asserts the wiring: that a pin reaches both halves, that it comes back after a simulated
 * process replacement, and that it never spreads to a host the user did not pin.
 */
public class SessionCertificatePinningTest {

    /** The credential file, as the next process would find it. */
    private val preferences: FakeSharedPreferences = FakeSharedPreferences()

    private val mirror: FakeSharedPreferences = FakeSharedPreferences()

    private val v1: FakeV1Api = FakeV1Api()
    private val subsonic: FakeSubsonicApi = FakeSubsonicApi()

    /** The store the trust manager reads on every handshake. One per simulated process. */
    private var pins: MutableCertificatePinStore = MutableCertificatePinStore()

    /** Two stores over one file is what a relaunch is. */
    private fun store(): SecureCredentialStore = SecureCredentialStore.createForTesting(
        preferences = preferences,
        files = null,
        serverMirror = mirror,
        state = CredentialStoreState.Opened,
        diagnostics = DiagnosticsSink.None,
    )

    private fun repository(
        credentials: SecureCredentialStore,
        pinStore: MutableCertificatePinStore,
    ): DefaultSessionRepository = DefaultSessionRepository(
        credentials = credentials,
        v1 = v1,
        capabilityProbe = CapabilityProbe(v1 = v1, subsonic = subsonic),
        networkMonitor = FakeNetworkMonitor(),
        pins = pinStore,
    )

    /**
     * The launch, exactly as `DataModule` performs it: a store over whatever is on the disk, and a
     * pin store seeded from it. If this seeding is removed, the relaunch tests below fail.
     */
    private fun launch(): Pair<SecureCredentialStore, DefaultSessionRepository> {
        val credentials: SecureCredentialStore = store()
        pins = MutableCertificatePinStore(credentials.pinnedCertificates())
        return credentials to repository(credentials, pins)
    }

    // ------------------------------------------------------------------ trusting

    @Test
    public fun `trusting a certificate pins it where the handshake will look`(): Unit = runTest {
        val (credentials, sessions) = launch()
        credentials.saveServerUrl(url(SERVER))

        val outcome: Outcome<Unit> = sessions.trustCertificate(CERTIFICATE)

        assertTrue(outcome.toString(), outcome is Outcome.Success)
        // The in-memory store `PinnedHostTrustManager` consults. This is the assertion that was
        // missing: before it, every pin went to the disk and nowhere else.
        assertEquals(setOf(FINGERPRINT), pins.pinsFor(HOST))
        // And the disk, so the decision outlives the process.
        assertEquals(FINGERPRINT, preferences.getString("pinned_certificate_sha256", null))
        assertEquals(HOST, preferences.getString("pinned_certificate_host", null))
    }

    /**
     * The relaunch. A pin accepted in one session has to be in force before the first request of
     * the next one, or the user re-confirms the same fingerprint every time they open the app.
     */
    @Test
    public fun `a pinned certificate is in force again on the next launch`(): Unit = runTest {
        val (first, sessions) = launch()
        first.saveServerUrl(url(SERVER))
        sessions.trustCertificate(CERTIFICATE)

        // A second store and a second pin store over the same file, with nothing shared in memory.
        val (restored, _) = launch()

        assertEquals(setOf(FINGERPRINT), pins.pinsFor(HOST))
        assertEquals(FINGERPRINT, restored.pinnedCertificateFor(HOST))
    }

    /** One host, as the doc says: never a global exception, never a second server inheriting it. */
    @Test
    public fun `a pin is scoped to the host it was confirmed for`(): Unit = runTest {
        val (credentials, sessions) = launch()
        credentials.saveServerUrl(url(SERVER))

        sessions.trustCertificate(CERTIFICATE)

        assertTrue(pins.pinsFor("music.elsewhere.net").isEmpty())
        assertNull(credentials.pinnedCertificateFor("music.elsewhere.net"))
    }

    /**
     * With no saved address there is no host to scope a pin to, and an unscoped pin is the global
     * trust-all REQUIREMENTS.md forbids. So this fails rather than pinning anything.
     */
    @Test
    public fun `trusting with no saved server pins nothing at all`(): Unit = runTest {
        val (_, sessions) = launch()

        val outcome: Outcome<Unit> = sessions.trustCertificate(CERTIFICATE)

        assertTrue(outcome.toString(), outcome is Outcome.Failure)
        assertTrue(pins.snapshot().toString(), pins.snapshot().isEmpty())
        assertNull(preferences.getString("pinned_certificate_sha256", null))
    }

    /**
     * Re-confirmation after a reissue replaces the pin rather than adding to it: the disk holds one
     * fingerprint per host, and a superseded certificate that stayed acceptable would never fail
     * loudly again.
     */
    @Test
    public fun `re-confirming after a reissue replaces the pin instead of adding to it`(): Unit =
        runTest {
            val (credentials, sessions) = launch()
            credentials.saveServerUrl(url(SERVER))
            sessions.trustCertificate(CERTIFICATE)

            sessions.trustCertificate(CERTIFICATE.copy(sha256Fingerprint = REISSUED_FINGERPRINT))

            assertEquals(setOf(REISSUED_FINGERPRINT), pins.pinsFor(HOST))
            assertEquals(
                REISSUED_FINGERPRINT,
                preferences.getString("pinned_certificate_sha256", null),
            )
        }

    // --------------------------------------------------------------- server change

    /**
     * REQUIREMENTS.md "Secrets and migrations" drops the mirror and the cache when the server
     * identity changes. The pin goes with them, and for a stronger reason: stale data is merely
     * stale, while a fingerprint left behind is an exception the **new** host inherits without the
     * user ever being shown it.
     */
    @Test
    public fun `changing the server host drops the pin from both halves`(): Unit = runTest {
        val (credentials, sessions) = launch()
        credentials.saveServerUrl(url(SERVER))
        sessions.trustCertificate(CERTIFICATE)

        sessions.probeServer("https://music.elsewhere.net")

        assertTrue(pins.snapshot().toString(), pins.snapshot().isEmpty())
        assertNull(preferences.getString("pinned_certificate_sha256", null))
        assertNull(preferences.getString("pinned_certificate_host", null))
        // And it does not come back on the next launch either.
        launch()
        assertTrue(pins.snapshot().toString(), pins.snapshot().isEmpty())
    }

    /**
     * A scheme or port change is the same server. `probeServer` walks several rungs of one host
     * before anything has failed, so revoking the pin per rung would undo the user's decision
     * mid-walk - which is exactly when it is needed.
     */
    @Test
    public fun `another rung of the same host keeps the pin`(): Unit = runTest {
        val (credentials, sessions) = launch()
        credentials.saveServerUrl(url(SERVER))
        sessions.trustCertificate(CERTIFICATE)

        sessions.probeServer(HOST)

        assertEquals(setOf(FINGERPRINT), pins.pinsFor(HOST))
        assertEquals(FINGERPRINT, credentials.pinnedCertificateFor(HOST))
    }

    /** Sign-out takes the pin with the secrets, in memory as well as on the disk. */
    @Test
    public fun `signing out revokes the pin`(): Unit = runTest {
        val (credentials, sessions) = launch()
        credentials.saveServerUrl(url(SERVER))
        sessions.trustCertificate(CERTIFICATE)

        sessions.signOut(revokeRemote = false)

        assertTrue(pins.snapshot().toString(), pins.snapshot().isEmpty())
        assertNull(preferences.getString("pinned_certificate_sha256", null))
    }

    // ------------------------------------------------------------------ migration

    /**
     * An install that pinned a certificate before the host was recorded beside it. Its fingerprint
     * belongs to the saved server's host - the only server that install ever had an address for -
     * so it is restored for that host rather than discarded, which would send a user who had
     * already confirmed their certificate back through the prompt.
     */
    @Test
    public fun `a pin saved before hosts were recorded is restored for the saved server`() {
        preferences.edit()
            .putString("server_url", SERVER)
            .putString("pinned_certificate_sha256", FINGERPRINT)
            .commit()

        val (credentials, _) = launch()

        assertEquals(mapOf(HOST to setOf(FINGERPRINT)), credentials.pinnedCertificates())
        assertEquals(setOf(FINGERPRINT), pins.pinsFor(HOST))
        assertNull(credentials.pinnedCertificateFor("music.elsewhere.net"))
    }

    /**
     * And a fingerprint whose host cannot be worked out at all - no recorded host, no saved address
     * - is dropped rather than adopted by the next server to be configured. That adoption is the
     * one failure this is all arranged to prevent.
     */
    @Test
    public fun `a fingerprint that cannot name its host is not inherited by the next server`() {
        preferences.edit().putString("pinned_certificate_sha256", FINGERPRINT).commit()

        val (credentials, _) = launch()
        val restored: Map<String, Set<String>> = credentials.pinnedCertificates()
        assertTrue(restored.toString(), restored.isEmpty())

        credentials.saveServerUrl(url(SERVER))

        assertNull(credentials.pinnedCertificateFor(HOST))
        assertNull(preferences.getString("pinned_certificate_sha256", null))
    }

    private fun url(raw: String): ServerUrl = requireNotNull(ServerUrl.parseOrNull(raw))

    private companion object {

        const val HOST: String = "music.yourhome.net"
        const val SERVER: String = "https://music.yourhome.net"

        /** Canonical, as `CertificateDetails.sha256Hex` renders it. */
        const val FINGERPRINT: String =
            "22:2D:19:1A:BF:81:7A:C6:14:8E:AB:68:4C:6C:BA:1F:49:98:6B:92:2F:87:82:28:B0:11:53:5C:66:B4:8D:D7"

        const val REISSUED_FINGERPRINT: String =
            "2D:3B:1B:F3:FE:3A:FB:0A:0E:1E:21:16:DE:0B:6A:BE:A9:99:98:69:4B:C1:0F:8E:A4:44:98:BE:96:C1:BB:A8"

        val CERTIFICATE: CertificateInfo = CertificateInfo(
            sha256Fingerprint = FINGERPRINT,
            subject = "CN=music.yourhome.net",
            issuer = "CN=music.yourhome.net",
            notAfter = Instant.fromEpochMilliseconds(4_000_000_000_000),
        )
    }
}
