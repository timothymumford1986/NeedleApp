package app.needler.core.data.repository

import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.security.CredentialStoreState
import app.needler.core.data.security.SecureCredentialStore
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ServerProbe
import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import app.needler.core.network.ProxyInterception
import app.needler.core.network.ProxySignal
import app.needler.core.network.ProxyVendor
import app.needler.core.network.ServerUrl
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.tls.CertificateDetails
import app.needler.core.network.tls.TlsCertificateProbe
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.AuthProvidersDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `probeServer` dials, in what order, and which rung's failure the user is told about.
 *
 * REQUIREMENTS.md "Accepted URL forms" lets the user type a bare host and makes the scheme a guess
 * rather than an instruction, so one typed address is several dialled ones. Three properties of
 * that walk are not visible in [ServerUrl.ladder]'s own tests and have to be checked against the
 * repository that walks it:
 *
 *  * the rungs are dialled in the ladder's order, and the first that answers is the one returned -
 *    so typing `music.yourhome.net` works at all;
 *  * an untrusted certificate **ends** the walk with a fingerprint to look at, rather than falling
 *    through to the cleartext rungs behind it;
 *  * a finished walk reports its most informative rung, not whichever came last.
 *
 * [SecureCredentialStore] is mocked because its real constructor is private and goes through
 * `EncryptedSharedPreferences`. Only the saved-address part of it matters here, and the fake keeps
 * it in a field, which is also what makes the dialled address readable: the V1 client reads the
 * server from the store on every call, which is the mechanism the walk works by.
 */
class SessionProbeLadderTest {

    /** The address [DefaultSessionRepository] has most recently saved, i.e. the rung being dialled. */
    private var saved: ServerUrl? = null

    /** Every address dialled, in order. */
    private val dialled: MutableList<String> = mutableListOf()

    /** What `GET /api/v1/auth/providers` does per base URL. Absent means connection refused. */
    private var answers: Map<String, Throwable?> = emptyMap()

    private var pinned: String? = null

    private var presentedCertificate: CertificateDetails? = SELF_SIGNED

    private val credentials: SecureCredentialStore = mockk(relaxed = true) {
        every { state } returns CredentialStoreState.Opened
        every { serverUrl() } answers { saved }
        every { saveServerUrl(any()) } answers {
            saved = firstArg()
            true
        }
        every { pinnedCertificateSha256() } answers { pinned }
    }

    private val v1: V1Api = mockk(relaxed = true)

    private val certificates: TlsCertificateProbe = mockk {
        coEvery { probe(any<ServerUrl>()) } answers { presentedCertificate }
    }

    private val monitor: NetworkMonitor = object : NetworkMonitor {
        override fun observe(): Flow<ConnectivityState> = flowOf(ConnectivityState.Unmetered)
        override fun current(): ConnectivityState = ConnectivityState.Unmetered
    }

    private fun repository(): DefaultSessionRepository {
        coEvery { v1.authProviders() } answers {
            val url: String = requireNotNull(saved).baseUrl
            dialled += url
            // Not `getOrElse`: a key mapped to null is the rung that *answers*, and `getOrElse`
            // cannot tell that from a key that is absent.
            val answer: Throwable? = if (answers.containsKey(url)) answers[url] else REFUSED
            when (answer) {
                null -> AuthProvidersDto(local = true)
                else -> throw answer
            }
        }
        return DefaultSessionRepository(
            credentials = credentials,
            v1 = v1,
            capabilityProbe = mockk<CapabilityProbe>(relaxed = true),
            networkMonitor = monitor,
            certificates = certificates,
        )
    }

    // ------------------------------------------------------------------ the walk

    @Test
    fun `a bare domain name is tried over tls first, and the answering rung is what comes back`() =
        runTest {
            answers = mapOf("http://music.yourhome.net:8688" to null)

            val outcome: Outcome<ServerProbe> = repository().probeServer("music.yourhome.net")

            assertEquals(
                listOf("https://music.yourhome.net", "http://music.yourhome.net:8688"),
                dialled,
            )
            assertEquals(
                "http://music.yourhome.net:8688",
                (outcome as Outcome.Success).value.identity.baseUrl,
            )
        }

    /**
     * A box on this network is likeliest on DroppedNeedle's own port and least likely to have TLS,
     * so a LAN address answers on the first rung instead of waiting out a handshake that was never
     * going to work.
     */
    @Test
    fun `a lan address is tried on DroppedNeedle's own port first`() = runTest {
        answers = mapOf("http://192.168.1.50:8688" to null)

        repository().probeServer("192.168.1.50")

        assertEquals(listOf("http://192.168.1.50:8688"), dialled)
    }

    @Test
    fun `a typed scheme is obeyed exactly and nothing else is dialled`() = runTest {
        val outcome: Outcome<ServerProbe> = repository().probeServer("https://music.yourhome.net")

        assertEquals(listOf("https://music.yourhome.net"), dialled)
        assertTrue(outcome.toString(), outcome is Outcome.Failure)
    }

    // --------------------------------------------------------- certificates stop it

