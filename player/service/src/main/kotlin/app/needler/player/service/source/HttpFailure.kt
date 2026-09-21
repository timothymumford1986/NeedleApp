package app.needler.player.service.source

import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One HTTP failure on an audio request, reduced to the three things the recovery rules care about.
 *
 * Deliberately not a Media3 or OkHttp type. The rules in [StreamRetryPolicy] and
 * [PlaybackErrorMapper] are the part most likely to be wrong and the part most worth testing, so they
 * are written against a plain value and the Media3 exception is unwrapped into it at the boundary.
 */
public data class HttpFailure(
    public val statusCode: Int,
    /** `Retry-After`, already parsed and clamped. Null when the server did not send one. */
    public val retryAfter: Duration? = null,
    /**
     * True when the body arrived as JSON or XML where audio was expected.
     *
     * The Subsonic shim answers **HTTP 200 with a `status=failed` envelope** on the binary endpoints,
     * so a JSON body is the only signal that anything went wrong. Without sniffing it, the failure
     * surfaces as a decode error - or a few kilobytes of text get written into the audio store as
     * though they were music.
     */
    public val bodyWasEnvelope: Boolean = false,
)

/**
 * `Retry-After` parsing, with the clamp REQUIREMENTS.md requires.
 *
 * The header comes in two forms and the limiter can emit either. Both are handled, and `0` is clamped
 * to one second: an unclamped zero is a hot retry loop against a server that has just said it is
 * overloaded.
 */
public object RetryAfterHeader {

    /** The floor an honoured `Retry-After` is clamped up to. */
    public val MINIMUM: Duration = 1.seconds

    /**
     * How long a single playback attempt will sit and wait.
     *
     * A stream cannot block the loading thread for a minute because the server suggested it; past this
     * the request is failed and Media3's own load-error policy takes over, which is the layer that is
     * allowed to back off slowly.
     */
    public val MAXIMUM_HONOURED: Duration = 5.seconds

    /**
     * Parses a `Retry-After` value, clamped into `[MINIMUM, MAXIMUM_HONOURED]`.
     *
     * Accepts delta-seconds and an HTTP-date. Returns null for an absent or unparseable value, and for
     * a date already in the past, because "retry after a moment that has gone" is not a wait.
     */
    public fun parse(raw: String?, nowEpochMillis: Long = System.currentTimeMillis()): Duration? {
        val value: String = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        value.toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return clamp(seconds.seconds)
        }

        val atMillis: Long = try {
            java.time.ZonedDateTime
                .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (parseFailure: DateTimeParseException) {
            return null
        }
        val deltaMillis: Long = atMillis - nowEpochMillis
        if (deltaMillis <= 0L) return null
        return clamp(deltaMillis.milliseconds)
    }

    private fun clamp(value: Duration): Duration = when {
        value < MINIMUM -> MINIMUM
        value > MAXIMUM_HONOURED -> MAXIMUM_HONOURED
        else -> value
    }
}
