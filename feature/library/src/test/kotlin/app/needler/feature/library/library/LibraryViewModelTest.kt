package app.needler.feature.library.library

import app.cash.turbine.test
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.TrackListKind
import app.needler.feature.library.FakeLibraryRepository
import app.needler.feature.library.FakePlaybackController
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.FakeSyncRepository
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = FakeLibraryRepository(
        albums = SampleLibrary.albums,
        artists = SampleLibrary.artists,
        songs = SampleLibrary.submarineTracks,
        stats = SampleLibrary.stats,
    )
    private val sync = FakeSyncRepository()
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private fun viewModel(withPlayer: Boolean = true) = LibraryViewModel(
        library = library,
        sync = sync,
        session = sessions,
        playback = if (withPlayer) Optional.of(playback) else Optional.empty(),
    )

    @Test
    fun `starts loading and then shows the mirror`() = runTest {
        viewModel().state.test {
            val first = awaitItem()
            assertTrue("the first state is the loading one", first.loading)

            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertEquals(SampleLibrary.albums.size, loaded.albums.size)
            assertEquals(LibraryTab.ALBUMS, loaded.tab)
            assertEquals(LibraryViewMode.GRID, loaded.viewMode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the header reports the album count, the size and the last scan`() = runTest {
        viewModel().state.test {
            awaitItem()
            val loaded = awaitItem()
            // renderedAt is the wall clock, so the scan figure is not asserted
            // here - LibraryFormatTest covers it from a fixed instant. What
            // matters is that all three parts reached the line.
            assertTrue(loaded.headerLine.startsWith("176 albums · 42 GB · last scan"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the sort reaches the repository query rather than sorting in memory`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onSortSelect(LibrarySort.TITLE)
            advanceUntilIdle()
            val sorted = awaitItem()
            assertEquals(LibrarySort.TITLE, sorted.sort)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(AlbumListKind.NEWEST, AlbumListKind.ALPHABETICAL_BY_NAME),
            library.albumListRequests,
        )
    }

    @Test
    fun `switching to Artists stops querying albums and lists artists`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onTabSelect(LibraryTab.ARTISTS)
            advanceUntilIdle()
            var current = awaitItem()
            while (current.tab != LibraryTab.ARTISTS || current.artists.isEmpty()) {
                current = awaitItem()
            }
            assertEquals(SampleLibrary.artists.size, current.artists.size)
            assertTrue("the album list is not carried over", current.albums.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the Songs tab asks the repository for songs, not for a list of albums`() = runTest {
        // The regression this tab shipped with: it had no query of its own, so it subscribed to an
        // album list and flattened the tracks of the first forty albums. That made it a sample of
        // the library rather than the library, and it is why the assertion below is about *which*
        // query was asked rather than about what came back.
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onTabSelect(LibraryTab.SONGS)
            advanceUntilIdle()
            var current = awaitItem()
            while (current.tab != LibraryTab.SONGS || current.songs.isEmpty()) {
                current = awaitItem()
            }
            assertEquals(SampleLibrary.submarineTracks.size, current.songs.size)
            assertTrue("the album list is not carried over", current.albums.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(TrackListKind.NEWEST), library.trackListRequests)
        // One album query, from the tab the screen opens on. Switching to Songs must not issue
        // another one.
        assertEquals(listOf(AlbumListKind.NEWEST), library.albumListRequests)
    }

    @Test
    fun `Title on the Songs tab means the song's title, not its album's`() = runTest {
        // Observed on a device before the repository grew a songs query: selecting Title on the
        // Songs tab produced a single Oasis record in track order, because the list was an album
        // list sorted by album title. The sort control is one control with two vocabularies, and
        // this asserts the Songs tab speaks the second one.
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onTabSelect(LibraryTab.SONGS)
            advanceUntilIdle()
            model.onSortSelect(LibrarySort.TITLE)
            advanceUntilIdle()
            var current = awaitItem()
            while (current.sort != LibrarySort.TITLE || current.tab != LibraryTab.SONGS) {
                current = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(TrackListKind.NEWEST, TrackListKind.ALPHABETICAL_BY_TITLE),
            library.trackListRequests,
        )
        // And nothing asked for an alphabetical *album* list on the way through.
        assertEquals(listOf(AlbumListKind.NEWEST), library.albumListRequests)
    }

    @Test
    fun `every sort option maps to a songs ordering as well as an album one`() {
        // A guard on the enum rather than on the ViewModel: a sixth option added with only an
        // album mapping would not compile, and one added with the wrong songs mapping is caught
        // here rather than on a device.
        assertEquals(
            listOf(
                TrackListKind.NEWEST,
                TrackListKind.ALPHABETICAL_BY_TITLE,
                TrackListKind.ALPHABETICAL_BY_ARTIST,
                TrackListKind.FREQUENT,
                TrackListKind.STARRED,
            ),
            LibrarySort.entries.map { it.trackKind },
        )
    }

    @Test
    fun `the grid-list toggle flips and is remembered`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            assertTrue(awaitItem().showsGrid)
            model.onViewModeToggle()
            advanceUntilIdle()
            val list = awaitItem()
            assertEquals(LibraryViewMode.LIST, list.viewMode)
            assertFalse(list.showsGrid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty mirror produces the empty state, not an endless spinner`() = runTest {
        val emptyLibrary = FakeLibraryRepository()
        val model = LibraryViewModel(
            library = emptyLibrary,
            sync = sync,
            session = sessions,
            playback = Optional.empty(),
        )
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.showEmptyState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing the connection does not empty the library, it only says so`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            val online = awaitItem()
            assertFalse(online.offline)

            sessions.connectivityFlow.value = ConnectivityState.Offline
            val offline = awaitItem()
            assertTrue(offline.offline)
            assertEquals(
                "browsing still works offline",
                SampleLibrary.albums.size,
                offline.albums.size,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `playing an album from the grid goes through the playback controller`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onAlbumPlay(SampleLibrary.submarine.releaseGroupMbid)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.playAlbumCalls.size)
        assertEquals(
            SampleLibrary.submarine.releaseGroupMbid,
            playback.playAlbumCalls.single().mbid,
        )
        assertFalse(playback.playAlbumCalls.single().shuffle)
    }

    @Test
    fun `with no player bound, a play tap is a no-op rather than a crash`() = runTest {
        val model = viewModel(withPlayer = false)
        model.state.test {
            awaitItem()
            awaitItem()
            model.onAlbumPlay(SampleLibrary.submarine.releaseGroupMbid)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playAlbumCalls.isEmpty())
    }

    @Test
    fun `an unplayable song is never handed to the player`() = runTest {
        val missing = SampleLibrary.submarinePartialTracks.first { it.key.trackNumber == 4 }
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onSongPlay(missing)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `Sync now forces a delta sync`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onSyncNow()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, sync.deltaSyncCalls)
        assertEquals(true, sync.lastForced)
    }
}
