package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Turns a playback failure into the [NeedlerError] every surface sharing the session will see.
 *
 * `PlaybackController` commands return `Unit` and report on `PlaybackState.error`, so this mapping is
 * the whole of what a user is told when a track will not play. It is written against plain JVM types
 * so the table itself is testable; the Media3 exception is unwrapped into those types at the boundary.
 *
 * Timeouts and DNS failures map to [NeedlerError.Offline] rather than to a server error, because
 * REQUIREMENTS.md treats them as offline: the mirror and cached audio still work, and telling the user
 * the server is broken when their train went into a tunnel is wrong.
 */
public object PlaybackErrorMapper {

    /** A transport-level failure, with no HTTP response to read. */
    public fun fromTransport(failure: Throwable): NeedlerError = when (failure) {
        is UnknownHostException -> NeedlerError.Offline(OfflineCause.DNS_FAILURE)
        is SocketTimeoutException -> NeedlerError.Offline(OfflineCause.TIMEOUT)
        is SSLPeerUnverifiedException -> NeedlerError.Unexpected(
            "TLS pin rejected the audio connection",
            failure,
        )
        is SSLException -> NeedlerError.Unexpected("TLS failure on the audio connection", failure)
        is ConnectException, is NoRouteToHostException -> NeedlerError.Offline(OfflineCause.CONNECTION_FAILED)
        // A cached row naming a file that is not there any more. The bytes were evicted, or a crash
        // landed between the two halves of an eviction; either way the answer is to refetch, so it is
        // reported as retryable rather than as corruption.
        is FileNotFoundException -> NeedlerError.NotFound("cached audio file")
        is IOException -> NeedlerError.Offline(OfflineCause.CONNECTION_FAILED)
        else -> NeedlerError.Unexpected(failure.message, failure)
    }

    /** An HTTP response that was not usable. Shares its table with [StreamRetryPolicy]. */
    public fun fromHttp(failure: HttpFailure): NeedlerError =
        when (val recovery: StreamRecovery = FAIL_ONLY.recover(failure, attempt = Int.MAX_VALUE, transcodeRequested = false)) {
            is StreamRecovery.Fail -> recovery.error
            // recover() with an exhausted attempt count returns Fail for everything except the two
            // repair paths, and neither is reachable with attempt = MAX_VALUE. Guarded rather than
            // asserted so a future rule change degrades to an honest error instead of a crash.
            else -> NeedlerError.Rejected(failure.statusCode, "audio request refused")
        }

    private val FAIL_ONLY = StreamRetryPolicy(maxRateLimitRetries = 0)
}
