// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards. See SettingsUiStateTest for the same note.
@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.repository.DownloadedAlbumOrder
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Downloaded albums screen's behaviour, against the domain interface.
 *
 * The point of these is that a tap on a remove control reaches the repository that deletes bytes, and
 * that what comes back is reported honestly. REQUIREMENTS.md "Storage, and why there is no budget"
 * leaves no storage limit in the product, so this is the user's only lever on a full device: "a
 * 'remove' that leaves the usage figure unchanged is the one thing that would make this whole screen
 * untrustworthy".
 */
class DownloadsViewModelTest {

    private val pins = FakePinRepository(albums = THREE_ALBUMS)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- the list ------------------------------------------------------------

    @Test
    fun `the screen asks for the albums largest first`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        assertEquals(listOf(DownloadedAlbumOrder.LARGEST_FIRST), pins.ordersRequested)
    }

    @Test
    fun `every downloaded album reaches the screen, in size order`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        val state: DownloadsUiState = viewModel.state.value
        assertFalse("the list has arrived, so the screen is no longer loading", state.loading)
        assertEquals(
            listOf("Submarine", "Con Todo El Mundo", "Fetch the Bolt Cutters"),
            state.albums.map { it.title },
        )
    }

    @Test
    fun `an empty device is reported as empty rather than as still loading`() = runTest {
        val viewModel = DownloadsViewModel(FakePinRepository(albums = emptyList()))
        subscribe(viewModel)

        assertTrue(viewModel.state.value.isEmpty)
        // No header figure: the empty state says it once, in words, at heading size.
        assertEquals("", viewModel.state.value.summary)
    }

    // ---- removal -------------------------------------------------------------

    @Test
    fun `removing an album reaches the repository with that album's mbid`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[1])

        assertEquals(listOf(THREE_ALBUMS[1].releaseGroupMbid), pins.unpinned)
    }

    @Test
    fun `a removal reports what it actually freed, naming the album`() = runTest {
        pins.unpinOutcome = Outcome.Success(
            RemovedDownload(
                releaseGroupMbid = THREE_ALBUMS[0].releaseGroupMbid,
                removedTracks = 12,
                freedBytes = 1_181_116_006L,
            ),
        )
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])

        val notice: DownloadsNotice? = viewModel.state.value.notice
        assertEquals("Removed Submarine", notice?.headline)
        assertEquals("12 tracks deleted, 1.1 GB freed.", notice?.detail)
    }

    @Test
    fun `a removal that deleted bytes offers to put the album back, and is marked destructive`() =
        runTest {
            // The one red thing on the screen, and the only way back from an action the screen's own
            // copy calls irreversible.
            val viewModel = DownloadsViewModel(pins)
            subscribe(viewModel)

            viewModel.onRemove(THREE_ALBUMS[0])

            val notice: DownloadsNotice? = viewModel.state.value.notice
            assertEquals(THREE_ALBUMS[0], notice?.undo)
            assertTrue("bytes left the device, so this is the destructive notice", notice!!.destructive)
        }

    @Test
    fun `the removed album leaves the list`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])

        assertEquals(
            listOf("Con Todo El Mundo", "Fetch the Bolt Cutters"),
            viewModel.state.value.albums.map { it.title },
        )
    }

    @Test
    fun `an album whose download never landed is a success, not a failure`() = runTest {
        // REQUIREMENTS.md: "A pin whose download never landed removes nothing and reports zero,
        // which is a success, not an error." The row still had to go, or the downloader fetches it
        // again.
        pins.unpinOutcome =
            Outcome.Success(RemovedDownload.nothing(THREE_ALBUMS[2].releaseGroupMbid))
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[2])

        val notice: DownloadsNotice? = viewModel.state.value.notice
        assertEquals("Removed Fetch the Bolt Cutters", notice?.headline)
        assertEquals("None of it had reached this device, so nothing was freed.", notice?.detail)
        // Nothing was lost, so this one is not drawn in the destructive colour. That is what keeps
        // the colour meaning "bytes are gone" rather than "something happened".
        assertFalse(notice!!.destructive)
    }

    @Test
    fun `a removal that failed says which album and why, and keeps the row`() = runTest {
        pins.unpinOutcome = Outcome.Failure(NeedlerError.SessionExpired)
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])

        val notice: DownloadsNotice? = viewModel.state.value.notice
        assertEquals("Could not remove Submarine", notice?.headline)
        assertEquals(
            "Your sign-in has expired. Sign in again to restore search and pulls.",
            notice?.detail,
        )
        // Nothing to undo: the album never left.
        assertEquals(null, notice?.undo)
        assertEquals(3, viewModel.state.value.albums.size)
    }

    @Test
    fun `a second tap during a removal is dropped rather than queued`() = runTest {
        // Two overlapping removals finish in either order and each overwrites the other's notice, so
        // the user is told about one of the two deletions and left to guess at the other.
        val gate = CompletableDeferred<Unit>()
        pins.unpinGate = gate
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])
        assertEquals(
            "the row in flight is named, not just flagged",
            THREE_ALBUMS[0].releaseGroupMbid,
            viewModel.state.value.removing,
        )
        assertEquals(
            DownloadedRowState.Removing,
            viewModel.state.value.rowState(THREE_ALBUMS[0]),
        )
        assertEquals(
            "the rows that are not going are not marked as going",
            DownloadedRowState.Idle,
            viewModel.state.value.rowState(THREE_ALBUMS[1]),
        )
        assertFalse("remove controls stop responding while one is running", viewModel.state.value.canRemove)

        viewModel.onRemove(THREE_ALBUMS[1])
        assertEquals(
            "only the first album reached the repository",
            listOf(THREE_ALBUMS[0].releaseGroupMbid),
            pins.unpinned,
        )

        gate.complete(Unit)
        assertEquals(null, viewModel.state.value.removing)
    }

    // ---- undo ----------------------------------------------------------------

    @Test
    fun `undo re-pins the album the notice names, and the row comes back`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])
        val undo: DownloadedAlbum = viewModel.state.value.notice!!.undo!!
        viewModel.onUndo(undo)

        assertEquals(listOf(THREE_ALBUMS[0].releaseGroupMbid), pins.pinned)
        assertTrue(
            "the album is back on the list",
            viewModel.state.value.albums.any { it.title == "Submarine" },
        )
    }

    @Test
    fun `a finished undo says the server is sending it again, and offers nothing further`() =
        runTest {
            // Never "restored". The bytes were deleted when the removal reported what it freed, and
            // what comes back comes over the network - which is what the screen's own explainer says
            // is the only way back.
            val viewModel = DownloadsViewModel(pins)
            subscribe(viewModel)

            viewModel.onRemove(THREE_ALBUMS[0])
            viewModel.onUndo(viewModel.state.value.notice!!.undo!!)

            val notice: DownloadsNotice? = viewModel.state.value.notice
            assertEquals("Submarine is back on the list", notice?.headline)
            assertEquals("The server is sending it to this device again.", notice?.detail)
            assertEquals(null, notice?.undo)
            assertFalse(notice!!.destructive)
        }

    @Test
    fun `an undo that failed says why and keeps offering itself`() = runTest {
        pins.pinOutcome = Outcome.Failure(NeedlerError.DownloadForbidden)
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])
        val undo: DownloadedAlbum = viewModel.state.value.notice!!.undo!!
        viewModel.onUndo(undo)

        val notice: DownloadsNotice? = viewModel.state.value.notice
        assertEquals("Could not put Submarine back", notice?.headline)
        assertEquals("This server does not allow downloads for your account.", notice?.detail)
        assertEquals("trying again is all the user can do from here", undo, notice?.undo)
    }

    @Test
    fun `no removal starts while an undo is putting an album back`() = runTest {
        // Both talk to the same repository about the same files. A removal fired under a restore
        // would race it, and the loser would be reported as the winner.
        val gate = CompletableDeferred<Unit>()
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])
        pins.pinGate = gate
        viewModel.onUndo(viewModel.state.value.notice!!.undo!!)
        assertTrue("the undo is in flight", viewModel.state.value.undoing)
        assertFalse(viewModel.state.value.canRemove)

        viewModel.onRemove(THREE_ALBUMS[1])
        assertEquals(
            "the second album never reached the repository",
            listOf(THREE_ALBUMS[0].releaseGroupMbid),
            pins.unpinned,
        )

        gate.complete(Unit)
        assertFalse(viewModel.state.value.undoing)
    }

    // ---- dismissing ----------------------------------------------------------

    @Test
    fun `dismissing clears the notice, so the Undo stops waiting to be hit`() = runTest {
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])
        assertTrue(viewModel.state.value.notice != null)

        viewModel.onDismissNotice()

        assertEquals(null, viewModel.state.value.notice)
    }

    /**
     * Subscribes to the state so the `WhileSubscribed` flow starts.
     *
     * Without a collector `state.value` is the initial [DownloadsUiState], which is the loading one -
     * so every assertion below would pass against an empty screen. `backgroundScope` is cancelled
     * when the test ends.
     */
    private fun TestScope.subscribe(viewModel: DownloadsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
        }
    }

    private companion object {

        val PINNED_AT: Instant = Instant.fromEpochSeconds(1_790_157_480L)

        /** The screenshot fixtures' three albums, largest first, as the query would return them. */
        val THREE_ALBUMS: List<DownloadedAlbum> = listOf(
            album("Submarine", "The Marías", 1_181_116_006L, "0a1b"),
            album("Con Todo El Mundo", "Khruangbin", 734_003_200L, "1b2c"),
            album("Fetch the Bolt Cutters", "Fiona Apple", 339_738_624L, "2c3d"),
        )

        fun album(
            title: String,
            artist: String,
            sizeBytes: Long,
            suffix: String,
        ): DownloadedAlbum = DownloadedAlbum(
            releaseGroupMbid = ReleaseGroupMbid("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a$suffix"),
            title = title,
            artistName = artist,
            sizeBytes = sizeBytes,
            pinnedAt = PINNED_AT,
        )
    }
}
