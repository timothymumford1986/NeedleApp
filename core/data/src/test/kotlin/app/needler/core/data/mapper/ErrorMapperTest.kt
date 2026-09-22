package app.needler.core.data.mapper

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import app.needler.core.network.ProxyInterception
import app.needler.core.network.ProxySignal
import app.needler.core.network.ProxyVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Transport failures folded onto the domain's failure cases, one row of the requirements' table at a
 * time.
 *
 * The distinctions here are the whole point of `NeedlerError`. Three of them mean opposite things
 * and the UI branches on all three, so each gets its own assertion.
 */
public class ErrorMapperTest {

    @Test
    public fun `a 401 on api v1 is a stale session, which must not touch playback`() {
        val error: NeedlerError = ErrorMapper.toNeedlerError(NetworkError.Unauthorised(ApiLane.V1))

        assertEquals(NeedlerError.SessionExpired, error)
        // Not retryable: the fix is a sign-in, not another attempt.
        assertFalse(error.isRetryable)
    }

    @Test
    public fun `a Subsonic auth rejection is a revoked app-password, repaired silently`() {
        val error: NeedlerError =
            ErrorMapper.toNeedlerError(NetworkError.Unauthorised(ApiLane.Subsonic, subsonicCode = 44))

        assertTrue(error is NeedlerError.AppPasswordRevoked)
        assertEquals(44, (error as NeedlerError.AppPasswordRevoked).subsonicErrorCode)
    }

    @Test
    public fun `the two authentication failures are never the same case`() {
        // Collapsing these into one "unauthorised" produces exactly the wrong behaviour in both
        // directions: a sign-in prompt for something the app can fix itself, or silence for
        // something only the user can.
        val bearer: NeedlerError = ErrorMapper.toNeedlerError(NetworkError.Unauthorised(ApiLane.V1))
        val appPassword: NeedlerError =
            ErrorMapper.toNeedlerError(NetworkError.Unauthorised(ApiLane.Subsonic, 40))

        assertFalse(bearer == appPassword)
    }

    @Test
    public fun `a disabled Subsonic protocol names the admin setting rather than a sign-in`() {
        assertEquals(
            NeedlerError.SubsonicProtocolDisabled,
            ErrorMapper.toNeedlerError(NetworkError.SubsonicProtocolDisabled),
        )
    }

    @Test
    public fun `a rate limit carries its Retry-After and is retryable`() {
        val error: NeedlerError = ErrorMapper.toNeedlerError(
            NetworkError.RateLimited(retryAfterSeconds = 30L, lane = ApiLane.Subsonic),
        )

        assertTrue(error is NeedlerError.RateLimited)
        assertEquals(30.seconds, (error as NeedlerError.RateLimited).retryAfter)
        assertTrue(error.isRetryable)
    }

    @Test
    public fun `a zero Retry-After is clamped to a second, or it is a hot retry loop`() {
        val error = ErrorMapper.toNeedlerError(
            NetworkError.RateLimited(retryAfterSeconds = 0L, lane = ApiLane.V1),
        ) as NeedlerError.RateLimited

        assertEquals(1.seconds, error.retryAfter)
    }

    @Test
    public fun `a transcode rate limit is marked as such, so the caller falls back to the original`() {
        val error = ErrorMapper.toNeedlerError(
            NetworkError.RateLimited(retryAfterSeconds = 5L, lane = ApiLane.Subsonic),
            transcodeRequest = true,
        ) as NeedlerError.RateLimited

        assertTrue(error.wasTranscodeRequest)
    }

    @Test
    public fun `a 403 on a download hides the affordance, elsewhere it is a permission failure`() {
        val onDownload: NeedlerError = ErrorMapper.toNeedlerError(
            NetworkError.Forbidden(ApiLane.Subsonic),
            downloadContext = true,
        )
        val elsewhere: NeedlerError =
            ErrorMapper.toNeedlerError(NetworkError.Forbidden(ApiLane.V1))

        assertEquals(NeedlerError.DownloadForbidden, onDownload)
        assertTrue(elsewhere is NeedlerError.PermissionDenied)
    }

    @Test
    public fun `a 416 says the cached length is wrong and is worth one more attempt`() {
        val error: NeedlerError =
            ErrorMapper.toNeedlerError(NetworkError.RangeNotSatisfiable(completeLength = 42L))

        assertEquals(NeedlerError.RangeNotSatisfiable, error)
        assertTrue(error.isRetryable)
    }

