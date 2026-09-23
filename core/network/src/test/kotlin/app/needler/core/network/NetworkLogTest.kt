package app.needler.core.network

import app.needler.core.network.media.RangeDownloader
import app.needler.core.network.media.SubsonicMediaUrls
import app.needler.core.network.v1.DefaultV1Api
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The bug this file is the regression test for was not a wrong log line. It was no log line.
 *
 * `RedactingLogInterceptor` existed, was wired into [NeedlerHttpClient] and was correct;
 * [redactUrl] existed and was tested. But the only implementation of [NetworkLogSink] in the whole
 * project was [NetworkLogSink.None], the one place that builds a client in the running app took
 * the default argument, and the interceptor's first line is a short circuit on exactly that. A
 * whole session's `logcat -d`, filtered to the app's PID, contained framework noise and not one
 * line of ours — so "offline downloads retain nothing" and "every artist's discography fails to
 * load" could not be told apart from each other, let alone from a dead session or a captive
 * portal.
 *
 * So these tests are in two halves. The first half asserts that something now comes out at all,
 * and that what comes out is enough to diagnose those two failures from `adb logcat` alone. The
 * second half asserts the thing that makes the first half safe to ship: that no credential ever
 * appears in a line, **including** the Subsonic query credential, which is the interesting case
 * because it lives in the URL rather than in a header and is appended by an interceptor that runs
 * *before* the logging one.
 */
class NetworkLogTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: TestCredentials
    private lateinit var sink: RecordingSink
    private lateinit var http: NeedlerHttpClient

    @Before
    fun setUp() {
        // NetworkDiagnostics is process-wide and these tests share a JVM with every other test in
        // the module, so each end of every test puts it back to silence.
        NetworkDiagnostics.disable()
        server = MockWebServer()
        server.start()
        credentials = TestCredentials().withServer("http://" + server.hostName + ":" + server.port)
        sink = RecordingSink()
        http = NeedlerHttpClient(
            credentials = credentials,
            logSink = sink,
            retryPolicy = RetryPolicy.None,
        )
    }

    @After
    fun tearDown() {
        http.shutdown()
        server.close()
        // The install point is process-wide, so a test that touches it must put it back.
        NetworkDiagnostics.disable()
    }

    // ---- something comes out at all ------------------------------------------

    @Test
    fun `a successful call writes one line naming the method, url, status and duration`() {
        server.enqueue(json("{\"version\":\"1.4.0\"}"))

        runBlocking { DefaultV1Api(http, credentials).version() }

        val line = sink.lines.single { it.contains("/api/v1/version") }
        assertTrue(line, line.startsWith("GET "))
        assertTrue(line, line.contains("-> 200"))
        assertTrue(line, line.contains("ms"))
        assertEquals(NetworkLogLevel.Debug, sink.levelOf(line))
    }

    @Test
    fun `the default client with nothing installed writes nothing`() {
        // The release-build shape: NetworkDiagnostics was never told to install anything, so the
        // default NetworkLogSink.Installed resolves to None and every log call returns immediately.
        val silent = NeedlerHttpClient(credentials = credentials, retryPolicy = RetryPolicy.None)
        server.enqueue(json("{\"version\":\"1.4.0\"}"))

        runBlocking { DefaultV1Api(silent, credentials).version() }

        assertFalse(NetworkDiagnostics.isEnabled)
        assertTrue(sink.lines.isEmpty())
        silent.shutdown()
    }

    @Test
    fun `installing a sink reaches a client that was built before the install`() {
        // The ordering that matters on a device: Hilt builds the NeedlerHttpClient singleton while
        // injecting fields into NeedlerApplication, which happens inside super.onCreate() and so
        // before the application's own onCreate body can install anything.
        val early = NeedlerHttpClient(credentials = credentials, retryPolicy = RetryPolicy.None)
        val installed = RecordingSink()
        NetworkDiagnostics.install(installed)
        server.enqueue(json("{\"version\":\"1.4.0\"}"))

        runBlocking { DefaultV1Api(early, credentials).version() }

        assertTrue(installed.lines.any { it.contains("/api/v1/version") })
        early.shutdown()
    }

    @Test
    fun `logging disabled at construction silences the client entirely`() {
        val quiet = NeedlerHttpClient(
            credentials = credentials,
            logSink = sink,
            loggingEnabled = false,
            retryPolicy = RetryPolicy.None,
        )
        server.enqueue(json("{\"version\":\"1.4.0\"}"))

        runBlocking { DefaultV1Api(quiet, credentials).version() }

        assertTrue(sink.lines.isEmpty())
        quiet.shutdown()
    }

    // ---- the two failures that could not be diagnosed ------------------------

    @Test
    fun `a failing artist releases call says the status and the error it was mapped to`() {
        // The real failure: "the rest of this artist's discography could not be fetched from the
        // catalogue", for every artist, with nothing anywhere saying why.
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"musicbrainz lookup failed\"}}")
                .build(),
        )

        val failure = failureFrom {
            DefaultV1Api(http, credentials).artistReleases("f27ec8db-af05-4f36-916e-3d57f91ecf5e")
        }

        assertTrue(failure is NetworkError.Server)
        val mapped = sink.lines.single { it.contains("mapped to") }
        assertTrue(mapped, mapped.contains("/api/v1/artists/f27ec8db-af05-4f36-916e-3d57f91ecf5e/releases"))
        assertTrue(mapped, mapped.contains("-> 500"))
        assertTrue(mapped, mapped.contains("code=INTERNAL_ERROR"))
        assertTrue(mapped, mapped.contains("Server"))
        assertTrue(mapped, mapped.contains("musicbrainz lookup failed"))
        assertEquals(NetworkLogLevel.Warn, sink.levelOf(mapped))
    }

    @Test
    fun `a session expiry is named as such rather than left as a bare 401`() {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"token expired\"}}")
                .build(),
        )

        failureFrom { DefaultV1Api(http, credentials).artistReleases("mbid") }

        val mapped = sink.lines.single { it.contains("mapped to") }
        assertTrue(mapped, mapped.contains("Unauthorised"))
        assertTrue(mapped, mapped.contains("V1"))
        assertEquals(1, credentials.bearerRejections)
    }

    @Test
    fun `a parse failure names the endpoint that could not be read`() {
        server.enqueue(json("{\"version\":12345}"))

        failureFrom { DefaultV1Api(http, credentials).version() }

        val parse = sink.lines.single { it.contains("could not parse") }
        assertTrue(parse, parse.contains("/api/v1/version"))
        assertEquals(NetworkLogLevel.Error, sink.levelOf(parse))
    }

    @Test
    fun `a finished download reports how many bytes it actually retained`() {
        // The first reported failure — downloads run and retain nothing — needs this module to say
        // whether the bytes ever arrived, so that the search can move on to whatever discards them.
        // A String body rather than a binary one: MockWebServer sets Content-Length from it, and
        // what is under test is the accounting, not the bytes.
        val audio = "a".repeat(4_096)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "audio/flac")
                .body(audio)
                .build(),
        )
        val target = File(Files.createTempDirectory("needler-log").toFile(), "track.flac")

        runBlocking {
            RangeDownloader(http, credentials).downloadTo(
                url = server.url("/subsonic/rest/download?id=tr-1").toString(),
                target = target,
            )
        }

        val finished = sink.lines.single { it.startsWith("download finished") }
        assertTrue(finished, finished.contains("wrote=4096B"))
        assertTrue(finished, finished.contains("size=4096B"))
        assertTrue(finished, finished.contains("complete=true"))
        assertEquals(NetworkLogLevel.Info, sink.levelOf(finished))
        // And nothing per chunk: a 4 KiB body is one 64 KiB read, but the assertion that matters is
        // that the count does not scale with the body.
        assertTrue(sink.lines.count { it.startsWith("download") } <= 2)
    }

    // ---- and the credentials stay out of it ----------------------------------

    @Test
    fun `the subsonic query credential is redacted even though an interceptor appends it`() {
        // The interesting case. CredentialInterceptor puts the app-password into the URL's query,
        // and it runs *before* RedactingLogInterceptor, so the URL the logger is handed genuinely
        // contains the secret. A naive log of request.url would publish it.
        server.enqueue(json("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}"))

        http.client.newCall(
            Request.Builder().url(server.url("/subsonic/rest/ping?f=json")).build(),
        ).execute().close()

        val line = sink.lines.single { it.contains("/subsonic/rest/ping") }
        assertTrue(line, line.contains("apiKey=REDACTED"))
        assertFalse(line, line.contains("test-app-password"))
    }

    @Test
    fun `the legacy subsonic salt-and-token credential in the url is redacted`() {
        server.enqueue(json("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}"))

        http.client.newCall(
            Request.Builder()
                .url(
                    server.url(
                        "/subsonic/rest/ping?u=bob&t=26719a1196d2a940705a59634eb18eab" +
                            "&s=c19b2d&p=enc:6265657273&c=Needler",
                    ),
                )
                .build(),
        ).execute().close()

        val line = sink.lines.single { it.contains("/subsonic/rest/ping") }
        assertFalse(line, line.contains("26719a1196d2a940705a59634eb18eab"))
        assertFalse(line, line.contains("c19b2d"))
        assertFalse(line, line.contains("enc:6265657273"))
        // Still readable: the client name and the username survive, so the line is worth having.
        assertTrue(line, line.contains("c=Needler"))
        assertTrue(line, line.contains("u=bob"))
    }

    @Test
    fun `no bearer, app-password or proxy header value reaches any line of a whole session`() {
        credentials.withProxyCredentials(ProxyCredentials.of("X-Api-Key" to PROXY_SECRET))
        server.enqueue(json("{\"version\":\"1.4.0\"}"))
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"nope\"}}")
                .build(),
        )
        server.enqueue(json("{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}"))

        runBlocking { DefaultV1Api(http, credentials).version() }
        failureFrom { DefaultV1Api(http, credentials).artistReleases("mbid") }
        http.client.newCall(
            Request.Builder().url(server.url("/subsonic/rest/ping?f=json")).build(),
        ).execute().close()

        assertTrue(sink.lines.isNotEmpty())
        for (line in sink.lines) {
            assertFalse("bearer in log: " + line, line.contains("test-bearer"))
            assertFalse("app-password in log: " + line, line.contains("test-app-password"))
            assertFalse("proxy secret in log: " + line, line.contains(PROXY_SECRET))
            // No request header is logged at all, so not even the name should appear.
            assertFalse("proxy header in log: " + line, line.contains("X-Api-Key"))
            assertFalse("authorization in log: " + line, line.contains("Authorization"))
        }
    }

    @Test
    fun `a parse failure never republishes the body it could not read`() {
        // kotlinx.serialization appends the document to its own message. One of the documents this
        // module parses is the login response, whose body is a freshly minted bearer token, so a
        // raw cause.message in a log line would leak a live credential on the day authentication
        // broke — the day somebody is most likely to be reading logcat.
        server.enqueue(json("{\"access_token\": \"" + MINTED_TOKEN + "\""))

        failureFrom { DefaultV1Api(http, credentials).version() }

        assertTrue(sink.lines.any { it.contains("could not parse") })
        // The value is what must never appear. A field *name* reaching the log through the
        // parser's JSON path is harmless and is not asserted against, because it is a name.
        for (line in sink.lines) {
            assertFalse("token in log: " + line, line.contains(MINTED_TOKEN))
            assertFalse("body in log: " + line, line.contains("eyJhbGci"))
        }
    }

    // ---- the redaction rules themselves --------------------------------------

    @Test
    fun `an unknown but secret-shaped query key loses its value and keeps its name`() {
        val redacted = redactUrl("https://music.example.net/api/v1/thing?page=2&session_token=abc123")

        assertTrue(redacted, redacted.contains("page=2"))
        assertTrue(redacted, redacted.contains("session_token=REDACTED"))
        assertFalse(redacted, redacted.contains("abc123"))
    }

    @Test
    fun `userinfo in a url is dropped whole`() {
        // ServerUrl.parse refuses an address containing '@', so nothing this module builds can
        // carry userinfo — but a redirect Location, or a URL handed in by another module, can.
        val redacted = redactUrl("https://admin:hunter2@music.example.net/subsonic/rest/ping?c=Needler")

        assertFalse(redacted, redacted.contains("hunter2"))
        assertFalse(redacted, redacted.contains("admin"))
        assertTrue(redacted, redacted.startsWith("https://music.example.net/"))
        assertTrue(redacted, redacted.contains("c=Needler"))
    }

    @Test
    fun `the app-password is redacted from a media url built for media3 or coil`() {
        val urls = SubsonicMediaUrls(TestCredentials())
        val redacted = redactUrl(urls.streamUrl("tr-4711").toHttpUrl())

        assertFalse(redacted, redacted.contains("test-app-password"))
        assertTrue(redacted, redacted.contains("apiKey=REDACTED"))
        assertTrue(redacted, redacted.contains("id=tr-4711"))
    }

    @Test
    fun `a parse message keeps the diagnosis and drops the document`() {
        val leaky = "Unexpected JSON token at offset 21: Expected quotation mark at path: " +
            "\$.access_token\nJSON input: {\"access_token\":\"" + MINTED_TOKEN + "\"}"

        val safe = redactParseMessage(leaky)

        assertFalse(safe, safe.contains(MINTED_TOKEN))
        assertFalse(safe, safe.contains("JSON input"))
        assertTrue(safe, safe.contains("Unexpected JSON token at offset 21"))
    }

    @Test
    fun `a missing field message survives intact because it names fields and not values`() {
        val message = "Fields [releases] are required for type with serial name " +
            "'app.needler.core.network.v1.dto.ArtistReleasesDto', but they were missing at path: \$"

        assertEquals(message, redactParseMessage(message))
        assertEquals("no detail", redactParseMessage(null))
    }

    @Test
    fun `an over-long parse message is truncated rather than filling the buffer`() {
        val safe = redactParseMessage("x".repeat(4_000))

        assertTrue(safe.length < 300)
        assertTrue(safe.endsWith("…"))
    }

    // ---- the install point ---------------------------------------------------

    @Test
    fun `the process default is silence and installing Installed is refused`() {
        assertFalse(NetworkDiagnostics.isEnabled)
        assertEquals(NetworkLogSink.None, NetworkDiagnostics.sink)

        NetworkDiagnostics.install(sink)
        assertTrue(NetworkDiagnostics.isEnabled)

        // Installing the forwarding sink into the thing it forwards to would recurse forever.
        var refused = false
        try {
            NetworkDiagnostics.install(NetworkLogSink.Installed)
        } catch (expected: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)

        NetworkDiagnostics.disable()
        assertFalse(NetworkDiagnostics.isEnabled)
    }

    @Test
    fun `a sink written as a lambda still receives levelled lines`() {
        // NetworkLogSink is a fun interface with one abstract member, so the file-backed sink in
        // :core:data can stay a one-liner and simply not care about severity.
        val plain = mutableListOf<String>()
        val log = NetworkLog(NetworkLogSink { plain += it })

        log.warn { "something" }

        assertEquals(listOf("something"), plain)
    }

    // ---- helpers -------------------------------------------------------------

    /** Records both halves of every line so the level routing can be asserted, not just the text. */
    private class RecordingSink : NetworkLogSink {
        val entries: MutableList<Pair<NetworkLogLevel, String>> = mutableListOf()

        val lines: List<String> get() = entries.map { it.second }

        override fun log(line: String) {
            log(NetworkLogLevel.Debug, line)
        }

        override fun log(level: NetworkLogLevel, line: String) {
            synchronized(entries) { entries += level to line }
        }

        fun levelOf(line: String): NetworkLogLevel = entries.first { it.second == line }.first
    }

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
        const val PROXY_SECRET = "s3cret-service-token-value"
        const val MINTED_TOKEN = "eyJhbGciOiJIUzI1NiJ9.super-secret-bearer.signature"
    }
}
