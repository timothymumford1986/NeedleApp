package app.needler.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers every URL form REQUIREMENTS.md §"Accepted URL forms" lists, the normalisation rules, and
 * the rejections the Connect screen has to explain.
 */
class ServerUrlTest {

    private fun valid(raw: String): ServerUrl {
        val result = ServerUrl.parse(raw)
        assertTrue("expected $raw to parse, got $result", result is ServerUrlResult.Valid)
        return (result as ServerUrlResult.Valid).url
    }

    private fun invalid(raw: String): ServerUrlResult.Invalid {
        val result = ServerUrl.parse(raw)
        assertTrue("expected $raw to be rejected, got $result", result is ServerUrlResult.Invalid)
        return result as ServerUrlResult.Invalid
    }

    // ------------------------------------------------- the four accepted forms

    @Test
    fun `https host with no port keeps the scheme default`() {
        val url = valid("https://music.yourhome.net")
        assertEquals("https", url.scheme)
        assertEquals("music.yourhome.net", url.host)
        assertNull(url.port)
        assertEquals("", url.basePath)
        assertEquals(443, url.effectivePort())
        assertEquals("https://music.yourhome.net", url.baseUrl)
        assertFalse(url.isCleartext)
        assertFalse(url.schemeWasAssumed)
    }

    @Test
    fun `plain http on a lan address is accepted`() {
        val url = valid("http://192.168.1.50:8688")
        assertEquals("http", url.scheme)
        assertEquals("192.168.1.50", url.host)
        assertEquals(8688, url.port)
        assertTrue(url.isCleartext)
        assertEquals("http://192.168.1.50:8688", url.baseUrl)
    }

    @Test
    fun `sub-path deployment keeps the base path`() {
        val url = valid("https://home.net/music")
        assertEquals("/music", url.basePath)
        assertEquals("https://home.net/music", url.baseUrl)
        assertEquals("https://home.net/music/api/v1/version", url.apiV1("/version"))
        assertEquals("https://home.net/music/subsonic/rest/ping", url.subsonicRest("ping"))
    }

    @Test
    fun `bare host defaults to http and port 8688`() {
        val url = valid("192.168.1.50")
        assertEquals("http", url.scheme)
        assertEquals(ServerUrl.DEFAULT_PORT, url.port)
        assertTrue(url.schemeWasAssumed)
        assertTrue(url.portWasAssumed)
        assertEquals("http://192.168.1.50:8688", url.baseUrl)
    }

    @Test
    fun `bare host with an explicit port keeps that port`() {
        val url = valid("nas.local:9000")
        assertEquals("http", url.scheme)
        assertEquals(9000, url.port)
        assertTrue(url.schemeWasAssumed)
        assertFalse(url.portWasAssumed)
    }

    @Test
    fun `bare host with a sub-path still defaults scheme and port`() {
        val url = valid("nas.local/music")
        assertEquals("http://nas.local:8688/music", url.baseUrl)
        assertEquals("/music", url.basePath)
    }

    // ------------------------------------------------------------ normalisation

    @Test
    fun `trailing slashes are trimmed`() {
        assertEquals("", valid("https://host/").basePath)
        assertEquals("", valid("https://host///").basePath)
        assertEquals("/music", valid("https://host/music/").basePath)
        assertEquals("/music", valid("https://host/music///").basePath)
        assertEquals("/a/b", valid("https://host/a/b/").basePath)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://host", valid("  https://host  ").baseUrl)
    }

    @Test
    fun `scheme and host are lower-cased but the base path is not`() {
        val url = valid("HTTPS://Music.Example.NET/Music")
        assertEquals("https", url.scheme)
        assertEquals("music.example.net", url.host)
        // The server's own base-path validator is case-sensitive, so /Music must survive as typed.
        assertEquals("/Music", url.basePath)
    }

    @Test
    fun `protocol default ports are left implicit`() {
        assertNull(valid("https://host:443").port)
        assertNull(valid("http://host:80").port)
        assertEquals("https://host", valid("https://host:443").baseUrl)
    }

    @Test
    fun `a trailing dot on the host is dropped`() {
        assertEquals("host.example.com", valid("https://host.example.com.").host)
    }

    @Test
    fun `ipv6 literals keep their brackets in urls only`() {
        val url = valid("http://[fd00::1]:8688")
        assertEquals("fd00::1", url.host)
        assertEquals("[fd00::1]", url.urlHost)
        assertEquals("http://[fd00::1]:8688", url.baseUrl)
    }

    // ---------------------------------------------------------------- rejections

