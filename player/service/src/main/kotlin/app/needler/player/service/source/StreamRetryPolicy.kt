package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import kotlin.time.Duration

/**
 * What to do about one failed attempt at an audio request.
 *
 * Every branch is reachable from the failure table in REQUIREMENTS.md "Failure handling"; the
 * interesting ones are [WaitAndRetry] and [FallBackToOriginal], which together are the `429` rule.
 */
public sealed interface StreamRecovery {

    /** Sleep for [delay] and issue the same request again. Used once, then escalated. */
    public data class WaitAndRetry(val delay: Duration) : StreamRecovery

    /**
     * Drop the transcode and fetch the original bytes for this track.
     *
     * REQUIREMENTS.md: on `429` from a transcode, fall back to the original stream for that track and
     * show a one-line notice rather than failing. The server allows one transcode per user and two in
     * total, so this is an ordinary Saturday evening in a two-listener household, not an incident.
     *
     * The fall-back stream is also the one that may be retained: original bytes are the only bytes
     * the store ever keeps, so the attempt that replaces a refused transcode is write-through even
     * though the transcode it replaced was not.
     */
    public data object FallBackToOriginal : StreamRecovery

    /**
     * Re-issue the same request without the length this client thought the file had.
     *
     * `416` means that length is wrong, so the range built from it names bytes that do not exist. The read
     * offset is still correct and must not move - serving the extractor bytes from a different offset than
     * it asked for is how a track plays as noise - so what is discarded is the assumed end of the range,
     * not the start. When the request already had no assumed length, a `416` is unrecoverable.
     */
    public data object DiscardAssumedLength : StreamRecovery

    /** Give up on this attempt, reporting [error]. Media3's load-error policy decides what happens next. */
    public data class Fail(val error: NeedlerError) : StreamRecovery
}

/**
 * The recovery rules for audio requests, as one testable table.
 *
 * Stateless: [recover] is told which attempt this is and whether the request asked for a transcode,
 * and answers. The data source that calls it owns the counting, because a policy that remembered
 * attempts would have to be per-request and there is one of these per player.
 */
public class StreamRetryPolicy(
    /** How many `429` waits are honoured before the transcode is abandoned. One, per REQUIREMENTS.md. */
    private val maxRateLimitRetries: Int = 1,
) {

    /**
     * @param failure the response, with `Retry-After` already parsed and clamped.
     * @param attempt 1 for the first attempt at this request.
     * @param transcodeRequested whether this request asked the server to transcode. A `429` on a
     *   transcode has somewhere to fall back to; a `429` on the original stream does not, and means the
     *   server's direct-stream slots are gone.
     */
    public fun recover(
        failure: HttpFailure,
        attempt: Int,
        transcodeRequested: Boolean,
    ): StreamRecovery {
        // A JSON or XML body where audio should be is a status=failed envelope over HTTP 200. Nothing
        // to retry: the request was refused, and retrying is how a revoked app-password turns into a
        // loop instead of a prompt.
        if (failure.bodyWasEnvelope) {
            return StreamRecovery.Fail(
                NeedlerError.ProtocolViolation("envelope body on a binary endpoint, status " + failure.statusCode),
            )
        }

        return when (failure.statusCode) {
            416 -> if (attempt <= 1) {
                StreamRecovery.DiscardAssumedLength
            } else {
                StreamRecovery.Fail(NeedlerError.RangeNotSatisfiable)
            }

            429 -> when {
                attempt <= maxRateLimitRetries && failure.retryAfter != null ->
                    StreamRecovery.WaitAndRetry(failure.retryAfter)
                // No header, or the wait has already been spent: the transcode is what has to go.
                attempt <= maxRateLimitRetries ->
                    StreamRecovery.WaitAndRetry(RetryAfterHeader.MINIMUM)
                transcodeRequested -> StreamRecovery.FallBackToOriginal
                // Nothing to fall back to. The slots are genuinely gone.
                else -> StreamRecovery.Fail(NeedlerError.StreamSlotsExhausted)
            }

            401 -> StreamRecovery.Fail(NeedlerError.SessionExpired)
            403 -> StreamRecovery.Fail(NeedlerError.DownloadForbidden)
            404 -> StreamRecovery.Fail(NeedlerError.NotFound("audio for this track"))
            in 500..599 -> StreamRecovery.Fail(
                NeedlerError.ServerError(failure.statusCode, "audio request failed"),
            )

            else -> StreamRecovery.Fail(
                NeedlerError.Rejected(failure.statusCode, "audio request refused"),
            )
        }
    }
}
