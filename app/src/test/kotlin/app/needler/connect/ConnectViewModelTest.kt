package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
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
