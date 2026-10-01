package app.needler.wear.sync

import app.needler.wear.playback.WearPlaybackProtocol
import app.needler.wear.store.WearAudioKey
import app.needler.wear.store.WearStoreContents
import app.needler.wear.store.WearStoreSpace
import app.needler.wear.store.WearTrackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's half of the sync policy: what the user has asked for, which album the pipeline works on,
 * and what the on-watch screen therefore says.
 *
 * [WearSyncCoordinator] holds a `DataClient` and cannot be tested; every decision it makes is in the three
 * pure objects asserted here. Two of them would fail in ways a reader would not spot:
 * [WearActiveAlbum.of] picking an album the phone cannot fill stalls the pipeline for ever, and
 * [WearOnWatchState.of] hiding an album the phone has dropped would leave music on the watch that nothing
 * in the app admits to.
 */
class WearSyncPolicyTest {

    private val albumA: String = "a1b2c3d4-0000-4000-8000-00000000000a"
    private val albumB: String = "a1b2c3d4-0000-4000-8000-00000000000b"
    private val albumC: String = "a1b2c3d4-0000-4000-8000-00000000000c"

    // ---- the selection ---------------------------------------------------------------------------

    @Test
    fun `an album is appended rather than promoted`() {
        // Order is priority, and the pipeline fills the first incomplete album before starting the next.
        // Promoting a new selection would push the album that is halfway transferred down the queue, and
        // on a link this slow that means three thirds of three albums instead of one whole one.
        val selection: WearSyncSelection = WearSyncSelection.Empty.with(albumA).with(albumB)
        assertEquals(listOf(albumA, albumB), selection.wantedAlbums)
    }

    @Test
    fun `selecting the same album twice changes nothing`() {
        val once: WearSyncSelection = WearSyncSelection.Empty.with(albumA)
        assertEquals(once, once.with(albumA))
    }

    @Test
    fun `an unsafe album key is never selected`() {
        // The key names a cover file and is the prefix of every track path. It is untrusted text.
        assertEquals(WearSyncSelection.Empty, WearSyncSelection.Empty.with("../../etc"))
        assertEquals(WearSyncSelection.Empty, WearSyncSelection.Empty.with(""))
    }

    @Test
    fun `the selection is bounded`() {
        var selection: WearSyncSelection = WearSyncSelection.Empty
        for (index in 1..(WearPlaybackProtocol.MAX_WANTED_ALBUMS + 4)) {
            selection = selection.with("a1b2c3d4-0000-4000-8000-0000000000" + index.toString())
        }
        assertEquals(WearPlaybackProtocol.MAX_WANTED_ALBUMS, selection.wantedAlbums.size)
    }

    @Test
    fun `an over-full selection is returned unchanged so the screen can say so`() {
        var selection: WearSyncSelection = WearSyncSelection.Empty
        for (index in 1..WearPlaybackProtocol.MAX_WANTED_ALBUMS) {
            selection = selection.with("a1b2c3d4-0000-4000-8000-0000000000" + index.toString())
        }
        assertEquals(selection, selection.with(albumA))
    }

    @Test
    fun `deselecting removes only that album`() {
        val selection: WearSyncSelection =
            WearSyncSelection.Empty.with(albumA).with(albumB).without(albumA)
        assertEquals(listOf(albumB), selection.wantedAlbums)
    }

    @Test
    fun `the selection round-trips through its file form`() {
        val selection: WearSyncSelection = WearSyncSelection.Empty.with(albumA).with(albumB)
        assertEquals(selection, WearSyncSelection.decode(selection.encode()))
    }

    @Test
    fun `a damaged line in the persisted selection does not forfeit the rest`() {
        // The user's other selections are not lost because one line was damaged, and an unsafe key never
        // reaches the store however it got into the file.
        val decoded: WearSyncSelection = WearSyncSelection.decode(
            albumA + "\n../../etc\n\n" + albumB + "\n" + albumA + "\n",
        )
        assertEquals(listOf(albumA, albumB), decoded.wantedAlbums)
    }

    @Test
    fun `no persisted selection is empty, not a failure`() {
        assertEquals(WearSyncSelection.Empty, WearSyncSelection.decode(null))
        assertEquals(WearSyncSelection.Empty, WearSyncSelection.decode(""))
    }

    @Test
    fun `the wire form is de-duplicated and bounded`() {
        val selection = WearSyncSelection(wantedAlbums = List(40) { albumA })
        assertEquals(1, selection.forWire().size)
    }

    // ---- which album the pipeline works on --------------------------------------------------------

    @Test
    fun `the first incomplete album is chosen`() {
        val active: String = WearActiveAlbum.of(
            wanted = listOf(albumA, albumB),
            offeredTrackCounts = mapOf(albumA to 10, albumB to 12),
            heldCounts = mapOf(albumA to 10, albumB to 3),
            rotation = 0,
        )
        assertEquals(albumB, active)
    }

