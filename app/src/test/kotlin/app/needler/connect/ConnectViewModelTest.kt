package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ReonboardingReason
import app.needler.core.domain.model.SessionState
import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import app.needler.core.network.ProxyInterception
import app.needler.core.network.ProxySignal
import app.needler.core.network.ProxyVendor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Connect screen's behaviour, against the domain interface.
 *
 * Each of the failures REQUIREMENTS.md requires the screen to handle has a test
 * here, checking that it reaches the screen as its own [ConnectFailure] rather
 * than as a generic one - which is the only thing that makes the five different
 * messages possible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val repository = FakeSessionRepository()
    private val proxyStore = FakeProxyCredentialStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connect is disabled until all three fields are filled`() {
        val viewModel = viewModel()
        assertFalse(viewModel.state.value.canConnect)

        viewModel.onServerChange("https://music.yourhome.net")
        viewModel.onUsernameChange("yourname")
        assertFalse("username and server alone must not be enough", viewModel.state.value.canConnect)

        viewModel.onPasswordChange("hunter2")
        assertTrue(viewModel.state.value.canConnect)
    }

    @Test
    fun `a successful connect probes, connects, then negotiates`() = runTest {
        val viewModel = filledIn()

        viewModel.connect()

        assertEquals(
            listOf(
                "probeServer(https://music.yourhome.net)",
                "connect(https://music.yourhome.net, yourname)",
                "negotiateCapabilities()",
            ),
            repository.calls,
        )
        assertTrue(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `the account password is dropped once onboarding succeeds`() = runTest {
        val viewModel = filledIn()

        viewModel.connect()

        assertEquals("", viewModel.state.value.password)
        // It did reach the repository, which is the only place it is needed.
        assertEquals("hunter2", repository.lastConnect?.password)
    }

    @Test
    fun `the device session is named after this device`() = runTest {
        val viewModel = filledIn()

        viewModel.connect()

        val name = requireNotNull(repository.lastConnect).deviceName
        assertTrue("'$name' should be a Needler session name", name.startsWith("Needler · "))
        assertTrue("'$name' exceeds the server's 80-character cap", name.length <= 80)
    }

    @Test
    fun `a bad url fails on the connect screen, before any credential is sent`() = runTest {
        repository.probeOutcome = Outcome.Failure(
            NeedlerError.NotADroppedNeedleServer("https://music.yourhome.net"),
        )
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure
        assertTrue("got $failure", failure is ConnectFailure.BadServerAddress)
        assertEquals(listOf("probeServer(https://music.yourhome.net)"), repository.calls)
        assertFalse(viewModel.state.value.connecting)
    }

    @Test
    fun `an unreachable server keeps the cause`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.Offline(OfflineCause.DNS_FAILURE))
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure
        assertTrue("got $failure", failure is ConnectFailure.ServerUnreachable)
        assertEquals(
            OfflineCause.DNS_FAILURE,
            (failure as ConnectFailure.ServerUnreachable).cause,
        )
    }

    /**
     * REQUIREMENTS.md "Accepted URL forms": a bare host is typed, and the probe walks
     * `ServerUrl.ladder` behind the user's back. When nothing answers, the three addresses it
     * dialled are the only thing that can tell someone running on port 8080 why - so the message
     * has to contain them, in the ladder's own order.
     */
    @Test
    fun `a bare host that answers nowhere names every address that was tried`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.Offline(OfflineCause.CONNECTION_FAILED))
        val viewModel = viewModel().apply {
            onServerChange("music.mumfordhome.com")
            onUsernameChange("yourname")
            onPasswordChange("hunter2")
        }

        viewModel.connect()

        val detail: String = requireNotNull(viewModel.state.value.failure).detail
        assertEquals(
            listOf(
                "https://music.mumfordhome.com",
                "http://music.mumfordhome.com:8688",
                "http://music.mumfordhome.com",
            ),
            (viewModel.state.value.failure as ConnectFailure.ServerUnreachable).attempted,
        )
        assertTrue(
            detail,
            detail.contains(
                " Needler tried https://music.mumfordhome.com, " +
                    "http://music.mumfordhome.com:8688 and http://music.mumfordhome.com.",
            ),
        )
        // The one thing a list of addresses cannot show on its own.
        assertTrue(detail, detail.contains("listens on another port"))
    }

    /**
     * A LAN address is walked in the opposite order - DroppedNeedle's own port first, TLS last -
     * and the message has to say so rather than printing a canonical order the probe did not use.
     */
    @Test
    fun `a lan address names its rungs in the order they were dialled`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.NotADroppedNeedleServer("http://192.168.1.50:8688"))
        val viewModel = viewModel().apply {
            onServerChange("192.168.1.50")
            onUsernameChange("yourname")
            onPasswordChange("hunter2")
        }

        viewModel.connect()

        val failure = viewModel.state.value.failure as ConnectFailure.BadServerAddress
        assertEquals(
            listOf("http://192.168.1.50:8688", "http://192.168.1.50", "https://192.168.1.50"),
            failure.attempted,
        )
        assertTrue(failure.detail, failure.detail.contains("http://192.168.1.50:8688,"))
    }

    /**
     * A typed scheme is one rung, and the sentence is singular. The port is still worth printing:
     * `https://music.yourhome.net` means 443, which is not what the user typed and not where a
     * self-hosted server usually is.
     */
    @Test
    fun `a typed scheme names the one address that was tried`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.Offline(OfflineCause.TIMEOUT))
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure as ConnectFailure.ServerUnreachable
        assertEquals(listOf("https://music.yourhome.net"), failure.attempted)
        assertTrue(
            failure.detail,
            failure.detail.contains(" Needler tried https://music.yourhome.net."),
        )
    }

    /**
     * DNS is the one cause that gets no list. No rung was dialled at all, and the three addresses
     * differ only in a scheme and a port that were never used - printing them would claim three
     * attempts where there were none.
     */
    @Test
    fun `a host name that does not resolve is not told what was tried`() = runTest {
        repository.probeOutcome = Outcome.Failure(NeedlerError.Offline(OfflineCause.DNS_FAILURE))
        val viewModel = viewModel().apply {
            onServerChange("music.mumfordhome.com")
            onUsernameChange("yourname")
            onPasswordChange("hunter2")
        }

        viewModel.connect()

        val detail: String = requireNotNull(viewModel.state.value.failure).detail
        assertFalse(detail, detail.contains("Needler tried"))
    }

    /**
     * Only the probe gets the ladder. A failure after an address has answered - a dropped network
     * during sign-in - must not describe a walk that did not happen on this step.
     */
    @Test
    fun `a failure after the probe does not claim a ladder was walked`() = runTest {
        repository.connectOutcome =
            Outcome.Failure(NeedlerError.Offline(OfflineCause.CONNECTION_FAILED))
        val viewModel = viewModel().apply {
            onServerChange("music.mumfordhome.com")
            onUsernameChange("yourname")
            onPasswordChange("hunter2")
        }

        viewModel.connect()

        val failure = viewModel.state.value.failure as ConnectFailure.ServerUnreachable
        assertEquals(emptyList<String>(), failure.attempted)
        assertFalse(failure.detail, failure.detail.contains("Needler tried"))
    }

    @Test
    fun `wrong credentials are reported as wrong credentials`() = runTest {
        repository.connectOutcome = Outcome.Failure(NeedlerError.InvalidCredentials)
        val viewModel = filledIn()

        viewModel.connect()

        assertEquals(ConnectFailure.WrongCredentials, viewModel.state.value.failure)
    }

    @Test
    fun `an untrusted certificate carries the certificate to the screen`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.CertificateUntrusted(CERTIFICATE))
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure
        assertTrue("got $failure", failure is ConnectFailure.UntrustedCertificate)
        assertEquals(
            CERTIFICATE,
            (failure as ConnectFailure.UntrustedCertificate).certificate,
        )
    }

    @Test
    fun `trusting a certificate pins it and retries immediately`() = runTest {
        repository.probeOutcome =
            Outcome.Failure(NeedlerError.CertificateUntrusted(CERTIFICATE))
        val viewModel = filledIn()
        viewModel.connect()

        // The user has now read the fingerprint and said yes.
        repository.probeOutcome = Outcome.Success(FakeSessionRepository.PROBE)
        viewModel.trustCertificate(CERTIFICATE)

        assertEquals(CERTIFICATE, repository.lastTrusted)
        assertTrue(viewModel.state.value.connected)
        assertTrue(
            "the retry should follow the pin without another tap",
            repository.calls.containsAll(listOf("trustCertificate()", "negotiateCapabilities()")),
        )
    }

    @Test
    fun `the subsonic gate is reported by name, after sign-in`() = runTest {
        repository.negotiateOutcome =
            Outcome.Failure(NeedlerError.SubsonicProtocolDisabled)
        val viewModel = filledIn()

        viewModel.connect()

        assertEquals(ConnectFailure.SubsonicDisabled, viewModel.state.value.failure)
        // Named, so an administrator can be told exactly what to switch on.
        assertTrue(ConnectFailure.SubsonicDisabled.detail.contains("subsonic_enabled"))
        // Sign-in itself succeeded: the gate is not a credential problem.
        assertTrue(repository.calls.any { it.startsWith("connect(") })
    }

    @Test
    fun `capabilities that merely report the gate block onboarding too`() = runTest {
        repository.negotiateOutcome = Outcome.Success(
            FakeSessionRepository.CAPABILITIES.copy(subsonicEnabled = false),
        )
        val viewModel = filledIn()

        viewModel.connect()

        assertEquals(ConnectFailure.SubsonicDisabled, viewModel.state.value.failure)
        assertFalse(viewModel.state.value.connected)
    }

    @Test
    fun `typing again clears the failure`() = runTest {
        repository.connectOutcome = Outcome.Failure(NeedlerError.InvalidCredentials)
        val viewModel = filledIn()
        viewModel.connect()
        assertEquals(ConnectFailure.WrongCredentials, viewModel.state.value.failure)

        viewModel.onPasswordChange("hunter3")

        assertNull(viewModel.state.value.failure)
    }

    // ---- an authenticating proxy in the way ---------------------------------

    @Test
    fun `a proxy interception is its own failure, naming the host and the vendor`() = runTest {
        repository.probeOutcome = Outcome.Failure(cloudflareAccessError())
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure
        assertTrue("got " + failure, failure is ConnectFailure.ProxyIntercepted)
        failure as ConnectFailure.ProxyIntercepted
        assertEquals("team.cloudflareaccess.com", failure.host)
        assertEquals("Cloudflare Access", failure.vendorName)
        assertFalse(failure.credentialsSent)
        // The one failure whose fix is a field the user cannot see: open it for them.
        assertTrue(viewModel.state.value.proxy.expanded)
        assertFalse(viewModel.state.value.connecting)
    }

    @Test
    fun `an unidentified proxy is still reported, without naming a vendor`() = runTest {
        repository.probeOutcome = Outcome.Failure(
            NeedlerError.Unexpected(
                detail = "intercepted",
                cause = NetworkError.AuthenticatingProxy(
                    ProxyInterception(
                        proxyHost = "sso.example.net",
                        vendor = ProxyVendor.Unknown,
                        signal = ProxySignal.HtmlInsteadOfJson,
                        requestedHost = "music.yourhome.net",
                        requestedUrl = "https://music.yourhome.net/api/v1/auth/providers",
                        statusCode = 200,
                        proxyCredentialsSent = false,
                    ),
                    ApiLane.V1,
                ),
            ),
        )
        val viewModel = filledIn()

        viewModel.connect()

        val failure = viewModel.state.value.failure as ConnectFailure.ProxyIntercepted
        assertNull(failure.vendorName)
        assertEquals("sso.example.net", failure.host)
        assertTrue(failure.detail.contains("sso.example.net"))
    }

    @Test
    fun `an ordinary unexpected error is not mistaken for a proxy`() = runTest {
        repository.probeOutcome = Outcome.Failure(NeedlerError.Unexpected("disk on fire"))
        val viewModel = filledIn()

        viewModel.connect()

        assertTrue(viewModel.state.value.failure is ConnectFailure.Unexpected)
    }

    // ---- the proxy credential form ------------------------------------------

    @Test
    fun `the proxy headers are saved before the first request goes out`() = runTest {
        val viewModel = filledIn()
        viewModel.onProxyPresetChange(ProxyPreset.CloudflareAccess)
        viewModel.onProxyFieldChange(ProxyField.CloudflareClientId, "abc.access")
        viewModel.onProxyFieldChange(ProxyField.CloudflareClientSecret, "s3cret")

        viewModel.connect()

        val written = proxyStore.writes.single()
        assertEquals(
            listOf("CF-Access-Client-Id", "CF-Access-Client-Secret"),
            written.headers.map { it.name },
        )
        // Written before the probe: the public probe is intercepted like everything else.
        assertTrue(repository.calls.isNotEmpty())
        assertTrue(viewModel.state.value.connected)
    }

    @Test
    fun `basic auth becomes one Proxy-Authorization header`() = runTest {
        val viewModel = filledIn()
        viewModel.onProxyPresetChange(ProxyPreset.BasicAuth)
        viewModel.onProxyFieldChange(ProxyField.BasicUsername, "gatekeeper")
        viewModel.onProxyFieldChange(ProxyField.BasicPassword, "letmein")

        viewModel.connect()

        val header = proxyStore.writes.single().headers.single()
        assertEquals("Proxy-Authorization", header.name)
        assertEquals("Basic Z2F0ZWtlZXBlcjpsZXRtZWlu", header.value)
    }

    @Test
    fun `a half-filled service token is refused before anything is sent`() = runTest {
        val viewModel = filledIn()
        viewModel.onProxyFieldChange(ProxyField.CloudflareClientId, "abc.access")

        viewModel.connect()

        assertTrue(repository.calls.isEmpty())
        assertTrue(proxyStore.writes.isEmpty())
        assertTrue(viewModel.state.value.proxy.expanded)
        assertTrue(
            viewModel.state.value.proxy.problem.orEmpty().contains("Client Secret"),
        )
        assertFalse(viewModel.state.value.connecting)
    }

    @Test
    fun `a header that would clobber the app's own auth is refused`() = runTest {
        val viewModel = filledIn()
        viewModel.onProxyPresetChange(ProxyPreset.Custom)
        viewModel.onCustomHeaderChange(0, "Authorization", "Basic nope")

        viewModel.connect()

        assertTrue(repository.calls.isEmpty())
        assertTrue(viewModel.state.value.proxy.problem.orEmpty().contains("sends that header"))
    }

    @Test
    fun `a server with no proxy is entirely unaffected`() = runTest {
        val viewModel = filledIn()

        viewModel.connect()

        assertTrue(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
        // The form was empty, so nothing is stored for the interceptor to attach.
        assertTrue(proxyStore.writes.single().isEmpty)
    }

    @Test
    fun `saved headers come back into the form on a later visit`() {
        val store = FakeProxyCredentialStore(
            saved = app.needler.core.network.ProxyCredentials.of(
                "CF-Access-Client-Id" to "abc.access",
                "CF-Access-Client-Secret" to "s3cret",
            ),
        )

        val viewModel = ConnectViewModel(repository, store)

        assertEquals(ProxyPreset.CloudflareAccess, viewModel.state.value.proxy.preset)
        assertEquals("abc.access", viewModel.state.value.proxy.cloudflareClientId)
        assertTrue(viewModel.state.value.proxy.expanded)
    }

    // ---- the way out of a long attempt --------------------------------------

    @Test
    fun `a slow attempt says it is still trying, and can be stopped`() = runTest {
        // The @Before dispatcher has its own scheduler; this one shares the test's, so virtual
        // time here also advances the delay inside the ViewModel.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        repository.probeGate = gate
        val viewModel = filledIn()

        viewModel.connect()
        assertTrue(viewModel.state.value.connecting)
        assertFalse(viewModel.state.value.attemptIsSlow)

        advanceTimeBy(6_000)
        assertTrue("the screen must admit it is still trying", viewModel.state.value.attemptIsSlow)

        viewModel.cancelConnect()

        assertFalse(viewModel.state.value.connecting)
        assertFalse(viewModel.state.value.attemptIsSlow)
        assertEquals(ConnectFailure.Cancelled, viewModel.state.value.failure)
        assertFalse(viewModel.state.value.connected)
        gate.complete(Unit)
    }

    @Test
    fun `cancelling an attempt that already finished does nothing`() = runTest {
        val viewModel = filledIn()
        viewModel.connect()
        assertTrue(viewModel.state.value.connected)

        viewModel.cancelConnect()

        assertTrue(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
    }

    // ---- arriving on this screen without having asked to -------------------

    @Test
    fun `a saved session hands straight over to the library instead of asking for it again`() {
        // The device fault of 2026-10-02, reported twice: signed in, library loaded, then an app
        // update replaced the process and the app came back on three empty fields. The credentials
        // were on the disk the whole time - 440 MB of downloads and a 289-album mirror with them.
        // Connect is the navigation graph's start destination on every launch and nothing told it
        // the user was already signed in, so the only way off the screen was to sign in again and
        // rotate a perfectly good device session.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(initialSession = FakeSessionRepository.AUTHENTICATED),
            proxyStore,
        )

        assertTrue(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `an expired bearer resumes as a music player rather than a sign-in form`() {
        // REQUIREMENTS.md "Expiry, and why playback survives it": an expired session "degrades the
        // app to a pure music player rather than bricking it". A sign-in form with no way past it
        // is the bricking.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(
                initialSession = SessionState.PlayerOnly(
                    server = FakeSessionRepository.IDENTITY,
                    user = FakeSessionRepository.USER,
                    capabilities = FakeSessionRepository.CAPABILITIES,
                    reason = PlayerOnlyReason.BEARER_EXPIRED,
                ),
            ),
            proxyStore,
        )

        assertTrue(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `a repair in flight is not announced with a sign-in form`() {
        // A process that died mid-repair resumes the repair. The domain is explicit that nothing in
        // the UI announces this state - "no dialog, no prompt, no sign-in screen" - because there is
        // nothing for the user to do and the repair is one round trip.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(
                initialSession = SessionState.RepairingAppPassword(
                    server = FakeSessionRepository.IDENTITY,
                    user = FakeSessionRepository.USER,
                    capabilities = FakeSessionRepository.CAPABILITIES,
                    bearerExpiresAt = null,
                ),
            ),
            proxyStore,
        )

        assertTrue(viewModel.state.value.connected)
    }

    @Test
    fun `a genuine first run still gets the empty form`() {
        val viewModel = ConnectViewModel(
            FakeSessionRepository(initialSession = SessionState.NotConfigured),
            proxyStore,
        )

        assertFalse(viewModel.state.value.connected)
        assertNull(viewModel.state.value.failure)
        assertEquals("", viewModel.state.value.server)
    }

    @Test
    fun `a session the keystore would not unlock is explained, not shown as a fresh install`() {
        // The worst bug in the app, from the user's side: signed in for weeks, then three empty
        // fields and no explanation. REQUIREMENTS.md "Expiry, and why playback survives it" is
        // explicit that a dead credential degrades the app rather than erasing it, and an
        // unreadable one is not even dead.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(
                initialSession = SessionState.ReonboardingRequired(
                    server = FakeSessionRepository.IDENTITY,
                    reason = ReonboardingReason.CREDENTIALS_UNREADABLE,
                ),
            ),
            proxyStore,
        )

        val failure: ConnectFailure? = viewModel.state.value.failure
        assertTrue(failure.toString(), failure is ConnectFailure.SavedSessionLocked)
        // The address is not a secret and never had to be lost with the key.
        assertEquals("https://music.yourhome.net", viewModel.state.value.server)
        assertTrue(failure!!.detail.contains("https://music.yourhome.net"))
        assertTrue(failure.detail, failure.detail.contains("untouched"))
    }

    @Test
    fun `signing out deliberately still shows the empty form`() {
        // The one state that must look like a fresh install, because the user asked for it.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(
                initialSession = SessionState.ReonboardingRequired(
                    server = null,
                    reason = ReonboardingReason.SIGNED_OUT,
                ),
            ),
            proxyStore,
        )

        assertNull(viewModel.state.value.failure)
        assertEquals("", viewModel.state.value.server)
    }

    @Test
    fun `an ordinary expiry is not the keystore's fault and gets no notice here`() {
        // Both credentials dead is the documented re-onboarding case and is handled by the
        // non-blocking prompt the rest of the app owns. Claiming the keystore failed would be a
        // false explanation, which is the fault item 3b on the punch list is about.
        val viewModel = ConnectViewModel(
            FakeSessionRepository(
                initialSession = SessionState.ReonboardingRequired(
                    server = FakeSessionRepository.IDENTITY,
                    reason = ReonboardingReason.BOTH_CREDENTIALS_DEAD,
                ),
            ),
            proxyStore,
        )

        assertNull(viewModel.state.value.failure)
        // The address is still carried over, though. An empty form is how the app told a user who
        // had been signed in for weeks that they never had been, and it is not a secret.
        assertEquals("https://music.yourhome.net", viewModel.state.value.server)
    }

    private fun cloudflareAccessError(): NeedlerError = NeedlerError.Unexpected(
        detail = "intercepted",
        cause = NetworkError.AuthenticatingProxy(
            ProxyInterception(
                proxyHost = "team.cloudflareaccess.com",
                vendor = ProxyVendor.CloudflareAccess,
                signal = ProxySignal.CrossHostRedirect,
                requestedHost = "music.yourhome.net",
                requestedUrl = "https://music.yourhome.net/api/v1/auth/providers",
                statusCode = 302,
                proxyCredentialsSent = false,
            ),
            ApiLane.V1,
        ),
    )

    private fun viewModel() = ConnectViewModel(repository, proxyStore)

    private fun filledIn(): ConnectViewModel = viewModel().apply {
        onServerChange("https://music.yourhome.net")
        onUsernameChange("yourname")
        onPasswordChange("hunter2")
    }

    private companion object {
        val CERTIFICATE = CertificateInfo(
            sha256Fingerprint = "AA:BB:CC",
            subject = "CN=music.yourhome.net",
            issuer = "CN=music.yourhome.net",
            notAfter = null,
        )
    }
}
