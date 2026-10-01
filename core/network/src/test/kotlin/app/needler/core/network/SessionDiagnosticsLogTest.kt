package app.needler.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics log REQUIREMENTS.md "Observability" requires, and the redaction that makes it safe
 * to hand to a stranger.
 *
 * ## Why the redaction half of this file is the important half
 *
 * The feature is "a log the user can read and share as a file for a bug report". Everything that
 * makes it work is a ring buffer and some string formatting. Everything that makes it *shippable* is
 * the rule that no credential can be inside it, and REQUIREMENTS.md "Security" rule 1 — "Credentials
 * in Keystore-backed storage, never in logs, analytics or crash reports" — has no exception for a log
 * the user chose to look at. Needler holds two secrets and they hide in opposite places:
 *
 *  * the Subsonic app-password travels as the `apiKey` **query parameter**, appended by an
 *    interceptor, so a naive log of `request.url` prints it;
 *  * the companion bearer travels in a **request header**, so a naive header dump prints that one
 *    instead.
 *
 * `NetworkLogTest` already proves that `RedactingLogInterceptor` gets both right for lines it writes
 * itself. These tests prove the second line of defence: that [redactLogLine], which every line
 * entering [SessionDiagnosticsLog] goes through whoever wrote it, gets both right for arbitrary text
 * — because the sync summaries and playback errors the requirement also asks for are written by
 * modules that have never seen an `HttpUrl`.
 *
 * The doubled-secret cases exist because a rule that redacts the *first* occurrence and stops is
 * worse than no rule at all: the line looks redacted, so nobody reads it again.
 */
class SessionDiagnosticsLogTest {

    // ---- the Subsonic lane: a credential in the query -------------------------

    @Test
    fun `the app-password in a query parameter is redacted`() {
        val line = redactLogLine(
            "GET https://music.yourhome.net/rest/ping?u=tim&apiKey=" + APP_PASSWORD +
                "&c=Needler&f=json -> 200",
        )

        assertNoSecrets(line)
        assertTrue(line, line.contains("apiKey=REDACTED"))
    }

    @Test
    fun `the same app-password twice in one url loses both copies`() {
        // A duplicated parameter is not hypothetical: a retry that re-appends the credential, or a
        // proxy that echoes the query back in a Location header, both produce one. A replacement
        // that stopped at the first match would leave the second in plain sight behind a line that
        // reads as though it had been cleaned.
        val line = redactLogLine(
            "GET https://music.yourhome.net/rest/stream?id=42&apiKey=" + APP_PASSWORD +
                "&u=tim&apiKey=" + APP_PASSWORD + " -> 200",
        )

        assertNoSecrets(line)
        assertEquals(
            "both copies should read REDACTED: " + line,
            2,
            Regex("apiKey=REDACTED").findAll(line).count(),
        )
    }

    @Test
    fun `the same app-password in two urls on one line loses both copies`() {
        val line = redactLogLine(
            "retry: GET /rest/ping?apiKey=" + APP_PASSWORD +
                " failed, retrying GET /rest/ping?apiKey=" + APP_PASSWORD,
        )

        assertNoSecrets(line)
        assertEquals(2, Regex("apiKey=REDACTED").findAll(line).count())
    }

    @Test
    fun `the legacy salt and token trio is redacted even though the names are single letters`() {
        val line = redactLogLine("GET /rest/getArtists?u=tim&t=abc123def&s=pepper&p=enc:deadbeef")

        assertFalse(line, line.contains("abc123def"))
        assertFalse(line, line.contains("pepper"))
        assertFalse(line, line.contains("deadbeef"))
        // The username is not a secret and is the single most useful thing in an auth failure.
        assertTrue(line, line.contains("u=tim"))
    }

    // ---- the v1 lane: a credential in a header --------------------------------

    @Test
    fun `an authorization header loses its whole value`() {
        val line = redactLogLine("POST /api/v1/requests/new Authorization: Bearer " + BEARER)

        assertNoSecrets(line)
        assertTrue(line, line.contains("Authorization: REDACTED"))
    }

