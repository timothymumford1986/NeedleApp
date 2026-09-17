package app.needler.core.network

import app.needler.core.network.internal.NeedlerJson
import app.needler.core.network.subsonic.SubsonicEnvelopeParser
import app.needler.core.network.subsonic.SubsonicErrorCode
import app.needler.core.network.subsonic.dto.GenresDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Subsonic envelope is parsed generically and its `error.code` mapped to typed errors. The
 * cases that matter most are the ones the UI reacts to differently: a dead app-password (40/44)
 * versus a protocol an administrator has switched off (code 0 with the disabled message) versus
 * the shim's own rate limiter (code 0 with a `Retry-After`).
 */
class SubsonicEnvelopeTest {

    private val credentials = TestCredentials()

    private fun parse(body: String) = SubsonicEnvelopeParser.parse(NeedlerJson, body)

    @Test
    fun `an ok envelope exposes its metadata and payload`() {
        val envelope = parse(
            """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"DroppedNeedle",
             "serverVersion":"10.10.6","openSubsonic":true,
             "genres":{"genre":[{"value":"Jazz","songCount":12,"albumCount":3}]}}}
            """.trimIndent(),
        )
        assertFalse(envelope.isFailed)
        assertEquals("ok", envelope.status)
        assertEquals("1.16.1", envelope.version)
        assertEquals("DroppedNeedle", envelope.type)
        assertEquals("10.10.6", envelope.serverVersion)
        assertTrue(envelope.openSubsonic)

        val payload = envelope.payload("genres")
        val genres = NeedlerJson.decodeFromJsonElement(GenresDto.serializer(), requireNotNull(payload))
        assertEquals(1, genres.genre.size)
        assertEquals("Jazz", genres.genre.first().value)
        assertEquals(12, genres.genre.first().songCount)
    }

    @Test
    fun `an envelope with no payload for the key returns null`() {
        val envelope = parse("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        assertEquals(null, envelope.payload("genres"))
    }

    @Test
    fun `unknown keys and new fields are tolerated`() {
        val envelope = parse(
            """
            {"subsonic-response":{"status":"ok","version":"1.16.1","somethingNew":42,
             "genres":{"genre":[{"value":"Jazz","futureField":"x"}],"futureList":[]}}}
            """.trimIndent(),
        )
        val genres = NeedlerJson.decodeFromJsonElement(
            GenresDto.serializer(),
            requireNotNull(envelope.payload("genres")),
        )
        assertEquals("Jazz", genres.genre.single().value)
    }

    @Test
    fun `a body that is not an envelope is a serialisation failure`() {
        val failure = runCatching { parse("<html>captive portal</html>") }.exceptionOrNull()
        assertTrue("got $failure", failure is NetworkError.Serialisation)

        val missingRoot = runCatching { parse("""{"something":{"status":"ok"}}""") }.exceptionOrNull()
        assertTrue("got $missingRoot", missingRoot is NetworkError.Serialisation)
    }

    // -------------------------------------------------------------- error codes

    private fun mapError(body: String, retryAfterSeconds: Long? = null): NetworkError {
        val envelope = parse(body)
        assertTrue(envelope.isFailed)
        return SubsonicEnvelopeParser.toNetworkError(envelope.error, retryAfterSeconds, credentials)
    }

    private fun failed(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1",
           "error":{"code":$code,"message":"$message"}}}"""

    @Test
    fun `code 44 means the app-password is dead and needs re-onboarding`() {
        val error = mapError(failed(SubsonicErrorCode.INVALID_APIKEY, "Invalid API key."))
        assertTrue("got $error", error is NetworkError.Unauthorised)
        val unauthorised = error as NetworkError.Unauthorised
        assertEquals(ApiLane.Subsonic, unauthorised.lane)
        assertEquals(44, unauthorised.subsonicCode)
        assertTrue(unauthorised.requiresReonboarding)
        assertEquals(1, credentials.appPasswordRejections)
        assertEquals(0, credentials.bearerRejections)
    }

    @Test
    fun `code 40 also means the app-password is dead`() {
        val error = mapError(failed(SubsonicErrorCode.WRONG_CREDENTIALS, "Wrong username or password."))
        assertTrue((error as NetworkError.Unauthorised).requiresReonboarding)
    }

    @Test
    fun `code 0 with the disabled message is its own actionable outcome`() {
        val error = mapError(failed(SubsonicErrorCode.GENERIC, "The Subsonic API is disabled on this server."))
        assertEquals(NetworkError.SubsonicProtocolDisabled, error)
        // Nothing about the user's credentials is wrong, so nothing is invalidated.
        assertEquals(0, credentials.appPasswordRejections)
    }

    @Test
    fun `code 0 with a retry-after is the shim's rate limiter, not a disabled protocol`() {
        val error = mapError(failed(SubsonicErrorCode.GENERIC, "Rate limit exceeded"), retryAfterSeconds = 7)
        assertTrue("got $error", error is NetworkError.RateLimited)
        val limited = error as NetworkError.RateLimited
        assertEquals(7L, limited.retryAfterSeconds)
        assertEquals(ApiLane.Subsonic, limited.lane)
    }

    @Test
    fun `code 0 with anything else stays a generic subsonic failure`() {
        val error = mapError(failed(SubsonicErrorCode.GENERIC, "Unknown method getwidgets"))
        assertTrue(error is NetworkError.SubsonicFailure)
        assertEquals(0, (error as NetworkError.SubsonicFailure).code)
    }

    @Test
    fun `code 70 is not found and code 50 is forbidden`() {
        assertTrue(mapError(failed(SubsonicErrorCode.NOT_FOUND, "Album not found")) is NetworkError.NotFound)
        assertTrue(
            mapError(failed(SubsonicErrorCode.NOT_AUTHORIZED, "User is not authorized")) is NetworkError.Forbidden,
        )
    }

    @Test
    fun `parameter errors are client bugs, not user-facing failures`() {
        assertTrue(
            mapError(failed(SubsonicErrorCode.PARAM_MISSING, "Required parameter is missing."))
                is NetworkError.InvalidRequest,
        )
        assertTrue(
            mapError(failed(SubsonicErrorCode.CONFLICTING_AUTH, "Multiple conflicting authentication mechanisms."))
                is NetworkError.InvalidRequest,
        )
    }

    @Test
    fun `an unmapped code keeps its number for the log`() {
        val error = mapError(failed(99, "Something new"))
        assertEquals(99, (error as NetworkError.SubsonicFailure).code)
    }
}
