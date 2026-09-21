package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The failure table from REQUIREMENTS.md "Failure handling", as it applies to an audio request.
 *
 * The `429` rule is the one worth the most care: honour `Retry-After`, retry once, then fall back to the
 * original stream rather than failing - and a `Retry-After: 0` must be clamped, because an unclamped zero is a
 * hot retry loop against a server that has just said it is overloaded.
 */
class StreamRetryPolicyTest {

    private val policy = StreamRetryPolicy()

    @Test
    fun `429 with Retry-After waits once`() {
        val recovery = policy.recover(
            failure = HttpFailure(429, retryAfter = 2.seconds),
            attempt = 1,
            transcodeRequested = true,
        )

        assertEquals(StreamRecovery.WaitAndRetry(2.seconds), recovery)
    }

    @Test
    fun `429 on a transcode falls back to the original stream after its one retry`() {
        val recovery = policy.recover(
            failure = HttpFailure(429, retryAfter = 2.seconds),
            attempt = 2,
            transcodeRequested = true,
        )

        assertEquals(StreamRecovery.FallBackToOriginal, recovery)
    }

    /** There is nothing to fall back to from the original stream: the server's direct slots are gone. */
    @Test
    fun `429 on the original stream reports the slots exhausted`() {
        val recovery = policy.recover(
            failure = HttpFailure(429, retryAfter = 1.seconds),
            attempt = 2,
            transcodeRequested = false,
        )

        assertEquals(StreamRecovery.Fail(NeedlerError.StreamSlotsExhausted), recovery)
    }

    @Test
    fun `429 with no Retry-After still waits the minimum before escalating`() {
        val recovery = policy.recover(HttpFailure(429), attempt = 1, transcodeRequested = false)

        assertEquals(StreamRecovery.WaitAndRetry(RetryAfterHeader.MINIMUM), recovery)
    }

    @Test
    fun `416 discards the assumed length once, then gives up`() {
        assertEquals(
            StreamRecovery.DiscardAssumedLength,
            policy.recover(HttpFailure(416), attempt = 1, transcodeRequested = false),
        )
        assertEquals(
            StreamRecovery.Fail(NeedlerError.RangeNotSatisfiable),
            policy.recover(HttpFailure(416), attempt = 2, transcodeRequested = false),
        )
    }

    /**
     * The shim answers HTTP 200 with a `status=failed` envelope on the binary endpoints, so a JSON body where
     * audio should be is the only signal anything went wrong. Retrying it turns a revoked app-password into a
     * loop instead of a prompt.
     */
    @Test
    fun `an envelope body is never retried`() {
        val recovery = policy.recover(
            failure = HttpFailure(500, bodyWasEnvelope = true),
            attempt = 1,
            transcodeRequested = true,
        )

        assertTrue((recovery as StreamRecovery.Fail).error is NeedlerError.ProtocolViolation)
    }

    @Test
    fun `the rest of the table maps as documented`() {
        assertEquals(
            NeedlerError.SessionExpired,
            failureFor(HttpFailure(401)),
        )
        assertEquals(
            NeedlerError.DownloadForbidden,
            failureFor(HttpFailure(403)),
        )
        assertTrue(failureFor(HttpFailure(404)) is NeedlerError.NotFound)
        assertEquals(
            NeedlerError.ServerError(503, "audio request failed"),
            failureFor(HttpFailure(503)),
        )
        assertTrue(failureFor(HttpFailure(418)) is NeedlerError.Rejected)
    }

    private fun failureFor(failure: HttpFailure): NeedlerError =
        (policy.recover(failure, attempt = 9, transcodeRequested = false) as StreamRecovery.Fail).error

    // ------------------------------------------------------------- Retry-After parsing

    @Test
    fun `Retry-After zero is clamped to one second`() {
        assertEquals(RetryAfterHeader.MINIMUM, RetryAfterHeader.parse("0"))
    }

    @Test
    fun `a long Retry-After is capped so one load does not block for a minute`() {
        assertEquals(RetryAfterHeader.MAXIMUM_HONOURED, RetryAfterHeader.parse("600"))
    }

    @Test
    fun `an HTTP-date Retry-After is honoured as a delay`() {
        // Sun, 06 Nov 1994 08:49:37 GMT is the RFC's own example.
        val at = java.time.ZonedDateTime
            .parse("Sun, 06 Nov 1994 08:49:37 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()

        val parsed = RetryAfterHeader.parse("Sun, 06 Nov 1994 08:49:37 GMT", nowEpochMillis = at - 3_000L)

        assertEquals(3.seconds, parsed)
    }

    @Test
    fun `an absent, unparseable or past Retry-After is no wait at all`() {
        assertNull(RetryAfterHeader.parse(null))
        assertNull(RetryAfterHeader.parse("   "))
        assertNull(RetryAfterHeader.parse("soon"))
        assertNull(RetryAfterHeader.parse("-5"))
        assertNull(
            RetryAfterHeader.parse(
                "Sun, 06 Nov 1994 08:49:37 GMT",
                nowEpochMillis = System.currentTimeMillis(),
            ),
        )
    }
}