    /**
     * The rule the ladder must never break. TLS *worked* - a certificate was presented and the
     * user has a decision to make - so the two cleartext rungs behind it are not tried, and the
     * failure carries the fingerprint rather than a flattened "unreachable".
     */
    @Test
    fun `an untrusted certificate ends the walk instead of falling through to cleartext`() =
        runTest {
            answers = mapOf(
                "https://music.yourhome.net" to NetworkError.TlsNotTrusted(
                    host = "music.yourhome.net",
                    cause = IOException("self-signed"),
                ),
                // Listening, and never reached.
                "http://music.yourhome.net:8688" to null,
            )

            val outcome: Outcome<ServerProbe> = repository().probeServer("music.yourhome.net")

            assertEquals(listOf("https://music.yourhome.net"), dialled)
            val error = (outcome as Outcome.Failure).error
            assertTrue(error.toString(), error is NeedlerError.CertificateUntrusted)
            assertEquals(
                SELF_SIGNED.sha256Hex,
                (error as NeedlerError.CertificateUntrusted).certificate.sha256Fingerprint,
            )
        }

    /**
     * A pin that exists and does not match is the loud case: it can mean a reissue, or it can mean
     * something is in the middle, and the user has to re-confirm either way.
     */
    @Test
    fun `a changed certificate is reported as changed, not as untrusted`() = runTest {
        pinned = "11:22:33"
        answers = mapOf(
            "https://music.yourhome.net" to NetworkError.TlsNotTrusted(
                host = "music.yourhome.net",
                cause = IOException("pin mismatch"),
            ),
        )

        val outcome: Outcome<ServerProbe> = repository().probeServer("https://music.yourhome.net")

        val error = (outcome as Outcome.Failure).error
        assertTrue(error.toString(), error is NeedlerError.CertificateChanged)
        assertEquals("11:22:33", (error as NeedlerError.CertificateChanged).expectedFingerprint)
    }

    /**
     * No certificate to show means no prompt to show. An empty fingerprint panel asks the user to
     * compare something that is not there, so this falls back to the answer the rest of the app
     * would give for a host that will not complete a handshake.
     */
    @Test
    fun `a certificate that cannot be read falls back to unreachable`() = runTest {
        presentedCertificate = null
        answers = mapOf(
            "https://music.yourhome.net" to NetworkError.TlsNotTrusted(
                host = "music.yourhome.net",
                cause = IOException("gone"),
            ),
        )

        val outcome: Outcome<ServerProbe> = repository().probeServer("https://music.yourhome.net")

        assertEquals(
            NeedlerError.Offline(OfflineCause.CONNECTION_FAILED),
            (outcome as Outcome.Failure).error,
        )
    }

    // ------------------------------------------------- which failure gets reported

    /**
     * The regression the ranking exists for. A forward-auth proxy intercepts the `https` rung,
     * which is the first one a domain name tries; the two cleartext rungs behind it are then
     * refused by ports with nothing on them. Reporting the last rung would replace the proxy
     * notice - which has a whole state and a whole form behind it on the Connect screen - with
     * "no connection", and that is the dead screen the proxy work was done to remove.
     */
    @Test
    fun `a proxy interception on the first rung survives the rungs after it`() = runTest {
        answers = mapOf("https://music.yourhome.net" to INTERCEPTED)

        val outcome: Outcome<ServerProbe> = repository().probeServer("music.yourhome.net")

        assertEquals(3, dialled.size)
        val error = (outcome as Outcome.Failure).error
        assertTrue(error.toString(), error is NeedlerError.Unexpected)
        assertTrue(
            error.toString(),
            (error as NeedlerError.Unexpected).cause is NetworkError.AuthenticatingProxy,
        )
    }

    /** Something that answered beats something that did not, wherever in the walk it happened. */
    @Test
    fun `a rung that answered outranks the rungs that were merely refused`() = runTest {
        answers = mapOf(
            "http://music.yourhome.net:8688" to NetworkError.NotFound(ApiLane.V1),
        )

        val outcome: Outcome<ServerProbe> = repository().probeServer("music.yourhome.net")

        val error = (outcome as Outcome.Failure).error
        assertTrue(error.toString(), error is NeedlerError.NotADroppedNeedleServer)
    }

    @Test
    fun `nothing anywhere is reported as unreachable, and the typed address stays saved`() =
        runTest {
            val outcome: Outcome<ServerProbe> = repository().probeServer("music.yourhome.net")

            assertEquals(
                listOf(
                    "https://music.yourhome.net",
                    "http://music.yourhome.net:8688",
                    "http://music.yourhome.net",
                ),
                dialled,
            )
            assertEquals(
                NeedlerError.Offline(OfflineCause.CONNECTION_FAILED),
                (outcome as Outcome.Failure).error,
            )
            // What the user typed, not whichever rung happened to be dialled last, so Settings and
            // a retry both show the address they entered.
            assertEquals("http://music.yourhome.net:8688", saved?.baseUrl)
        }

    private companion object {
        val REFUSED: NetworkError = NetworkError.Offline(
            cause = IOException("connection refused"),
            kind = NetworkError.Offline.Kind.Io,
        )

        val INTERCEPTED: NetworkError = NetworkError.AuthenticatingProxy(
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
        )

        val SELF_SIGNED = CertificateDetails(
            host = "music.yourhome.net",
            sha256Hex = "AA:BB:CC:DD",
            sha256Base64 = "qrvM3Q==",
            subject = "CN=music.yourhome.net",
            issuer = "CN=music.yourhome.net",
            notBefore = Date(0),
            notAfter = Date(4_000_000_000_000),
            isSelfSigned = true,
        )
    }
}