    @Test
    fun `empty input is rejected`() {
        assertEquals(ServerUrlResult.Reason.Empty, invalid("").reason)
        assertEquals(ServerUrlResult.Reason.Empty, invalid("   ").reason)
        assertNull(ServerUrl.parseOrNull(null))
    }

    @Test
    fun `only http and https are supported`() {
        assertEquals(ServerUrlResult.Reason.UnsupportedScheme, invalid("ftp://host").reason)
        assertEquals(ServerUrlResult.Reason.UnsupportedScheme, invalid("ws://host").reason)
    }

    @Test
    fun `a query string or fragment is rejected`() {
        assertEquals(ServerUrlResult.Reason.QueryOrFragment, invalid("https://host/music?x=1").reason)
        assertEquals(ServerUrlResult.Reason.QueryOrFragment, invalid("https://host#top").reason)
    }

    @Test
    fun `credentials in the url are rejected with their own reason`() {
        assertEquals(
            ServerUrlResult.Reason.CredentialsInUrl,
            invalid("https://user:secret@host").reason,
        )
    }

    @Test
    fun `bad ports are rejected`() {
        assertEquals(ServerUrlResult.Reason.InvalidPort, invalid("https://host:abc").reason)
        assertEquals(ServerUrlResult.Reason.InvalidPort, invalid("https://host:0").reason)
        assertEquals(ServerUrlResult.Reason.InvalidPort, invalid("https://host:70000").reason)
        assertEquals(ServerUrlResult.Reason.InvalidPort, invalid("https://host:").reason)
    }

    @Test
    fun `bad hosts are rejected`() {
        assertEquals(ServerUrlResult.Reason.MissingHost, invalid("https://").reason)
        assertEquals(ServerUrlResult.Reason.InvalidHost, invalid("https://host_name").reason)
        assertEquals(ServerUrlResult.Reason.InvalidHost, invalid("https://-host.net").reason)
        // An unbracketed IPv6 literal is a common mistake and gets a specific message.
        assertEquals(ServerUrlResult.Reason.InvalidHost, invalid("http://fd00::1:8688").reason)
    }

    @Test
    fun `base paths are held to the server's own rules`() {
        assertEquals(ServerUrlResult.Reason.InvalidBasePath, invalid("https://host/../etc").reason)
        assertEquals(ServerUrlResult.Reason.InvalidBasePath, invalid("https://host/./music").reason)
        assertEquals(ServerUrlResult.Reason.InvalidBasePath, invalid("https://host/mu%20sic").reason)
        assertEquals(ServerUrlResult.Reason.InvalidBasePath, invalid("https://host/a//b").reason)
        assertEquals(ServerUrlResult.Reason.InvalidBasePath, invalid("https://host/mus!c").reason)
        val tooLong = "https://host/" + "a".repeat(ServerUrl.MAX_BASE_PATH_LENGTH + 1)
        assertEquals(ServerUrlResult.Reason.BasePathTooLong, invalid(tooLong).reason)
    }

    @Test
    fun `whitespace inside the address is rejected`() {
        assertEquals(ServerUrlResult.Reason.Malformed, invalid("https://ho st/music").reason)
        assertEquals(ServerUrlResult.Reason.Malformed, invalid("https://host\\music").reason)
    }

    @Test
    fun `rejection details never echo a credential`() {
        val detail = invalid("https://user:secret@host").detail
        assertFalse(detail.contains("secret"))
    }

    // ----------------------------------------------------------- url composition

    @Test
    fun `api paths compose with and without a leading slash`() {
        val url = valid("https://host")
        assertEquals("https://host/api/v1/version", url.apiV1("/version"))
        assertEquals("https://host/api/v1/version", url.apiV1("version"))
        assertEquals("https://host/api/v1", url.apiV1(""))
        assertEquals("https://host/subsonic/rest/getArtists", url.subsonicRest("getArtists"))
        assertEquals("https://host/subsonic/rest/getArtists", url.subsonicRest("/getArtists"))
    }

    @Test
    fun `server identity ignores an implicit default port`() {
        assertTrue(valid("https://host").sameServerAs(valid("https://host:443")))
        assertFalse(valid("https://host").sameServerAs(valid("https://host:8443")))
        assertFalse(valid("https://host").sameServerAs(valid("http://host")))
        assertFalse(valid("https://host/music").sameServerAs(valid("https://host")))
    }

    @Test
    fun `parseOrNull returns the url or null`() {
        assertEquals("http://nas.local:8688", ServerUrl.parseOrNull("nas.local")?.baseUrl)
        assertNull(ServerUrl.parseOrNull("ftp://nas.local"))
    }
}
