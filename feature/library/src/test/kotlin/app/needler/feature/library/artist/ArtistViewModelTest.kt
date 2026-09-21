package app.needler.feature.library.artist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.feature.library.FakeLibraryRepository
import app.needler.feature.library.FakePullRepository
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = FakeLibraryRepository(artists = SampleLibrary.artists)
    private val pulls = FakePullRepository()
    private val sessions = FakeSessions()

    private val artist = SampleLibrary.artists.first()
    private val owned = listOf(SampleLibrary.submarine)
    private val unowned = listOf(
        SampleLibrary.album(
            slug = "superclean",
            title = "Superclean Vol. II",
            artistName = "The Marías",
            artistSlug = "marias",
            year = 2018,
            trackCount = 7,
            state = AlbumState.NotOwned,
            format = null,
        ),
        SampleLibrary.album(
            slug = "cinema",
            title = "Cinema",
            artistName = "The Marías",
            artistSlug = "marias",
            year = 2021,
            trackCount = 13,
            state = AlbumState.NotOwned,
            format = null,
        ),
    )

    private fun viewModel() = ArtistViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(ArtistViewModel.ARTIST_ID_ARG to artist.mbid.value),
        ),
        library = library,
        pulls = pulls,
        sessions = sessions,
    )

    private fun stocked() {
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to (owned + unowned))
    }

    @Test
    fun `owned albums come first and the catalogue holds only what you do not own`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            assertEquals(listOf("Submarine"), loaded.ownedAlbums.map { it.title })
            assertEquals(
                listOf("Superclean Vol. II", "Cinema"),
                loaded.catalogueAlbums.map { it.title },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album present in both lanes is drawn once, from the mirror`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            val titles = loaded.ownedAlbums.map { it.title } + loaded.catalogueAlbums.map { it.title }
            assertEquals(titles.size, titles.toSet().size)
            assertFalse(loaded.catalogueAlbums.any { it.title == "Submarine" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the subtitle counts what you own and what is left to pull`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            assertEquals("1 album · 2 more to pull", loaded.subtitle)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unreachable catalogue does not take the owned half down with it`() = runTest {
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.refreshDiscographyOutcome =
            Outcome.Failure(NeedlerError.Offline())
        sessions.connectivityFlow.value = ConnectivityState.Offline

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.discographyUnavailable) loaded = awaitItem()
            assertTrue(loaded.offline)
            assertEquals(1, loaded.ownedAlbums.size)
            assertTrue(loaded.catalogueAlbums.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pulling an un-owned album sends the title, artist and year as hints`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            model.onPull(loaded.catalogueAlbums.first())
            advanceUntilIdle()
            var after = awaitItem()
            while (after.notice == null) after = awaitItem()
            assertEquals(AlbumNotice.PullAccepted, after.notice)
            cancelAndIgnoreRemainingEvents()
        }
        val request = pulls.albumRequests.single()
        assertEquals("Superclean Vol. II", request.albumTitle)
        assertEquals("The Marías", request.artistName)
        assertEquals(2018, request.year)
    }
}