    @Test
    fun `a bearer quoted without its header name is still redacted`() {
        val line = redactLogLine("session refused, token was Bearer " + BEARER)

        assertNoSecrets(line)
        assertTrue(line, line.contains("Bearer REDACTED"))
    }

    @Test
    fun `a proxy credential header loses its value`() {
        // REQUIREMENTS.md "Connectivity and failures": the user's proxy headers are credentials in
        // their own right, and Cloudflare Access names one of them with no token-shaped word in it
        // beyond "Secret".
        val line = redactLogLine(
            "headers={CF-Access-Client-Secret: " + PROXY_SECRET + ", Accept: application/json}",
        )

        assertNoSecrets(line)
        assertTrue(line, line.contains("CF-Access-Client-Secret: REDACTED"))
        // The innocent header beside it survives, so the line is still worth reading.
        assertTrue(line, line.contains("Accept: application/json"))
    }

    @Test
    fun `a cookie header loses its value even though its name reads innocent`() {
        val line = redactLogLine("Cookie: dn_session=" + BEARER)

        assertNoSecrets(line)
        assertTrue(line, line.contains("Cookie: REDACTED"))
    }

    @Test
    fun `userinfo in a url is replaced whole`() {
        val line = redactLogLine("GET https://tim:" + APP_PASSWORD + "@music.yourhome.net/rest/ping")

        assertNoSecrets(line)
        assertTrue(line, line.contains("https://REDACTED@music.yourhome.net"))
    }

    // ---- what must survive, or the log is useless -----------------------------

    @Test
    fun `everything that is not a credential survives`() {
        val original = "GET https://music.yourhome.net:8443/rest/getAlbumList2?u=tim&c=Needler" +
            "&v=1.16.1&f=json&type=alphabeticalByArtist&size=500 -> 200 142051B in 88ms " +
            "Content-Type=application/json"

        val line = redactLogLine(original)

        assertEquals("nothing here is a credential, so nothing should change", original, line)
    }

    @Test
    fun `a port is not mistaken for a header value`() {
        val original = "GET https://music.yourhome.net:8443/rest/ping -> 200"

        assertEquals(original, redactLogLine(original))
    }

    // ---- the buffer redacts on ingest, not on render --------------------------

    @Test
    fun `a line written straight into the buffer is redacted before it is stored`() {
        val log = SessionDiagnosticsLog(clock = { 0L })

        // Exactly the mistake this guards against: a module that has never heard of redactUrl
        // reporting what it was doing when it failed.
        log.record(
            DiagnosticsSource.Sync,
            NetworkLogLevel.Error,
            "delta sync failed on https://music.yourhome.net/rest/getIndexes?apiKey=" +
                APP_PASSWORD + "&u=tim",
        )

        val stored = log.snapshot().single()
        assertNoSecrets(stored.message)
        assertNoSecrets(stored.render())
        assertNoSecrets(log.render())
    }

    @Test
    fun `a whole session of every line shape exports with no credential in it`() {
        val log = SessionDiagnosticsLog(clock = { 0L })

        log.log("GET /rest/ping?u=tim&apiKey=" + APP_PASSWORD + " -> 200")
        log.log(NetworkLogLevel.Warn, "GET /api/v1/auth/me Authorization: Bearer " + BEARER + " -> 401")
        log.record(DiagnosticsSource.Sync, NetworkLogLevel.Info, "delta sync: 12 albums, 3 removed")
        log.record(
            DiagnosticsSource.Playback,
            NetworkLogLevel.Error,
            "stream died: https://tim:" + APP_PASSWORD + "@music.yourhome.net/rest/stream?id=9",
        )
        log.record(
            DiagnosticsSource.App,
            NetworkLogLevel.Info,
            "app-password rejected, minting a replacement: POST /api/v1/connect-apps/app-passwords " +
                "Authorization: Bearer " + BEARER,
        )

        val exported = log.render(listOf("Needler 0.1.0 (10100)", "Android 16 (API 36)"))

        assertNoSecrets(exported)
        assertTrue(exported, exported.contains("Needler 0.1.0 (10100)"))
        assertTrue(exported, exported.contains("5 lines"))
    }

    // ---- bounds ---------------------------------------------------------------

