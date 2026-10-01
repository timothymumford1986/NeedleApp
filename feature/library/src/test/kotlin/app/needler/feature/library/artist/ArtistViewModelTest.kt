package app.needler.feature.library.artist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.feature.library.FakeFavouriteRepository
import app.needler.feature.library.FakeLibraryRepository
import app.needler.feature.library.FakePlaybackController
import app.needler.feature.library.FakePullRepository
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = FakeLibraryRepository(artists = SampleLibrary.artists)
    private val pulls = FakePullRepository()
    private val favourites = FakeFavouriteRepository()
    private val playback = FakePlaybackController()
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

    private fun viewModel(
        artistId: String = artist.mbid.value,
        artistName: String? = null,
        artistSubtitle: String? = null,
    ) = ArtistViewModel(
        savedStateHandle = SavedStateHandle(
            buildMap<String, Any?> {
                put(ArtistViewModel.ARTIST_ID_ARG, artistId)
                if (artistName != null) put(ArtistViewModel.ARTIST_NAME_ARG, artistName)
                if (artistSubtitle != null) {
                    put(ArtistViewModel.ARTIST_SUBTITLE_ARG, artistSubtitle)
                }
            },
        ),
        library = library,
        favourites = favourites,
        playback = Optional.of(playback),
        pulls = pulls,
        sessions = sessions,
    )

    private fun stocked() {
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to (owned + unowned))
        library.tracksByMbid.value = mapOf(
            SampleLibrary.submarine.releaseGroupMbid.value to SampleLibrary.submarineTracks,
        )
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
            model.onConfirmRequest()
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

    // ---- the request sheet --------------------------------------------------

    @Test
    fun `tapping a row Pull opens the sheet and sends nothing yet`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            model.onPull(loaded.catalogueAlbums.first())
            var open = awaitItem()
            while (open.requestSheet == null) open = awaitItem()
            assertEquals("Superclean Vol. II", open.requestSheet?.title)
            assertFalse(open.requestSheet?.isArtistWide ?: true)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(pulls.albumRequests.isEmpty())
    }

    @Test
    fun `the monitor artist toggle reaches the request body`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            model.onPull(loaded.catalogueAlbums.first())
            model.onMonitorArtistChange(true)
            model.onConfirmRequest()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(pulls.albumRequests.single().monitorArtist)
    }

    // ---- playback ----------------------------------------------------------

    @Test
    fun `Play hands over every playable track the artist owns`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playableTracks.isEmpty()) loaded = awaitItem()
            assertTrue(loaded.canPlay)
            model.onPlay()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            SampleLibrary.submarineTracks.map { it.title },
            playback.playTracksCalls.single().map { it.title },
        )
    }

    /**
     * `PlaybackController.playAlbum` documents that the album screen's Shuffle "shuffles the
     * album's own tracks rather than turning on the global shuffle mode for everything that
     * follows". There is no artist-wide equivalent of that call, so the list is shuffled
     * here and handed over in that order — the assertion is that the same tracks go, not
     * that the mode changed.
     */
    @Test
    fun `Shuffle hands over the same tracks in some order and does not touch the mode`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playableTracks.isEmpty()) loaded = awaitItem()
            model.onShuffle()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            SampleLibrary.submarineTracks.map { it.title }.toSet(),
            playback.playTracksCalls.single().map { it.title }.toSet(),
        )
        assertTrue(playback.playAlbumCalls.isEmpty())
    }

    /** REQUIREMENTS.md "Partial content is a normal state": an owned album can have holes. */
    @Test
    fun `an artist whose every track is missing cannot be played`() = runTest {
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to owned)
        library.tracksByMbid.value = mapOf(
            SampleLibrary.submarine.releaseGroupMbid.value to
                SampleLibrary.submarineTracks.map { existing ->
                    SampleLibrary.track(
                        albumSlug = "submarine",
                        number = existing.key.trackNumber,
                        title = existing.title,
                        artistName = existing.artistName,
                        durationMs = existing.durationMs ?: 0L,
                        available = false,
                    )
                },
        )
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            assertFalse(loaded.canPlay)
            model.onPlay()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    // ---- favourites --------------------------------------------------------

    @Test
    fun `starring the artist is keyed on the artist MBID`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.artist == null) loaded = awaitItem()
            model.onToggleFavourite()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(FavouriteTarget.OfArtist(artist.mbid) to true),
            favourites.calls,
        )
    }

    // ---- an id the catalogue can never accept -------------------------------

    /**
     * The device log was full of
     * `Rejected 400: Use the local library artist route for a DroppedNeedle artist ID`
     * for exactly this id. The fix is not to handle the 400 better; it is not to make the call.
     */
    @Test
    fun `a name-derived artist id never reaches the discography route`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to owned)
        library.discographyByArtist.value = mapOf(SERVER_MINTED to owned)

        val model = viewModel(artistId = SERVER_MINTED, artistName = "Some Local Band")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            assertTrue(loaded.artistNotInCatalogue)
            assertTrue(loaded.discographyUnavailable)
            // Not an error: nothing failed, because nothing was attempted.
            assertNull(loaded.discographyError)
            // And the screen still says who it is about, and still lists what is owned.
            assertEquals("Some Local Band", loaded.displayName)
            assertEquals(1, loaded.ownedAlbums.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(library.refreshedDiscographies.isEmpty())
    }

    @Test
    fun `a real MusicBrainz artist id does reach the discography route`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(artist.mbid), library.refreshedDiscographies)
    }

    /**
     * A name-derived artist with un-owned rows in the mirror — search wrote them — can still
     * be pulled, and the "no discography" sentence stays off the screen while there is one on
     * it.
     */
    @Test
    fun `a name-derived artist with catalogue rows still offers a pull`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to emptyList())
        library.discographyByArtist.value = mapOf(SERVER_MINTED to unowned)

        viewModel(artistId = SERVER_MINTED, artistName = "Some Local Band").state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            assertFalse(loaded.artistNotInCatalogue)
            assertTrue(loaded.canPullArtist)
            assertEquals(2, loaded.pullableAlbums.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- an artist nobody owns, reached from search -------------------------

    /**
     * The device case: search "my friend the chocolate cake", tap the artist, land on "That
     * artist is not here" — an untrue sentence on a screen that never said the name and
     * offered nothing but Back.
     *
     * `refreshArtistDiscography` writes album rows and never an artist row, so the mirror has
     * no artist however well the fetch went. The name comes from the route instead.
     */
    @Test
    fun `an artist known only from search is named and can be pulled`() = runTest {
        library.ownedByArtist.value = mapOf(CATALOGUE_ONLY to emptyList())
        library.discographyByArtist.value = mapOf(CATALOGUE_ONLY to unowned)

        val model = viewModel(
            artistId = CATALOGUE_ONLY,
            artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
            artistSubtitle = ArtistUiState.NOT_IN_LIBRARY_YET,
        )
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            // Not a dead end any more.
            assertFalse(loaded.notFound)
            assertEquals(SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME, loaded.displayName)
            assertTrue(loaded.fromHintsOnly)
            assertTrue(loaded.canPullArtist)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an artist with no mirror row and no albums still says the name it was given`() = runTest {
        library.refreshDiscographyOutcome = Outcome.Failure(NeedlerError.Offline())
        sessions.connectivityFlow.value = ConnectivityState.Offline

        viewModel(
            artistId = CATALOGUE_ONLY,
            artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
            artistSubtitle = ArtistUiState.NOT_IN_LIBRARY_YET,
        ).state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.discographyUnavailable) loaded = awaitItem()
            assertFalse(loaded.notFound)
            assertEquals(SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME, loaded.displayName)
            assertEquals(ArtistUiState.NOT_IN_LIBRARY_YET, loaded.subtitle)
            // Nothing to pull, so the screen offers the one action that can still work.
            assertFalse(loaded.canPullArtist)
            assertTrue(loaded.canRetryDiscography)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an artist with no row, no name and no albums is the only empty screen left`() = runTest {
        viewModel(artistId = CATALOGUE_ONLY).state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.loading) loaded = awaitItem()
            assertTrue(loaded.notFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- pulling the whole artist ------------------------------------------

    @Test
    fun `Pull all asks for every un-owned release group, with the monitor flag`() = runTest {
        library.ownedByArtist.value = mapOf(CATALOGUE_ONLY to emptyList())
        library.discographyByArtist.value = mapOf(CATALOGUE_ONLY to unowned)

        val model = viewModel(artistId = CATALOGUE_ONLY, artistName = "Chocolate Cake")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            model.onPullArtist()
            var open = awaitItem()
            while (open.requestSheet == null) open = awaitItem()
            assertNotNull(open.requestSheet)
            assertTrue(open.requestSheet?.isArtistWide ?: false)
            assertEquals("Everything by Chocolate Cake", open.requestSheet?.title)
            model.onMonitorArtistChange(true)
            model.onConfirmRequest()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, pulls.batchRequests.single().size)
        assertTrue(pulls.batchRequests.single().all { it.monitorArtist })
    }

    private companion object {
        /**
         * The id from the device log, a UUID v5: third group `5e80`.
         *
         * Taken from the shared fixture rather than retyped, so that this test and the artist
         * screenshots are asserting about the same id. DroppedNeedle derived it from the artist's
         * name, and the catalogue route will never accept it.
         */
        val SERVER_MINTED: String = SampleLibrary.nameDerivedArtist.mbid.value

        /** A real MusicBrainz artist MBID for an artist the library does not own. */
        val CATALOGUE_ONLY: String = SampleLibrary.CATALOGUE_ONLY_ARTIST_MBID
    }
}