    @Test
    fun `an album the phone is not offering is skipped rather than chosen`() {
        // The failure this prevents: the phone can do nothing with an album it has unpinned, so choosing
        // it would stall the pipeline on an album that will never fill, with the next album never reached.
        val active: String = WearActiveAlbum.of(
            wanted = listOf(albumA, albumB),
            offeredTrackCounts = mapOf(albumB to 12),
            heldCounts = mapOf(albumA to 4, albumB to 0),
            rotation = 0,
        )
        assertEquals(albumB, active)
    }

    @Test
    fun `a full selection rotates so the staleness check reaches every album`() {
        // Only the phone can tell whether the server has replaced a file - REQUIREMENTS.md "Invalidating
        // upgraded files" - so the watch has to keep offering it albums to compare. One per session, no
        // timer anywhere.
        val offered: Map<String, Int> = mapOf(albumA to 10, albumB to 12, albumC to 8)
        val held: Map<String, Int> = mapOf(albumA to 10, albumB to 12, albumC to 8)
        val wanted: List<String> = listOf(albumA, albumB, albumC)
        assertEquals(albumA, WearActiveAlbum.of(wanted, offered, held, rotation = 0))
        assertEquals(albumB, WearActiveAlbum.of(wanted, offered, held, rotation = 1))
        assertEquals(albumC, WearActiveAlbum.of(wanted, offered, held, rotation = 2))
        assertEquals(albumA, WearActiveAlbum.of(wanted, offered, held, rotation = 3))
    }

    @Test
    fun `a lost rotation counter simply starts again`() {
        // Not persisted, because losing it costs one redundant comparison.
        val offered: Map<String, Int> = mapOf(albumA to 10)
        assertEquals(
            albumA,
            WearActiveAlbum.of(listOf(albumA), offered, mapOf(albumA to 10), rotation = -1),
        )
    }

    @Test
    fun `nothing wanted asks for nothing`() {
        assertEquals("", WearActiveAlbum.of(emptyList(), mapOf(albumA to 10), emptyMap(), 0))
    }

    @Test
    fun `nothing offered asks for nothing`() {
        // An empty answer is what stops the phone publishing. A watch that has never read an offer cannot
        // know whether an album is complete, and guessing would have it demand tracks the phone may not
        // have.
        assertEquals("", WearActiveAlbum.of(listOf(albumA, albumB), emptyMap(), emptyMap(), 0))
    }

    // ---- the on-watch screen ---------------------------------------------------------------------

    private fun stored(album: String, tracks: Int, bytes: Long = 30_000_000L): List<WearTrackRecord> =
        (1..tracks).map { number ->
            WearTrackRecord(
                key = WearAudioKey(album, 1, number),
                title = "Track " + number,
                artist = "The Marias",
                albumTitle = "Album " + album.takeLast(1),
                durationMs = 200_000L,
                format = "FLAC",
                sizeBytes = bytes,
                fingerprint = "4711|1|2|FLAC|1010",
            )
        }

    private fun offered(
        album: String,
        tracks: Int,
        bytes: Long = 300_000_000L,
    ): WearOfferedAlbum = WearOfferedAlbum(
        albumKey = album,
        title = "Album " + album.takeLast(1),
        artist = "The Marias",
        trackCount = tracks,
        sizeBytes = bytes,
    )

    private val roomySpace: WearStoreSpace =
        WearStoreSpace(usableBytes = 6_000_000_000L, totalBytes = 8_000_000_000L)

    private fun contents(records: List<WearTrackRecord>, space: WearStoreSpace = roomySpace) =
        WearStoreContents.of(records = records, space = space)

