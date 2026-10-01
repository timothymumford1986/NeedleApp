package app.needler.wear

import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone actually puts on the wire, for the parts of it that can be wrong.
 *
 * [WearStatePublisher] itself cannot be tested on the JVM - it holds a `DataClient`, which wants Google
 * Play services, a paired watch and a device - which is exactly why every decision was pushed into
 * [WearSnapshots]. What is left in the publisher is key names and a `putDataItem`; what is here is the
 * windowing and the index arithmetic, and a mistake in either plays the wrong track on somebody's
 * wrist and looks like a Bluetooth glitch.
 *
 * The fixtures are the design pack's own record, as `:widget`'s and `:feature:player`'s are: a test
 * source set is not shared across modules any more than a main one is.
 */
class WearSnapshotTest {

    // ---- now playing ---------------------------------------------------------------------------

    @Test
    fun `nothing loaded is published as nothing loaded`() {
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(PlaybackState.Idle)
        assertFalse(snapshot.hasItem)
        assertEquals("", snapshot.title)
        assertNull(snapshot.artworkId)
        // Not a deleted data item: the watch reads hasItem = false as Idle, and one publish path is
        // easier to keep honest than a publish path and a delete path.
        assertEquals(WearNowPlaying.NothingLoaded, snapshot)
    }

    @Test
    fun `a playing track carries the three lines and the transport`() {
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(
            PlaybackState(currentItem = sienna, isPlaying = true, isBuffering = false),
        )
        assertTrue(snapshot.hasItem)
        assertEquals("Sienna", snapshot.title)
        assertEquals("The Marias", snapshot.artist)
        assertEquals("Submarine", snapshot.album)
        assertTrue(snapshot.isPlaying)
        assertFalse(snapshot.isBuffering)
    }

    @Test
    fun `buffering is carried separately from playing`() {
        // The watch dims the primary button rather than flipping the glyph, which it can only do if
        // these two arrive as two booleans. Collapsing them would make every mid-stream stall look
        // like a pause.
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(
            PlaybackState(currentItem = sienna, isPlaying = true, isBuffering = true),
        )
        assertTrue(snapshot.isPlaying)
        assertTrue(snapshot.isBuffering)
    }

    @Test
    fun `a track with no album title sends no album`() {
        // The server does describe tracks with no album. The watch treats a blank as "no third line",
        // and a blank must not reach it as the string "null" or an empty row of its own.
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(
            PlaybackState(currentItem = queueItem(rowId = "9@x/1/1", album = null)),
        )
        assertNull(snapshot.album)
    }

    @Test
    fun `a blank album title is the same as none`() {
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(
            PlaybackState(currentItem = queueItem(rowId = "9@x/1/1", album = "   ")),
        )
        assertNull(snapshot.album)
    }

    @Test
    fun `artwork is identified by the release group, so the watch fetches one cover per album`() {
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(PlaybackState(currentItem = sienna))
        assertEquals("marias-submarine", snapshot.artworkId)
        assertEquals(ArtworkRef.Owned("al-marias-submarine"), snapshot.artwork)
    }

    @Test
    fun `a track with no artwork sends no artwork id either`() {
        // An id with no asset behind it would have the watch cache a decoded cover under a key it
        // never receives bytes for.
        val snapshot: WearNowPlaying = WearSnapshots.nowPlaying(
            PlaybackState(currentItem = queueItem(rowId = "9@x/1/1", artwork = null)),
        )
        assertNull(snapshot.artworkId)
        assertNull(snapshot.artwork)
    }

    @Test
    fun `two tracks from one album share an artwork id`() {
        // This is the whole point of using the release group: the asset crosses the link once per
        // album, not once per track.
        val first: WearNowPlaying = WearSnapshots.nowPlaying(PlaybackState(currentItem = sienna))
        val second: WearNowPlaying = WearSnapshots.nowPlaying(PlaybackState(currentItem = hush))
        assertEquals(first.artworkId, second.artworkId)
    }

    // ---- the crate -----------------------------------------------------------------------------

    @Test
    fun `an empty crate publishes nothing playing and no rows`() {
        val window: WearCrateWindow = WearSnapshots.crateWindow(PlayQueue.Empty)
        assertTrue(window.rows.isEmpty())
        assertEquals(WearPlaybackProtocol.NO_CURRENT_ROW, window.currentIndexInWindow)
        assertEquals(0, window.total)
    }

