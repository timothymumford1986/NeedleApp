package app.needler.core.network

import app.needler.core.network.media.RangeDownloader
import app.needler.core.network.subsonic.DefaultSubsonicApi
import app.needler.core.network.v1.DefaultV1Api
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The failure that started this: a DroppedNeedle behind Cloudflare Access, answering every API call
 * - including the public probe the Connect screen uses - with a `302` to a sign-in page.
 *
 * The real transcript, from a real device:
 *
 * ```
 * HTTP/1.1 302 Found
 * Location: https://team.cloudflareaccess.com/cdn-cgi/access/login/music.example.com?kid=…
 * Set-Cookie: CF_AppSession=…
 * Server: cloudflare
 * ```
 *
 * The app followed it, asked a login page for JSON, and sat on "Connecting…" for forty-five
 * seconds. Every test here is about one of the two halves of the fix: fail on the first answer and
 * say what happened, or get through with the credential the proxy wants.
 */
class AuthenticatingProxyTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: TestCredentials
    private lateinit var http: NeedlerHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        credentials = TestCredentials()
            .withServer("http://" + server.hostName + ":" + server.port)
        http = NeedlerHttpClient(credentials = credentials, retryPolicy = RetryPolicy.None)
    }

    @After
    fun tearDown() {
        http.shutdown()
        server.close()
    }

    // ---- detection -----------------------------------------------------------

    @Test
    fun `a cloudflare access redirect fails on the first answer, naming the login host`() {
        server.enqueue(cloudflareAccessChallenge())

        val failure = failureFrom { DefaultV1Api(http, credentials).authProviders() }

        assertTrue("got " + failure, failure is NetworkError.AuthenticatingProxy)
        failure as NetworkError.AuthenticatingProxy
        assertEquals("team.cloudflareaccess.com", failure.host)
        assertEquals(ProxyVendor.CloudflareAccess, failure.interception.vendor)
        assertEquals(ProxySignal.CrossHostRedirect, failure.interception.signal)
        assertEquals(302, failure.interception.statusCode)
        assertEquals(ApiLane.V1, failure.lane)
        assertFalse(failure.interception.proxyCredentialsSent)
        // One request, and no request to the login host: the redirect was not followed, so nothing
        // was sent to a host the user never typed.
        assertEquals(1, server.requestCount)
        // Never retried. Three refusals would take three times as long to say the same thing.
        assertFalse(failure.isTransient)
    }

    @Test
    fun `a redirect to another host is refused even with a retrying policy`() {
        val retrying = NeedlerHttpClient(credentials = credentials, retryPolicy = RetryPolicy())
        server.enqueue(cloudflareAccessChallenge())

        val failure = failureFrom { DefaultV1Api(retrying, credentials).version() }

        assertTrue(failure is NetworkError.AuthenticatingProxy)
        assertEquals(1, server.requestCount)
        retrying.shutdown()
    }

    @Test
    fun `a login page served as HTML over HTTP 200 is detected on the subsonic lane`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body(
                    "<!DOCTYPE html><html><head><title>Sign in ・ Cloudflare Access</title>" +
                        "</head><body><form><input type=\"password\"></form></body></html>",
                )
                .build(),
        )

        val failure = failureFrom { DefaultSubsonicApi(http, credentials).ping() }

        assertTrue("got " + failure, failure is NetworkError.AuthenticatingProxy)
        failure as NetworkError.AuthenticatingProxy
        assertEquals(ProxySignal.HtmlInsteadOfJson, failure.interception.signal)
        assertEquals(ProxyVendor.CloudflareAccess, failure.interception.vendor)
        assertEquals(ApiLane.Subsonic, failure.lane)
    }

    @Test
    fun `an unbranded sign-in page is still detected, without naming a vendor`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<html><body><h1>Please log in</h1><form action=/login></form></body></html>")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).authProviders() }

        failure as NetworkError.AuthenticatingProxy
        assertEquals(ProxyVendor.Unknown, failure.interception.vendor)
        assertFalse(failure.isIdentified)
        // The host it can name is the one that answered, which is still actionable.
        assertEquals(server.hostName, failure.interception.requestedHost)
    }

    @Test
    fun `a 407 from an intercepting proxy is detected on its status alone`() {
        server.enqueue(
            MockResponse.Builder()
                .code(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"gateway\"")
                .body("")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).version() }

        failure as NetworkError.AuthenticatingProxy
        assertEquals(ProxySignal.ProxyAuthRequired, failure.interception.signal)
    }

    @Test
    fun `an authelia session cookie names authelia`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://auth.example.net/?rd=https%3A%2F%2Fmusic.example.net")
                .addHeader("Set-Cookie", "authelia_session=abc; Path=/; HttpOnly")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).version() }

        failure as NetworkError.AuthenticatingProxy
        assertEquals(ProxyVendor.Authelia, failure.interception.vendor)
        assertEquals("auth.example.net", failure.host)
    }

    // ---- and none of it fires on a healthy server ----------------------------

    @Test
    fun `a normal server is entirely unaffected`() {
        server.enqueue(json("{\"local\":true,\"oidc\":false}"))

        val providers = runBlocking { DefaultV1Api(http, credentials).authProviders() }

        assertTrue(providers.local)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an HTML 404 is still not-found, not a proxy`() {
        // Pointing at some other web server: the existing message ("that is not a Dropped Needle
        // server") is the right one and must not be replaced by a story about proxies.
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("Content-Type", "text/html")
                .body("<html><head><title>404 Not Found</title></head><body>nginx</body></html>")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).authProviders() }

        assertTrue("got " + failure, failure is NetworkError.NotFound)
    }

    @Test
    fun `an HTML 502 is still a server error`() {
        server.enqueue(
            MockResponse.Builder()
                .code(502)
                .addHeader("Content-Type", "text/html")
                .body("<html><body>Bad Gateway</body></html>")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).authProviders() }

        assertTrue("got " + failure, failure is NetworkError.Server)
    }

    @Test
    fun `a single-page app's index is not a sign-in page`() {
        // A catch-all `index.html` on an unrelated host: HTML, HTTP 200, no auth challenge in it.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<!DOCTYPE html><html><head><title>Some App</title></head><body></body></html>")
                .build(),
        )

        val failure = failureFrom { DefaultV1Api(http, credentials).authProviders() }

        assertTrue("got " + failure, failure is NetworkError.Serialisation)
    }

    @Test
    fun `a same-host redirect is followed, as a base-path fix-up must be`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/api/v1/auth/providers/")
                .build(),
        )
        server.enqueue(json("{\"local\":true}"))

        val providers = runBlocking { DefaultV1Api(http, credentials).authProviders() }

        assertTrue(providers.local)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("/api/v1/auth/providers/", server.takeRequest().target)
    }

    // ---- getting through: the headers on every lane --------------------------

    @Test
    fun `the proxy headers go on both API lanes, the media client and artwork`() {
        credentials.withProxyCredentials(
            ProxyPresets.cloudflareAccess(CLIENT_ID, CLIENT_SECRET),
        )
        server.enqueue(json("{\"version\":\"1.0\"}"))
        server.enqueue(json("{\"local\":true}"))
        server.enqueue(json("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}"))
        // A HEAD must be answered without a body, or MockWebServer corrupts the connection.
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "42").build())
        server.enqueue(json("{}"))

        val v1 = DefaultV1Api(http, credentials)
        val subsonic = DefaultSubsonicApi(http, credentials)
        runBlocking {
            // The bearer lane.
            v1.version()
            // The public probe, which is tagged SkipAuth: the proxy challenges it too, so the
            // headers have to go on even when no Needler credential does.
            v1.authProviders()
            // The Subsonic lane.
            runCatching { subsonic.ping() }
            // Audio, through the media client.
            RangeDownloader(http, credentials).probeLength(
                server.url("/subsonic/rest/stream").toString(),
            )
        }
        // Artwork: Coil builds its own request on the same media client, with no tag at all.
        http.mediaClient.newCall(
            Request.Builder().url(server.url("/api/v1/covers/album/1")).get().build(),
        ).execute().close()

        assertEquals(5, server.requestCount)
        val targets = mutableListOf<String>()
        repeat(5) {
            val request: RecordedRequest = server.takeRequest()
            targets += request.target
            assertEquals(
                "no client id on " + request.target,
                CLIENT_ID,
                request.headers["CF-Access-Client-Id"],
            )
            assertEquals(
                "no client secret on " + request.target,
                CLIENT_SECRET,
                request.headers["CF-Access-Client-Secret"],
            )
        }
        assertTrue(targets.any { it.contains("/api/v1/version") })
        assertTrue(targets.any { it.contains("/api/v1/auth/providers") })
        assertTrue(targets.any { it.contains("/subsonic/rest/ping") })
        assertTrue(targets.any { it.contains("/subsonic/rest/stream") })
        assertTrue(targets.any { it.contains("/api/v1/covers/") })
    }

    @Test
    fun `the headers are never sent to a host the user did not name`() {
        credentials.withProxyCredentials(ProxyCredentials.of("X-Api-Key" to CLIENT_SECRET))
        credentials.withServer("https://music.example.net")
        server.enqueue(json("{\"version\":\"1.0\"}"))

        http.client.newCall(Request.Builder().url(server.url("/api/v1/version")).build())
            .execute()
            .close()

        val request = server.takeRequest()
        assertNull(request.headers["X-Api-Key"])
    }

    @Test
    fun `an intercepted audio download never reaches the cache`() {
        credentials.withProxyCredentials(ProxyCredentials.of("X-Api-Key" to CLIENT_SECRET))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .addHeader("Set-Cookie", "CF_Authorization=stale; Path=/")
                .body("<!DOCTYPE html><html><body>Sign in to continue</body></html>")
                .build(),
        )
        val target = File(Files.createTempDirectory("needler-proxy").toFile(), "track.flac")

        val failure = failureFrom {
            RangeDownloader(http, credentials).downloadTo(
                url = server.url("/subsonic/rest/download").toString(),
                target = target,
            )
        }

        assertTrue("got " + failure, failure is NetworkError.AuthenticatingProxy)
        failure as NetworkError.AuthenticatingProxy
        // The headers were sent and refused anyway, which is a different instruction to the user.
        assertTrue(failure.interception.proxyCredentialsSent)
        assertFalse("a login page was written as audio", target.exists())
    }

    // ---- and the secret stays a secret ---------------------------------------

    @Test
    fun `no proxy header value reaches a log line or an error message`() {
        val lines = mutableListOf<String>()
        val logged = NeedlerHttpClient(
            credentials = credentials,
            logSink = NetworkLogSink { lines += it },
            retryPolicy = RetryPolicy.None,
        )
        credentials.withProxyCredentials(
            ProxyPresets.cloudflareAccess(CLIENT_ID, CLIENT_SECRET),
        )
        server.enqueue(cloudflareAccessChallenge())
        server.enqueue(json("{\"version\":\"1.0\"}"))

        val failure = failureFrom { DefaultV1Api(logged, credentials).authProviders() }
        runBlocking { DefaultSubsonicApi(logged, credentials).mediaUrls }
        http.client.newCall(
            Request.Builder().url(server.url("/subsonic/rest/stream?apiKey=test-app-password"))
                .build(),
        ).execute().close()

        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertFalse("secret in log: " + line, line.contains(CLIENT_SECRET))
            assertFalse("app-password in log: " + line, line.contains("test-app-password"))
            assertFalse("bearer in log: " + line, line.contains("test-bearer"))
        }
        val message = requireNotNull(failure.message)
        assertFalse(message.contains(CLIENT_SECRET))
        assertFalse(message.contains(CLIENT_ID))
        assertTrue("the message must still name the host", message.contains("cloudflareaccess.com"))
        logged.shutdown()
    }

    // ---- helpers -------------------------------------------------------------

    /** The real Cloudflare Access challenge, header for header. */
    private fun cloudflareAccessChallenge(): MockResponse = MockResponse.Builder()
        .code(302)
        .addHeader(
            "Location",
            "https://team.cloudflareaccess.com/cdn-cgi/access/login/music.example.com" +
                "?kid=8a1f&redirect_url=%2Fapi%2Fv1%2Fauth%2Fproviders",
        )
        .addHeader("Set-Cookie", "CF_AppSession=b7c1d2; Path=/; HttpOnly; Secure")
        .addHeader("Server", "cloudflare")
        .build()

    private fun json(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun failureFrom(block: suspend () -> Unit): NetworkError = try {
        runBlocking { block() }
        throw AssertionError("the call was expected to fail")
    } catch (error: NetworkError) {
        error
    }

    private companion object {
        const val CLIENT_ID = "0123456789abcdef0123456789abcdef.access"
        const val CLIENT_SECRET = "s3cret-service-token-value"
    }
}
