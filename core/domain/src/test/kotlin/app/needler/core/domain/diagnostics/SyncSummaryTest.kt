package app.needler.core.domain.diagnostics

import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "sync summaries" REQUIREMENTS.md "Observability" asks the diagnostics log to contain.
 *
 * The three lines here are the three shapes of the complaint that brings a user to this screen - "it is
 * not showing my new album" - and the point of asserting the whole string is that each must be
 * distinguishable from the others at a glance in a five-hundred-line file: the server said nothing
 * moved, the pass failed, or the pass ran and changed nothing.
 */
class SyncSummaryTest {

    @Test
    fun `an unchanged library is one line and stays at Debug`() {
        // The cheap case REQUIREMENTS.md writes a performance budget for: one request. It happens every
        // fifteen minutes in the foreground, so it must not be the line that pushes a failure out of a
        // bounded buffer.
        val report = SyncReport(
            phase = SyncPhase.DELTA,
            libraryUnchanged = true,
            newRevision = "1737052800000",
        )

        assertEquals(DiagnosticsLevel.Debug, SyncSummary.levelOf(report))
        assertEquals(
            "delta sync - library unchanged, revision 1737052800000 (112 ms)",
            SyncSummary.line(report, elapsedMillis = 112L),
        )
    }

    @Test
    fun `a delta that changed things names the counts and drops the zeroes`() {
        val report = SyncReport(
            phase = SyncPhase.DELTA,
            artistsUpdated = 3,
            albumsUpdated = 1,
            tracksUpdated = 12,
            staleTracksEvicted = listOf(
                TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 4),
            ),
            newRevision = "1737052800000",
        )

        assertEquals(DiagnosticsLevel.Info, SyncSummary.levelOf(report))
        assertEquals(
            "delta sync - 3 artists, 1 album, 12 tracks, 1 stale track evicted, " +
                "revision 1737052800000 (2.4 s)",
            SyncSummary.line(report, elapsedMillis = 2_351L),
        )
    }

    @Test
    fun `a pass that ran against a moved revision and found nothing says so`() {
        // Different from "library unchanged", and the difference is the whole diagnosis: the server
        // reported movement and the app found no work in it.
        val report = SyncReport(phase = SyncPhase.DELTA, newRevision = "1737052800000")

        assertEquals(
            "delta sync - nothing changed, revision 1737052800000 (310 ms)",
            SyncSummary.line(report, elapsedMillis = 310L),
        )
    }

    @Test
    fun `a full sync names which of the three reasons it was`() {
        // A full sync is one request per artist plus one per album. Seeing one in a log without knowing
        // why is the difference between "first connect" and "something is re-reading the library every
        // launch".
        val report = SyncReport(
            phase = SyncPhase.FULL,
            artistsUpdated = 41,
            albumsUpdated = 210,
            tracksUpdated = 2_803,
            newRevision = "1737052800000",
        )

        assertEquals(
            "full sync (FIRST_CONNECT) - 41 artists, 210 albums, 2803 tracks, " +
                "revision 1737052800000 (3m 12s)",
            SyncSummary.line(report, elapsedMillis = 192_400L, reason = FullSyncReason.FIRST_CONNECT),
        )
    }

    @Test
    fun `a server that never gave a revision leaves the clause out rather than printing null`() {
        val report = SyncReport(phase = SyncPhase.DELTA, albumsUpdated = 2, tracksUpdated = 20)

        assertEquals(
            "delta sync - 2 albums, 20 tracks (1.4 s)",
            SyncSummary.line(report, elapsedMillis = 1_400L),
        )
    }

    @Test
    fun `a failure carries the error's own diagnostic and nothing else`() {
        // NeedlerError.diagnostic is documented as "a short, non-localised description for the
        // diagnostics log", so this line does not re-describe the failure and cannot drift from it.
        assertEquals(
            "delta sync failed - Offline: TIMEOUT (310 ms)",
            SyncSummary.failureLine(
                phase = SyncPhase.DELTA,
                error = NeedlerError.Offline(OfflineCause.TIMEOUT),
                elapsedMillis = 310L,
            ),
        )
    }

    @Test
    fun `a failed full sync still names its reason`() {
        assertEquals(
            "full sync (USER_REQUESTED) failed - Offline: NO_NETWORK (4.0 s)",
            SyncSummary.failureLine(
                phase = SyncPhase.FULL,
                error = NeedlerError.Offline(),
                elapsedMillis = 4_000L,
                reason = FullSyncReason.USER_REQUESTED,
            ),
        )
    }

    private companion object {
        private const val RG: String = "d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"
    }
}
