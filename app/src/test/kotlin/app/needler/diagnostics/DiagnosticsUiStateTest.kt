package app.needler.diagnostics

import app.needler.core.network.DiagnosticsSource
import app.needler.core.network.NetworkLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the Diagnostics screen that are logic rather than layout.
 *
 * The redaction — the load-bearing half of the whole feature — is tested where it lives, in
 * `:core:network`'s `SessionDiagnosticsLogTest`, because that is where the rule is applied: every line
 * is redacted on ingest, before this module ever sees one. What is left to test here is the
 * presentation, and two of those decisions are worth a regression test each:
 *
 *  * **severity is a word, not a colour**, so a `WARN` or `ERROR` line has a badge and not only a
 *    different text colour. The palette has one alarm colour, `#e8908a`, and REQUIREMENTS.md
 *    "Design pack discrepancies" added it for permanent data loss specifically; spending it on a
 *    failed HTTP request would make it mean nothing by the time it was needed. The badge is also what
 *    makes the information reachable without colour vision at all.
 *  * **the dropped-lines notice**, because a log that silently begins in the middle of a session
 *    invites the reader to conclude nothing happened before its first line.
 */
class DiagnosticsUiStateTest {

    // ---- severity is a word, not a colour ------------------------------------

    @Test
    fun `an ordinary line carries no badge and is drawn muted`() {
        val line = line(level = NetworkLogLevel.Debug, message = "GET /rest/ping -> 200")

        assertNull(line.badge)
        assertFalse(line.emphasised)
    }

    @Test
    fun `an info line is no louder than a debug line`() {
        // Info exists so a finished download appears in a bug report, not so it shouts. Raising it
        // would make two thirds of a session emphasised, which is the same as emphasising nothing.
        val line = line(level = NetworkLogLevel.Info, message = "download finished, 41.2 MB retained")

        assertNull(line.badge)
        assertFalse(line.emphasised)
    }

    @Test
    fun `a warning says WARN and a failure says ERROR, in words`() {
        val warning = line(level = NetworkLogLevel.Warn, message = "GET /api/v1/auth/me -> 401")
        val failure = line(level = NetworkLogLevel.Error, message = "GET /rest/stream FAILED")

        assertEquals("WARN", warning.badge)
        assertEquals("ERROR", failure.badge)
        assertTrue(warning.emphasised)
        assertTrue(failure.emphasised)
    }

    @Test
    fun `the source column uses the buffer's own four-character tag`() {
        assertEquals("NET", line(source = DiagnosticsSource.Network).source)
        assertEquals("SYNC", line(source = DiagnosticsSource.Sync).source)
        assertEquals("PLAY", line(source = DiagnosticsSource.Playback).source)
        assertEquals("APP", line(source = DiagnosticsSource.App).source)
    }

    // ---- what a screen reader hears -------------------------------------------

    @Test
    fun `a screen reader hears the failure first and the timestamp last`() {
        // TalkBack reads a node from the beginning every time focus lands on it, so a log line that
        // starts with twelve characters of clock is a log line nobody hears the end of.
        val spoken = line(
            level = NetworkLogLevel.Error,
            source = DiagnosticsSource.Playback,
            clock = "14:03:11.204",
            message = "stream died",
        ).spoken

        assertEquals("error, playback: stream died, at 14:03:11.204", spoken)
    }

    @Test
    fun `an ordinary line is spoken without a severity`() {
        val spoken = line(
            level = NetworkLogLevel.Debug,
            source = DiagnosticsSource.Network,
            clock = "09:00:00.000",
            message = "GET /rest/ping -> 200",
        ).spoken

        assertEquals("network: GET /rest/ping -> 200, at 09:00:00.000", spoken)
    }

    @Test
    fun `identical lines get different keys so the lazy list does not confuse them`() {
        // A log is full of repeated lines - the same poll, every thirty seconds - so the message
        // cannot be the identity.
        val first = line(index = 0, clock = "09:00:00.000", message = "GET /rest/ping -> 200")
        val second = line(index = 1, clock = "09:00:00.000", message = "GET /rest/ping -> 200")

        assertFalse(first.key == second.key)
    }

    // ---- the summary line ------------------------------------------------------

