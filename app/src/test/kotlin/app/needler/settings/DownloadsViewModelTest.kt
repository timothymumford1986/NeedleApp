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
        assertEquals("Nothing is downloaded to this device.", viewModel.state.value.summary)
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

        assertEquals("Removed Submarine: 12 tracks, 1.1 GB freed.", viewModel.state.value.notice)
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

        assertEquals(
            "Removed Fetch the Bolt Cutters. None of it was on this device.",
            viewModel.state.value.notice,
        )
    }

    @Test
    fun `a removal that failed says which album and why, and keeps the row`() = runTest {
        pins.unpinOutcome = Outcome.Failure(NeedlerError.SessionExpired)
        val viewModel = DownloadsViewModel(pins)
        subscribe(viewModel)

        viewModel.onRemove(THREE_ALBUMS[0])

        assertEquals(
            "Could not remove Submarine. Your sign-in has expired. Sign in again to restore " +
                "search and pulls.",
            viewModel.state.value.notice,
        )
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
        assertTrue("the first removal is in flight", viewModel.state.value.working)
        assertFalse("remove controls stop responding while one is running", viewModel.state.value.canRemove)

        viewModel.onRemove(THREE_ALBUMS[1])
        assertEquals(
            "only the first album reached the repository",
            listOf(THREE_ALBUMS[0].releaseGroupMbid),
            pins.unpinned,
        )

        gate.complete(Unit)
        assertFalse(viewModel.state.value.working)
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