    @Test
    fun `an album on the watch and still offered reads as complete`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 10)),
            selection = WearSyncSelection.Empty.with(albumA),
            offer = WearOfferState.Offered(albums = listOf(offered(albumA, 10)), notShown = 0),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertTrue(row.onWatch)
        assertTrue(row.complete)
        assertFalse(row.transferring)
        assertTrue(row.refreshable)
        assertTrue(row.playable)
        assertFalse(row.addable)
    }

    @Test
    fun `a part-transferred wanted album reads as transferring`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 4)),
            selection = WearSyncSelection.Empty.with(albumA),
            offer = WearOfferState.Offered(albums = listOf(offered(albumA, 12)), notShown = 0),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertTrue(row.transferring)
        assertFalse(row.complete)
        // Playable already: REQUIREMENTS.md "Partial content is a normal state" - the tracks that arrived
        // are music, and a watch on a run should not be told to wait for the rest.
        assertTrue(row.playable)
        assertTrue(state.transferring)
    }

    @Test
    fun `an album the user stopped fetching reads as kept, not as failing`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 3)),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered(albums = listOf(offered(albumA, 12)), notShown = 0),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertTrue(row.partialAndStopped)
        assertFalse(row.transferring)
        assertTrue(row.playable)
    }

    @Test
    fun `an album the phone has dropped keeps its row and is marked unrefreshable`() {
        // The state that matters most here. Those bytes are still perfectly good music and the user must
        // still be able to remove them - but nothing can fill the gaps and, more importantly, nothing can
        // check whether the server has replaced them. Hiding the row would leave music on the watch that
        // nothing in the app admitted to.
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 6)),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered(albums = emptyList(), notShown = 0),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertFalse(row.refreshable)
        assertTrue(row.onWatch)
        assertTrue(row.playable)
        assertFalse(row.addable)
        assertEquals(0, row.offeredTracks)
    }

    @Test
    fun `an offered album not on the watch is addable when there is room`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(emptyList()),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered(
                albums = listOf(offered(albumA, 10, bytes = 300_000_000L)),
                notShown = 3,
            ),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertTrue(row.addable)
        assertFalse(row.playable)
        assertTrue(row.fits)
        assertEquals(3, state.notShown)
    }

    @Test
    fun `an album larger than the watch's headroom is not addable`() {
        // Said rather than shown as a dead control: the rule REQUIREMENTS.md sets for Cast is to "say why
        // in the output picker rather than failing after the user picks a speaker".
        val tight = WearStoreSpace(usableBytes = 700_000_000L, totalBytes = 8_000_000_000L)
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(emptyList(), space = tight),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered(
                albums = listOf(offered(albumA, 10, bytes = 2_400_000_000L)),
                notShown = 0,
            ),
        )
        val row: WearOnWatchRow = state.rows.single()
        assertFalse(row.fits)
        assertFalse(row.addable)
    }

    @Test
    fun `on-watch albums come before addable ones`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumB, 4)),
            selection = WearSyncSelection.Empty.with(albumB),
            offer = WearOfferState.Offered(
                albums = listOf(offered(albumA, 10), offered(albumB, 12)),
                notShown = 0,
            ),
        )
        assertEquals(listOf(albumB, albumA), state.rows.map { row -> row.albumKey })
        assertEquals(1, state.albumsOnWatch)
        assertEquals(4, state.tracksOnWatch)
    }

    @Test
    fun `an unreachable phone still lists what is on the watch`() {
        // The whole point of the feature: the watch plays what it holds with the phone switched off, so
        // the screen must list it. What it cannot do is offer anything new.
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 8)),
            selection = WearSyncSelection.Empty.with(albumA),
            offer = WearOfferState.PhoneUnreachable,
        )
        assertEquals(1, state.rows.size)
        assertTrue(state.rows.single().playable)
        assertFalse(state.rows.single().refreshable)
    }

    @Test
    fun `an offer that has not arrived does not accuse the phone of dropping an album`() {
        // Absence from a known offer means the phone has dropped the download. Absence from an offer that
        // has not come back means nothing, and flashing "not on phone" against perfectly refreshable
        // music is the kind of wrong information that makes a screen untrustworthy.
        val loading: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 8)),
            selection = WearSyncSelection.Empty.with(albumA),
            offer = WearOfferState.Loading,
        )
        assertTrue(loading.rows.single().refreshable)

        val known: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 8)),
            selection = WearSyncSelection.Empty.with(albumA),
            offer = WearOfferState.Offered.Nothing,
        )
        assertFalse(known.rows.single().refreshable)
    }

    @Test
    fun `an unscanned store draws nothing rather than an empty library`() {
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = WearStoreContents.NotScanned,
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Loading,
        )
        assertFalse(state.scanned)
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `usage and space are carried through for the header to print`() {
        // The bound has to be explicit and visible, which means these two figures reach the screen rather
        // than being implied by a greyed control.
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 2, bytes = 5_000_000L)),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered.Nothing,
        )
        assertEquals(10_000_000L, state.usedBytes)
        assertEquals(roomySpace, state.space)
        assertFalse(state.space.isUnknown)
    }

    @Test
    fun `the title on the watch wins over the phone's`() {
        // It came with the bytes, so it is readable with the phone out of range.
        val state: WearOnWatchState = WearOnWatchState.of(
            contents = contents(stored(albumA, 2)),
            selection = WearSyncSelection.Empty,
            offer = WearOfferState.Offered(
                albums = listOf(
                    WearOfferedAlbum(
                        albumKey = albumA,
                        title = "Phone title",
                        artist = "Phone artist",
                        trackCount = 2,
                        sizeBytes = 60_000_000L,
                    ),
                ),
                notShown = 0,
            ),
        )
        assertEquals("Album a", state.rows.single().title)
    }

    @Test
    fun `an offer can be asked for one album by key`() {
        val offer = WearOfferState.Offered(
            albums = listOf(offered(albumA, 10), offered(albumB, 12)),
            notShown = 0,
        )
        assertEquals(12, offer.album(albumB)?.trackCount)
        assertNull(offer.album(albumC))
    }
}