    @Test
    fun `an empty log says so rather than showing a count of nothing`() {
        val state = DiagnosticsUiState()

        assertTrue(state.isEmpty)
        assertFalse(state.hasContent)
        assertEquals("Nothing has been logged yet this session.", state.summary)
    }

    @Test
    fun `a full log reports its size`() {
        val state = DiagnosticsUiState(lines = listOf(line(), line(index = 1)))

        assertEquals("2 lines from this session.", state.summary)
    }

    @Test
    fun `one line is one line, not one lines`() {
        val state = DiagnosticsUiState(lines = listOf(line()))

        assertEquals("1 line from this session.", state.summary)
    }

    @Test
    fun `a truncated log says what it lost and what the limit is`() {
        val state = DiagnosticsUiState(
            lines = listOf(line(), line(index = 1)),
            droppedCount = 7,
            capacity = 500,
        )

        assertEquals(
            "2 lines from this session, and 7 lines dropped from the start of it: the log keeps " +
                "the most recent 500 and no more.",
            state.summary,
        )
    }

    @Test
    fun `sharing and clearing are offered only when there is something to share or clear`() {
        assertFalse(DiagnosticsUiState().hasContent)
        assertTrue(DiagnosticsUiState(lines = listOf(line())).hasContent)
    }

    // ---- the exported file's header ---------------------------------------------

    @Test
    fun `the export header answers the three questions a bug report is triaged on`() {
        val header = DiagnosticsExport.header(
            versionName = "0.1.0",
            versionCode = 10_100L,
            androidRelease = "16",
            sdkInt = 36,
            manufacturer = "Google",
            model = "Pixel 8",
            exportedAt = "2026-09-24T14:03:11.204Z",
        )

        assertEquals(
            listOf(
                "Exported 2026-09-24T14:03:11.204Z",
                "Needler 0.1.0 (10100)",
                "Android 16 (API 36)",
                "Google Pixel 8",
            ),
            header,
        )
    }

    @Test
    fun `the export header carries no server address, account or library content`() {
        // The header is the one part of the exported file a person writes by hand, which makes it the
        // easiest place to add a leak by accident. REQUIREMENTS.md "Security" rule 1 and rule 4 are
        // both about exactly this file.
        val header = DiagnosticsExport.header(
            versionName = "0.1.0",
            versionCode = 10_100L,
            androidRelease = "16",
            sdkInt = 36,
            manufacturer = "Google",
            model = "Pixel 8",
            exportedAt = "2026-09-24T14:03:11.204Z",
        ).joinToString(" ")

        assertFalse(header.contains("://"))
        assertFalse(header.lowercase().contains("user"))
        assertFalse(header.lowercase().contains("token"))
        assertFalse(header.lowercase().contains("password"))
    }

    @Test
    fun `an unreadable package still produces a header rather than no export`() {
        // PackageManager cannot fail for an app asking about itself, but it declares that it can, and
        // losing the diagnostics log over the version string would be an absurd trade.
        val header = DiagnosticsExport.header(
            versionName = "",
            versionCode = 0L,
            androidRelease = "16",
            sdkInt = 36,
            manufacturer = "Google",
            model = "Pixel 8",
            exportedAt = "2026-09-24T14:03:11.204Z",
        )

        assertEquals("Needler unknown (0)", header[1])
    }

    @Test
    fun `the export directory matches the one the FileProvider exposes`() {
        // app/src/main/res/xml/diagnostics_paths.xml exposes exactly `<cacheDir>/diagnostics/` and
        // nothing else, deliberately: a provider rooted at the cache would hand any consumer a way to
        // read cached music by guessing a path. A mismatch here is a crash inside
        // FileProvider.getUriForFile at the moment the user taps share.
        assertEquals("diagnostics", DiagnosticsExport.DIRECTORY_NAME)
        assertEquals("needler-diagnostics.txt", DiagnosticsExport.FILE_NAME)
        assertEquals("text/plain", DiagnosticsExport.MIME_TYPE)
    }

    private fun line(
        index: Int = 0,
        clock: String = "09:00:00.000",
        level: NetworkLogLevel = NetworkLogLevel.Debug,
        source: DiagnosticsSource = DiagnosticsSource.Network,
        message: String = "GET /rest/ping -> 200",
    ): DiagnosticsLine = diagnosticsLine(
        index = index,
        clock = clock,
        level = level,
        source = source,
        message = message,
    )
}
