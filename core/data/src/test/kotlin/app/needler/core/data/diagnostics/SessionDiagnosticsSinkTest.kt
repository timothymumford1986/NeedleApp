package app.needler.core.data.diagnostics

import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.network.DiagnosticsSource
import app.needler.core.network.NetworkLogLevel
import app.needler.core.network.SessionDiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the domain's diagnostics vocabulary and the buffer the user can read and share.
 *
 * Two things are worth a test here and nothing else is. The first is that the four severities survive
 * the crossing, because the whole reason the domain has its own enum is that it cannot name
 * `:core:network`'s, and a translation nobody checks is a translation that silently files every line as
 * `DEBUG`. The second is the one that matters for REQUIREMENTS.md "Security" rule 1 - "Credentials in
 * Keystore-backed storage, never in logs, analytics or crash reports" - namely that a line written
 * through this seam by code that never thought about credentials still arrives redacted.
 */
public class SessionDiagnosticsSinkTest {

    private val log = SessionDiagnosticsLog(clock = { FIXED_CLOCK })

    @Test
    public fun `each severity crosses the module boundary unchanged`() {
        val sink = SessionDiagnosticsSink(log = log, source = DiagnosticsSource.Playback)

        sink.record(DiagnosticsLevel.Debug, "d")
        sink.record(DiagnosticsLevel.Info, "i")
        sink.record(DiagnosticsLevel.Warn, "w")
        sink.record(DiagnosticsLevel.Error, "e")

        assertEquals(
            listOf(
                NetworkLogLevel.Debug,
                NetworkLogLevel.Info,
                NetworkLogLevel.Warn,
                NetworkLogLevel.Error,
            ),
            log.snapshot().map { it.level },
        )
    }

    @Test
    public fun `the source is fixed per instance, so one column tells a reader who wrote the line`() {
        SessionDiagnosticsSink(log = log, source = DiagnosticsSource.Playback)
            .record(DiagnosticsLevel.Warn, "not cached")
        SessionDiagnosticsSink(log = log, source = DiagnosticsSource.Sync)
            .record(DiagnosticsLevel.Info, "delta sync - nothing changed")

        assertEquals(
            listOf(DiagnosticsSource.Playback, DiagnosticsSource.Sync),
            log.snapshot().map { it.source },
        )
        // And it reaches the shared file, which is the artefact a bug report actually contains.
        assertTrue(log.snapshot().first().render().contains("PLAY"))
        assertTrue(log.snapshot().last().render().contains("SYNC"))
    }

    @Test
    public fun `a Subsonic app-password in a line written here is redacted on the way in`() {
        // The lane's credential travels as a query parameter, so any line that quotes a URL carries it.
        // Nothing at the call site redacts, deliberately: the buffer does it on ingest, which is what
        // makes it safe for `:core:data` to write a line assembled from whatever a failure handed it.
        SessionDiagnosticsSink(log = log, source = DiagnosticsSource.Sync).record(
            DiagnosticsLevel.Warn,
            "delta sync failed - https://music.example.net/rest/getIndexes" +
                "?u=tim&apiKey=hunter2&f=json",
        )

        assertEquals(
            "delta sync failed - https://music.example.net/rest/getIndexes" +
                "?u=tim&apiKey=REDACTED&f=json",
            log.snapshot().single().message,
        )
    }

    @Test
    public fun `a companion bearer in a line written here is redacted too`() {
        // The other lane's credential is a header, and an error message that quoted the failed request
        // is the way it would reach a log.
        SessionDiagnosticsSink(log = log, source = DiagnosticsSource.Sync).record(
            DiagnosticsLevel.Warn,
            "delta sync failed - Authorization: Bearer eyJhbGciOiJIUzI1NiJ9",
        )

        assertEquals(
            "delta sync failed - Authorization: REDACTED",
            log.snapshot().single().message,
        )
    }

    private companion object {
        private const val FIXED_CLOCK: Long = 1_700_000_000_000L
    }
}
