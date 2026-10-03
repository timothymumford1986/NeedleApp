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
import app.needler.core.domain.model.PinnedCertificate
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.network.ServerUrl
import app.needler.core.network.ServerUrlResult
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.tls.CertificatePinStore
import app.needler.core.network.tls.MutableCertificatePinStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trusting a certificate, reading back what was trusted, and taking it away again.
 *
 * ## The gap this closes
 *
 * `trustCertificate` was a one-way door. A user could grant a self-signed certificate an exception on
 * the Connect screen and from then on nothing in the application said that they had, what they had
 * trusted, or offered any way to revoke it - so the most consequential security decision in the
 * product was the only one with no record and no undo. REQUIREMENTS.md "Self-signed certificates"
 * scopes a pin to "the one host"; a scope the user cannot inspect is a scope they are taking on trust.
 *
 * ## Both stores, every time
 *
 * The thing that goes wrong with a pin is that only one of the two stores hears about it, and the
 * symptom depends on which: `SecureCredentialStore` is the record that survives a launch, and
 * `MutableCertificatePinStore` is what the TLS trust manager consults during a handshake. A grant
 * written only to the first takes effect on the next launch; a revocation written only to the first is
 * still honoured until the process dies. That asymmetry is why these tests read both rather than
 * asking the repository what it thinks it did - and why the revocation is asserted against the
 * preference file as well as the live set, which is the state the *next* process starts from.
 */
public class CertificatePinLifecycleTest {

    private val preferences: FakeSharedPreferences = FakeSharedPreferences()
    private val mirror: FakeSharedPreferences = FakeSharedPreferences()
    private val pins: MutableCertificatePinStore = MutableCertificatePinStore()

    private val diagnostics = DiagnosticsSink { _, _ -> }

    private val v1: FakeV1Api = FakeV1Api()
    private val subsonic: FakeSubsonicApi = FakeSubsonicApi()

    private fun store(): SecureCredentialStore = SecureCredentialStore.createForTesting(
        preferences = preferences,
        files = null,
        serverMirror = mirror,
        state = CredentialStoreState.Opened,
        diagnostics = diagnostics,
    )

    private fun repository(credentials: SecureCredentialStore): SessionRepository =
        DefaultSessionRepository(
            credentials = credentials,
            v1 = v1,
            capabilityProbe = CapabilityProbe(v1 = v1, subsonic = subsonic),
            networkMonitor = FakeNetworkMonitor(),
            nowMillis = { NOW },
            pins = pins,
        )

    /** A saved address, which is where `trustCertificate` reads the host to scope the pin to. */
    private fun savedServer(credentials: SecureCredentialStore) {
        val parsed: ServerUrlResult = ServerUrl.parse(SERVER)
        credentials.saveServerUrl((parsed as ServerUrlResult.Valid).url)
    }

    @Test
    public fun `nothing is pinned on a fresh install`(): Unit = runTest {
        assertNull(repository(store()).pinnedCertificate())
    }

    @Test
    public fun `what was trusted can be read back, host and fingerprint`(): Unit = runTest {
        val credentials: SecureCredentialStore = store()
        savedServer(credentials)
        val session: SessionRepository = repository(credentials)

        assertTrue(session.trustCertificate(certificate()) is Outcome.Success)

        val pinned: PinnedCertificate? = session.pinnedCertificate()
        assertEquals(HOST, pinned?.host)
        assertEquals(
            "the fingerprint has to come back in the one canonical rendering, or the user is " +
                "comparing it against their server by eye in a different spelling",
            CertificatePinStore.normalisePin(FINGERPRINT),
            pinned?.sha256Fingerprint,
        )
    }

    /** It survives the process, which is the only reason it is on disk at all. */
    @Test
    public fun `the pin is readable from a second process`(): Unit = runTest {
        val first: SecureCredentialStore = store()
        savedServer(first)
        repository(first).trustCertificate(certificate())

        // A second store and a second repository over the same file, with nothing shared in memory.
        val restored: PinnedCertificate? = repository(store()).pinnedCertificate()

        assertEquals(HOST, restored?.host)
    }

    @Test
    public fun `forgetting it clears the disk and the live set together`(): Unit = runTest {
        val credentials: SecureCredentialStore = store()
        savedServer(credentials)
        val session: SessionRepository = repository(credentials)
        session.trustCertificate(certificate())
        assertTrue("the pin should be live before it is revoked", pins.pinsFor(HOST).isNotEmpty())

        assertTrue(session.forgetPinnedCertificate() is Outcome.Success)

        assertNull("the repository still reports a pin", session.pinnedCertificate())
        assertTrue(
            "the handshake would still trust the certificate this process has just revoked",
            pins.pinsFor(HOST).isEmpty(),
        )
        // The file, not the cache: a revocation that comes back on the next launch has told the user
        // something false about their own device.
        assertNull(preferences.getString("pinned_certificate_sha256", null))
        assertNull(preferences.getString("pinned_certificate_host", null))
        assertNull(repository(store()).pinnedCertificate())
    }

    /**
     * Forgetting nothing succeeds.
     *
     * The caller asked for a state - no pin - and that state already holds. Reporting a failure would
     * make the Settings row say "could not forget the certificate" about a certificate that is not
     * there.
     */
    @Test
    public fun `forgetting when nothing is pinned is not a failure`(): Unit = runTest {
        assertTrue(repository(store()).forgetPinnedCertificate() is Outcome.Success)
    }

    private fun certificate(): CertificateInfo = CertificateInfo(
        sha256Fingerprint = FINGERPRINT,
        subject = "CN=music.yourhome.net",
        issuer = "CN=music.yourhome.net",
        notAfter = null,
    )

    private companion object {
        const val SERVER: String = "https://music.yourhome.net"
        const val HOST: String = "music.yourhome.net"
        const val NOW: Long = 1_762_000_000_000L

        /** 32 bytes of colon-separated hex, as `CertificatePinStore.sha256Hex` renders one. */
        const val FINGERPRINT: String =
            "22:2D:19:4A:5B:6C:7D:8E:9F:A0:B1:C2:D3:E4:F5:06:17:28:39:4A:5B:6C:7D:8E:9F:A0:B1:C2:" +
                "D3:E4:F5:06"
    }
}
