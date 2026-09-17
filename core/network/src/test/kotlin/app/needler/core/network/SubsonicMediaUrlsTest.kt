package app.needler.core.network

import app.needler.core.network.media.StreamQuality
import app.needler.core.network.media.SubsonicMediaUrls
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binary URLs Media3, Coil and a Cast receiver fetch directly. Two things must hold: the
 * default is original bytes (`format=raw`), and the credential these URLs carry is never visible
 * once the URL reaches a log.
 */
class SubsonicMediaUrlsTest {

    private val credentials = TestCredentials()
    private val urls = SubsonicMediaUrls(credentials)

    private fun query(url: String) = url.toHttpUrl().let { parsed ->
        (0 until parsed.querySize).associate { parsed.queryParameterName(it) to parsed.queryParameterValue(it) }
    }

    @Test
    fun `stream defaults to original bytes`() {
        val url = urls.streamUrl("tr-4711")
        assertTrue(url.startsWith("https://music.example.net/subsonic/rest/stream?"))
        val parameters = query(url)
        assertEquals("raw", parameters["format"])
        assertEquals("tr-4711", parameters["id"])
        assertEquals("Needler", parameters["c"])
        assertEquals("json", parameters["f"])
        assertEquals("1.16.1", parameters["v"])
        assertEquals("test-app-password", parameters["apiKey"])
        // No transcode parameters on the raw path.
        assertNull(parameters["maxBitRate"])
    }

    @Test
    fun `the metered-data case asks for mp3 320`() {
        val parameters = query(urls.streamUrl("tr-1", StreamQuality.Transcoded()))
        assertEquals("mp3", parameters["format"])
        assertEquals("320", parameters["maxBitRate"])
    }

    @Test
    fun `a transcode can start at an offset`() {
        val parameters = query(
            urls.streamUrl("tr-1", StreamQuality.Transcoded(maxBitRateKbps = 192), timeOffsetSeconds = 42.0),
        )
        assertEquals("192", parameters["maxBitRate"])
        assertEquals("42", parameters["timeOffset"])
    }

    @Test
    fun `download and cover art build on the same base`() {
        assertEquals(
            "tr-9",
            query(urls.downloadUrl("tr-9"))["id"],
        )
        assertTrue(urls.downloadUrl("tr-9").contains("/subsonic/rest/download?"))

        val cover = query(urls.coverArtUrl("al-abc", size = 500))
        assertEquals("al-abc", cover["id"])
        assertEquals("500", cover["size"])
    }

    @Test
    fun `cover art size is clamped to what the server accepts`() {
        assertEquals("2000", query(urls.coverArtUrl("al-abc", size = 9_999))["size"])
        assertEquals("1", query(urls.coverArtUrl("al-abc", size = 0))["size"])
        assertNull(query(urls.coverArtUrl("al-abc"))["size"])
    }

    @Test
    fun `catalogue cover art uses the v1 lane and carries no credential`() {
        val url = urls.catalogueCoverUrl("4e0f5b3c-0000-4000-8000-000000000001")
        assertEquals(
            "https://music.example.net/api/v1/covers/release-group/4e0f5b3c-0000-4000-8000-000000000001?size=500",
            url,
        )
        assertFalse(url.contains("apiKey"))
    }

    @Test
    fun `a sub-path deployment is honoured`() {
        val subPath = SubsonicMediaUrls(TestCredentials().withServer("https://home.net/music"))
        assertTrue(subPath.streamUrl("tr-1").startsWith("https://home.net/music/subsonic/rest/stream?"))
        assertTrue(
            subPath.catalogueCoverUrl("mbid").startsWith("https://home.net/music/api/v1/covers/release-group/mbid"),
        )
    }

    @Test
    fun `redaction hides the app-password but keeps the url readable`() {
        val redacted = redactUrl(urls.streamUrl("tr-4711"))
        assertFalse(redacted.contains("test-app-password"))
        assertTrue(redacted.contains("apiKey=REDACTED"))
        assertTrue(redacted.contains("id=tr-4711"))
        assertTrue(redacted.contains("format=raw"))
    }

    @Test
    fun `redaction covers the legacy subsonic auth parameters too`() {
        val redacted = redactUrl("https://host/subsonic/rest/ping?u=bob&t=deadbeef&s=salt&p=plaintext&c=Needler")
        assertFalse(redacted.contains("deadbeef"))
        assertFalse(redacted.contains("plaintext"))
        assertFalse(redacted.contains("salt"))
        assertTrue(redacted.contains("c=Needler"))
    }

    @Test
    fun `redaction of a non-url falls back to dropping the query`() {
        assertEquals("not a url?REDACTED", redactUrl("not a url?token=abc"))
        assertEquals("not a url", redactUrl("not a url"))
    }
}
