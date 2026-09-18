package app.needler.connect

import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        viewModel.onUsernameChange("tim")
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
                "connect(https://music.yourhome.net, tim)",
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

    private fun viewModel() = ConnectViewModel(repository)

    private fun filledIn(): ConnectViewModel = viewModel().apply {
        onServerChange("https://music.yourhome.net")
        onUsernameChange("tim")
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