    @Test
    fun `the buffer keeps the most recent lines and says how many it dropped`() {
        val log = SessionDiagnosticsLog(capacity = 3, clock = { 0L })

        repeat(10) { index -> log.log("line " + index) }

        assertEquals(3, log.size)
        assertEquals(7, log.droppedCount)
        assertEquals(
            listOf("line 7", "line 8", "line 9"),
            log.snapshot().map { it.message },
        )
        assertTrue(log.render().contains("7 older lines dropped"))
    }

    @Test
    fun `one pathological line cannot evict the session to hold itself`() {
        val log = SessionDiagnosticsLog(clock = { 0L })

        log.log("x".repeat(SessionDiagnosticsLog.MAX_MESSAGE_CHARS * 4))

        val stored = log.snapshot().single().message
        assertEquals(SessionDiagnosticsLog.MAX_MESSAGE_CHARS + 1, stored.length)
        assertTrue(stored.endsWith("…"))
    }

    @Test
    fun `clearing empties the buffer and forgets the dropped count`() {
        val log = SessionDiagnosticsLog(capacity = 2, clock = { 0L })
        repeat(5) { log.log("line") }

        log.clear()

        assertEquals(0, log.size)
        assertEquals(0, log.droppedCount)
        assertTrue(log.render().contains("Nothing was logged this session."))
    }

    @Test
    fun `a buffer with no room is refused rather than silently dropping everything`() {
        val failure = runCatching { SessionDiagnosticsLog(capacity = 0) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // ---- rendering ------------------------------------------------------------

    @Test
    fun `a line renders as a fixed set of columns`() {
        val log = SessionDiagnosticsLog(clock = { 1_790_157_600_000L })

        log.record(DiagnosticsSource.Playback, NetworkLogLevel.Warn, "decoder stalled")

        assertEquals("10:00:00.000  W  PLAY  decoder stalled", log.snapshot().single().render())
    }

    @Test
    fun `the clock column is utc time of day and pads every field`() {
        assertEquals("00:00:00.000", formatUtcTimeOfDay(0L))
        assertEquals("00:00:01.007", formatUtcTimeOfDay(1_007L))
        assertEquals("23:59:59.999", formatUtcTimeOfDay(86_399_999L))
        // Rolls over rather than growing an hours field, because it is a time of day and not a
        // duration since the epoch.
        assertEquals("00:00:00.000", formatUtcTimeOfDay(86_400_000L))
    }

    @Test
    fun `the export names the redaction rule in the file itself`() {
        // The file is what gets attached to an issue and read by someone who has no idea what was
        // taken out of it, so the explanation travels with it rather than living in this repository.
        val exported = SessionDiagnosticsLog(clock = { 0L }).render()

        assertTrue(exported, exported.contains("apiKey"))
        assertTrue(exported, exported.contains("REDACTED"))
        assertTrue(exported, exported.contains("never logged"))
    }

    // ---- the composite sink --------------------------------------------------

    @Test
    fun `composing two sinks writes every line to both, and composing with None costs nothing`() {
        val left = SessionDiagnosticsLog(clock = { 0L })
        val right = SessionDiagnosticsLog(clock = { 0L })

        val both: NetworkLogSink = left + right
        both.log(NetworkLogLevel.Info, "one line")

        assertEquals(1, left.size)
        assertEquals(1, right.size)
        assertTrue((left + NetworkLogSink.None) === left)
        assertTrue((NetworkLogSink.None + right) === right)
    }

    private fun assertNoSecrets(text: String) {
        assertFalse("the app-password survived: " + text, text.contains(APP_PASSWORD))
        assertFalse("the bearer survived: " + text, text.contains(BEARER))
        assertFalse("the proxy secret survived: " + text, text.contains(PROXY_SECRET))
    }

    private companion object {
        /** Shaped like a real app-password secret: long, opaque, no punctuation to hide behind. */
        const val APP_PASSWORD = "np7Qx2LmWf4tRzKd9Vb3Ys6Hc1Ju8Ge5"
        const val BEARER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dGltCg.s1gn4tur3"
        const val PROXY_SECRET = "cfsecret-8f2b41d9e6a7c05b3128"
    }
}
