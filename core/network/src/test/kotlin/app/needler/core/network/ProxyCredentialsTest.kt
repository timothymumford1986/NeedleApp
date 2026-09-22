package app.needler.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header list itself: what may be sent, what may not, and how it survives a round trip through
 * the encrypted store.
 *
 * The rejections are the interesting half. A header that clobbers `Authorization` produces a `401`
 * that is indistinguishable from a wrong password, and a `\r\n` in a value is request splitting -
 * both have to fail on the Connect screen, in words, rather than somewhere inside an interceptor.
 */
class ProxyCredentialsTest {

    @Test
    fun `an ordinary header pair is accepted`() {
        assertNull(ProxyCredentials.validate("X-Api-Key", "abc123"))
        assertNull(ProxyCredentials.validate("CF-Access-Client-Id", "0123.access"))
    }

    @Test
    fun `the app's own auth headers cannot be overridden`() {
        assertEquals(
            ProxyHeaderProblem.ReservedName,
            ProxyCredentials.validate("Authorization", "Basic abc"),
        )
        // Case does not rescue it.
        assertEquals(
            ProxyHeaderProblem.ReservedName,
            ProxyCredentials.validate("authorization", "Basic abc"),
        )
        // Nor do the transport headers that make a download resumable.
        assertEquals(ProxyHeaderProblem.ReservedName, ProxyCredentials.validate("Range", "bytes=0-"))
        assertEquals(
            ProxyHeaderProblem.ReservedName,
            ProxyCredentials.validate("Accept-Encoding", "gzip"),
        )
    }

    @Test
    fun `a header name outside the RFC 7230 token set is refused`() {
        assertEquals(ProxyHeaderProblem.IllegalName, ProxyCredentials.validate("X Api Key", "v"))
        assertEquals(ProxyHeaderProblem.IllegalName, ProxyCredentials.validate("X:Api", "v"))
        assertEquals(ProxyHeaderProblem.BlankName, ProxyCredentials.validate("  ", "v"))
    }

    @Test
    fun `a value with a line break is refused, because that is request splitting`() {
        assertEquals(
            ProxyHeaderProblem.IllegalValue,
            ProxyCredentials.validate("X-Api-Key", "abc\r\nX-Admin: true"),
        )
        assertEquals(ProxyHeaderProblem.BlankValue, ProxyCredentials.validate("X-Api-Key", ""))
    }

    @Test
    fun `encoding and decoding round-trips, values intact`() {
        val credentials = ProxyCredentials.of(
            "CF-Access-Client-Id" to "0123.access",
            "CF-Access-Client-Secret" to "a secret: with a colon",
        )

        val decoded = ProxyCredentials.decode(credentials.encode())

        assertEquals(credentials, decoded)
        assertEquals("a secret: with a colon", decoded.headers[1].value)
    }

    @Test
    fun `a corrupt stored line is skipped rather than fatal`() {
        val decoded = ProxyCredentials.decode("X-Api-Key: abc\nnonsense\n: novalue")

        assertEquals(1, decoded.headers.size)
        assertEquals("X-Api-Key", decoded.headers.single().name)
    }

    @Test
    fun `an empty set is the default, and stores nothing`() {
        assertTrue(ProxyCredentials.None.isEmpty)
        assertFalse(ProxyCredentials.None.isNotEmpty)
        assertEquals("", ProxyCredentials.None.encode())
        assertEquals(ProxyCredentials.None, ProxyCredentials.decode(null))
    }

    @Test
    fun `neither the header nor the set ever renders a value`() {
        val header = ProxyHeader("X-Api-Key", "hunter2hunter2")
        val credentials = ProxyCredentials.of(listOf(header))

        assertFalse(header.toString().contains("hunter2"))
        assertTrue(header.toString().contains("X-Api-Key"))
        assertFalse(credentials.toString().contains("hunter2"))
    }

    @Test
    fun `the cloudflare preset writes the two documented headers`() {
        val credentials = ProxyPresets.cloudflareAccess(" 0123.access ", "s3cret")

        assertEquals(
            listOf("CF-Access-Client-Id", "CF-Access-Client-Secret"),
            credentials.headers.map { it.name },
        )
        assertEquals("0123.access", credentials.headers.first().value)
    }

    @Test
    fun `a half-filled preset sends nothing at all`() {
        assertTrue(ProxyPresets.cloudflareAccess("0123.access", "").isEmpty)
        assertTrue(ProxyPresets.cloudflareAccess(null, "s3cret").isEmpty)
        assertTrue(ProxyPresets.basicAuth("user", "").isEmpty)
    }

    @Test
    fun `basic auth goes to Proxy-Authorization, not Authorization`() {
        val credentials = ProxyPresets.basicAuth("gatekeeper", "letmein")

        val header = credentials.headers.single()
        assertEquals("Proxy-Authorization", header.name)
        // The companion bearer owns Authorization; sharing it would produce a 401 on every
        // /api/v1 call with nothing on screen to explain it.
        assertEquals("Basic Z2F0ZWtlZXBlcjpsZXRtZWlu", header.value)
    }

    @Test
    fun `duplicates and overflow are trimmed rather than sent`() {
        val credentials = ProxyCredentials.of(
            (1..ProxyCredentials.MAX_HEADERS + 3).map { ProxyHeader("X-Key-" + it, "v" + it) },
        )
        assertEquals(ProxyCredentials.MAX_HEADERS, credentials.headers.size)

        val deduplicated = ProxyCredentials.of("X-Key" to "first", "x-key" to "second")
        assertEquals(1, deduplicated.headers.size)
        assertEquals("first", deduplicated.headers.single().value)
    }
}
