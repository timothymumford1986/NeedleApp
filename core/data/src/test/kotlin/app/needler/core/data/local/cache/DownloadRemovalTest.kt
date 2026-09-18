package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.projection.EvictionCandidateRow
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing a download: what "remove from device" has to give back.
 *
 * Unpinning used to demote the album's rows to the cached tier and leave every byte on disk. With no
 * storage limit left in the app, removing a download is the **only** lever the user has on a full
 * device, so a removal that frees nothing now - and leaves the Storage figure exactly where it was -
 * breaks the workflow the feature exists for.
 *
 * These tests fix the accounting half of the contract, which is the half that can be tested off a
 * device: the rows are read before they are deleted, every file path comes back for the caller to
 * unlink after the transaction, and the bytes are summed so the UI can say what was freed. The
 * transaction itself lives in `NeedlerDatabase.removeDownloadedAlbum`, which needs Room.
 */
public class DownloadRemovalTest {

    private val megabyte: Long = 1_048_576L

    private fun row(disc: Int, track: Int, sizeBytes: Long): EvictionCandidateRow =
        EvictionCandidateRow(
            key = TrackKeyDb(releaseGroupMbid = ALBUM, discNo = disc, trackNo = track),
            filePath = "/data/audio/" + ALBUM + "/" + disc + "-" + track + ".flac",
            sizeBytes = sizeBytes,
            lastPlayedAt = 0L,
            downloadedAt = 1_000L,
        )

    @Test
    public fun `removing a download reports the bytes it freed`() {
        val removed: RemovedAudio = RemovedAudio.of(
            listOf(
                row(disc = 1, track = 1, sizeBytes = 40 * megabyte),
                row(disc = 1, track = 2, sizeBytes = 35 * megabyte),
                row(disc = 1, track = 3, sizeBytes = 25 * megabyte),
            ),
        )

        // The figure is what the Storage screen listed against the album a moment ago, so the user
        // can see the removal did what they asked.
        assertEquals(100 * megabyte, removed.bytes)
        assertEquals(3, removed.trackCount)
        assertFalse(removed.isEmpty)
    }

    @Test
    public fun `removing a download returns every file path for the caller to unlink`() {
        val rows: List<EvictionCandidateRow> = listOf(
            row(disc = 1, track = 1, sizeBytes = megabyte),
            row(disc = 2, track = 1, sizeBytes = megabyte),
        )

        val removed: RemovedAudio = RemovedAudio.of(rows)

        // File deletion cannot happen inside a Room transaction, so the paths come out with the
        // result - exactly as clearCachedAudio, clearForServerChange and clearAllAudio do it. A path
        // missed here is an orphaned file that only an uninstall reclaims.
        assertEquals(rows.map { it.filePath }, removed.filePaths)
        assertTrue(removed.filePaths.all { it.contains(ALBUM) })
    }

    @Test
    public fun `an album with nothing on disk removes nothing and reports zero`() {
        // Unpinning a download that never landed is a normal thing to do: the pin row still goes,
        // and reporting zero freed is a success, not a failure.
        val removed: RemovedAudio = RemovedAudio.of(emptyList())

        assertEquals(RemovedAudio.Empty, removed)
        assertTrue(removed.isEmpty)
        assertEquals(0L, removed.bytes)
        assertEquals(0, removed.trackCount)
    }

    @Test
    public fun `the whole album goes, whichever tier its rows were in`() {
        // The query behind this does not filter on `pinned`. To the user an album is on the device
        // or it is not, and leaving behind whatever happened to be cached from streaming it would
        // free less than the screen just promised.
        val removed: RemovedAudio = RemovedAudio.of(
            listOf(
                row(disc = 1, track = 1, sizeBytes = 10 * megabyte),
                row(disc = 1, track = 2, sizeBytes = 10 * megabyte),
            ),
        )

        assertEquals(20 * megabyte, removed.bytes)
    }

    // ---------------------------------------------------------------- what the UI is told

    @Test
    public fun `the domain result carries what the UI needs to say how much was freed`() {
        val removed: RemovedAudio = RemovedAudio.of(
            listOf(
                row(disc = 1, track = 1, sizeBytes = 60 * megabyte),
                row(disc = 1, track = 2, sizeBytes = 60 * megabyte),
            ),
        )

        val reported = RemovedDownload(
            releaseGroupMbid = ReleaseGroupMbid(ALBUM),
            removedTracks = removed.trackCount,
            freedBytes = removed.bytes,
        )

        assertEquals(2, reported.removedTracks)
        assertEquals(120 * megabyte, reported.freedBytes)
        assertEquals(ALBUM, reported.releaseGroupMbid.value)
    }

    @Test
    public fun `a pin whose download never landed reports an empty removal`() {
        val mbid = ReleaseGroupMbid(ALBUM)

        assertEquals(
            RemovedDownload(releaseGroupMbid = mbid, removedTracks = 0, freedBytes = 0L),
            RemovedDownload.nothing(mbid),
        )
    }

    private companion object {
        private const val ALBUM: String = "5f2c1b34-0000-4000-8000-0000000000aa"
    }
}