    @Test
    public fun `every flavour of unreachable becomes offline, which is a state and not an error`() {
        val timeout = ErrorMapper.toNeedlerError(
            NetworkError.Offline(IOException("timeout"), NetworkError.Offline.Kind.Timeout),
        ) as NeedlerError.Offline
        val dns = ErrorMapper.toNeedlerError(
            NetworkError.Offline(IOException("dns"), NetworkError.Offline.Kind.Dns),
        ) as NeedlerError.Offline

        assertEquals(OfflineCause.TIMEOUT, timeout.cause)
        assertEquals(OfflineCause.DNS_FAILURE, dns.cause)
        assertTrue(timeout.isRetryable)
    }

    @Test
    public fun `a distrusted certificate reads as unreachable away from the Connect screen`() {
        // The transport only saw a handshake fail and cannot supply a certificate to show. Only the
        // Connect screen has a reason to show one, and it reads the certificate itself.
        val error: NeedlerError = ErrorMapper.toNeedlerError(
            NetworkError.TlsNotTrusted("music.example.net", IOException("bad cert")),
        )

        assertTrue(error is NeedlerError.Offline)
    }

    @Test
    public fun `a 5xx is retryable and keeps its status for the backoff log`() {
        val error = ErrorMapper.toNeedlerError(
            NetworkError.Server(statusCode = 503, lane = ApiLane.V1, serverMessage = "busy"),
        ) as NeedlerError.ServerError

        assertEquals(503, error.statusCode)
        assertTrue(error.isRetryable)
    }

    @Test
    public fun `a 400-class rejection is permanent, so the write queue drops it`() {
        val error: NeedlerError = ErrorMapper.toNeedlerError(
            NetworkError.InvalidRequest(ApiLane.V1, statusCode = 422, serverMessage = "too many items"),
        )

        assertTrue(error is NeedlerError.Rejected)
        assertFalse(error.isRetryable)
    }

    @Test
    public fun `an unmapped Subsonic code is a protocol violation, not a silent success`() {
        val error: NeedlerError =
            ErrorMapper.toNeedlerError(NetworkError.SubsonicFailure(code = 30, serverMessage = "odd"))

        assertTrue(error is NeedlerError.ProtocolViolation)
    }

    @Test
    public fun `Subsonic code 70 is a not-found and code 50 a permission failure`() {
        assertTrue(
            ErrorMapper.toNeedlerError(NetworkError.SubsonicFailure(70, "gone")) is NeedlerError.NotFound,
        )
        assertTrue(
            ErrorMapper.toNeedlerError(
                NetworkError.SubsonicFailure(50, "nope"),
            ) is NeedlerError.PermissionDenied,
        )
    }

    @Test
    public fun `an unparseable body is a protocol violation rather than an unexpected crash`() {
        val error: NeedlerError = ErrorMapper.toNeedlerError(
            NetworkError.Serialisation(ApiLane.Subsonic, IllegalStateException("bad json")),
        )

        assertTrue(error is NeedlerError.ProtocolViolation)
    }

    @Test
    public fun `an authenticating proxy keeps its host and vendor all the way to the UI`() {
        val transport = NetworkError.AuthenticatingProxy(
            ProxyInterception(
                proxyHost = "team.cloudflareaccess.com",
                vendor = ProxyVendor.CloudflareAccess,
                signal = ProxySignal.CrossHostRedirect,
                requestedHost = "music.example.net",
                requestedUrl = "https://music.example.net/api/v1/auth/providers",
                statusCode = 302,
                proxyCredentialsSent = true,
            ),
            ApiLane.V1,
        )

        val error: NeedlerError = ErrorMapper.toNeedlerError(transport)

        // `:core:domain` has no case for this, so the structured error travels as the cause. The
        // Connect screen reads it back out; nothing else has to care.
        assertTrue(error is NeedlerError.Unexpected)
        assertEquals(transport, (error as NeedlerError.Unexpected).cause)
        assertTrue(error.diagnostic.contains("team.cloudflareaccess.com"))
        assertTrue(error.diagnostic.contains("Cloudflare Access"))
        // Not retryable: the proxy will refuse the next request identically.
        assertFalse(error.isRetryable)
        assertFalse(transport.isTransient)
    }

    @Test
    public fun `anything unclassified is wrapped rather than escaping as a throwable`() {
        val error: NeedlerError = ErrorMapper.toNeedlerError(IllegalArgumentException("boom"))

        assertTrue(error is NeedlerError.Unexpected)
    }
}
