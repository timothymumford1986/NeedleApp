package app.needler.core.network

import app.needler.core.network.internal.HttpEngine
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two headers the failure table depends on: `Retry-After` on a `429`, which must be honoured,
 * and `Content-Range` on a `416`, which tells the downloader the file's real length.
 */
class ResponseHeaderTest {

    private fun response(code: Int, vararg headers: Pair<String, String>): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://music.example.net/subsonic/rest/ping").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body("".toResponseBody(null))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    @Test
    fun `retry-after is read in seconds`() {
        assertEquals(30L, HttpEngine.retryAfterSeconds(response(429, "Retry-After" to "30")))
    }

    @Test
    fun `a zero retry-after is clamped to one second`() {
        // The /api/v1 limiter truncates its float to an int and can legitimately emit 0.
        assertEquals(1L, HttpEngine.retryAfterSeconds(response(429, "Retry-After" to "0")))
    }

    @Test
    fun `a missing or non-numeric retry-after is null`() {
        assertNull(HttpEngine.retryAfterSeconds(response(429)))
        // HTTP-date form is legal in the spec but this server never sends it; treat it as absent
        // rather than guessing a delay.
        assertNull(HttpEngine.retryAfterSeconds(response(429, "Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")))
    }

    @Test
    fun `content-range on a 416 yields the complete length`() {
        assertEquals(
            12_345L,
            HttpEngine.completeLengthOf(response(416, "Content-Range" to "bytes */12345")),
        )
        assertEquals(
            999L,
            HttpEngine.completeLengthOf(response(206, "Content-Range" to "bytes 100-998/999")),
        )
    }

    @Test
    fun `an unparseable content-range is null`() {
        assertNull(HttpEngine.completeLengthOf(response(416)))
        assertNull(HttpEngine.completeLengthOf(response(416, "Content-Range" to "bytes */*")))
    }
}