    @Test
    fun `the window starts at the playing row`() {
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(10), currentIndex = 4),
            maxRows = 3,
        )
        assertEquals(listOf("row-4", "row-5", "row-6"), window.rows.map { it.id })
        assertEquals(4, window.windowStart)
        assertEquals(10, window.total)
    }

    @Test
    fun `the playing row is index zero of the window, not of the crate`() {
        // The watch reads this key rather than assuming zero, so that the window rule can change
        // without a protocol change - but what is sent today has to be the window's own index. Sending
        // 4 here would make the watch highlight the fifth published row, which is a different track.
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(10), currentIndex = 4),
            maxRows = 3,
        )
        assertEquals(0, window.currentIndexInWindow)
    }

    @Test
    fun `a crate longer than the window is truncated, and says how long it really is`() {
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(300), currentIndex = 0),
        )
        assertEquals(WearPlaybackProtocol.MAX_CRATE_ROWS, window.rows.size)
        assertEquals(300, window.total)
        assertEquals(0, window.windowStart)
    }

    @Test
    fun `a window that runs off the end of the crate stops there`() {
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(5), currentIndex = 3),
            maxRows = 10,
        )
        assertEquals(listOf("row-3", "row-4"), window.rows.map { it.id })
        assertEquals(3, window.windowStart)
        assertEquals(5, window.total)
    }

    @Test
    fun `a crate with nothing playing publishes its top`() {
        // A restored crate before the first play. Publishing nothing would render as an empty crate,
        // which is a lie about a crate with rows in it.
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(4), currentIndex = null),
            maxRows = 2,
        )
        assertEquals(listOf("row-0", "row-1"), window.rows.map { it.id })
        assertEquals(WearPlaybackProtocol.NO_CURRENT_ROW, window.currentIndexInWindow)
        assertEquals(0, window.windowStart)
    }

    @Test
    fun `a current index outside the crate is treated as nothing playing`() {
        // PlayQueue allows it - a skip and a crate edit can land in either order - and it must not
        // throw inside a publisher that runs on a background dispatcher.
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = crateOf(3), currentIndex = 9),
            maxRows = 2,
        )
        assertEquals(WearPlaybackProtocol.NO_CURRENT_ROW, window.currentIndexInWindow)
        assertEquals(listOf("row-0", "row-1"), window.rows.map { it.id })
    }

    @Test
    fun `rows carry the queue row id, not the track id`() {
        // PATH_SKIP_TO_ROW maps to PlaybackController.skipToQueueItem, which addresses rows by
        // QueueItem.id. The same track can sit in the crate twice, and sending a track id would make
        // tapping the second copy play the first.
        val twice: List<QueueItem> = listOf(
            queueItem(rowId = "1@marias-submarine/1/1"),
            queueItem(rowId = "2@marias-submarine/1/1"),
        )
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = twice, currentIndex = 0),
        )
        assertEquals(
            listOf("1@marias-submarine/1/1", "2@marias-submarine/1/1"),
            window.rows.map { it.id },
        )
    }

    @Test
    fun `rows carry the two strings a watch draws`() {
        val window: WearCrateWindow = WearSnapshots.crateWindow(
            PlayQueue(items = listOf(sienna), currentIndex = 0),
        )
        assertEquals("Sienna", window.rows[0].title)
        assertEquals("The Marias", window.rows[0].artist)
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private val sienna: QueueItem = queueItem(
        rowId = "1@marias-submarine/1/1",
        title = "Sienna",
        trackNumber = 1,
    )

    private val hush: QueueItem = queueItem(
        rowId = "2@marias-submarine/1/2",
        title = "Hush",
        trackNumber = 2,
    )

    private fun crateOf(size: Int): List<QueueItem> = (0 until size).map { index ->
        queueItem(rowId = "row-" + index, trackNumber = index + 1)
    }

    private fun queueItem(
        rowId: String,
        title: String = "Sienna",
        album: String? = "Submarine",
        trackNumber: Int = 1,
        artwork: ArtworkRef? = ArtworkRef.Owned("al-marias-submarine"),
    ): QueueItem {
        val releaseGroup = ReleaseGroupMbid("marias-submarine")
        return QueueItem(
            id = rowId,
            track = Track(
                key = TrackKey(
                    releaseGroupMbid = releaseGroup,
                    discNumber = 1,
                    trackNumber = trackNumber,
                ),
                title = title,
                artistName = "The Marias",
                albumTitle = album,
                durationMs = 200_000L,
                fetch = TrackFetchHandle(
                    fileId = FileId(releaseGroup.value + "-" + trackNumber),
                    sizeBytes = null,
                    durationMs = 200_000L,
                    format = AudioFormat.FLAC,
                    bitrateKbps = null,
                ),
                artwork = artwork,
            ),
        )
    }
}
