package app.needler.feature.library.artist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.SearchBucket
import app.needler.feature.library.FakeFavouriteRepository
import app.needler.feature.library.FakeLibraryRepository
import app.needler.feature.library.FakePlaybackController
import app.needler.feature.library.FakePullRepository
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.hasPlayableFile
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
    private val catalogue = FakeArtistCatalogueSearch()

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
        search = catalogue,
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

    // ---- the screen is never silent about the catalogue half ----------------

    /**
     * The device regression, v0.0.12: Dido, four owned albums, and nothing whatever about the
     * rest of her records.
     *
     * No discography section, no sentence, no retry, nothing in logcat. The screen could not have
     * drawn any of them: the only empty-state line on it is gated on [ArtistUiState.hasNothing],
     * which is false whenever anything is owned, and the retry was gated on the same thing. So an
     * artist with a dozen un-owned records looked exactly like an artist with four records in
     * total, and a listener had no way to tell "that is all there is" from "we could not look it
     * up" from "we did not try".
     *
     * REQUIREMENTS.md "Library browse" calls this screen the place "where the two lanes meet
     * visibly". A lane that is missing without comment is not visible.
     */
    @Test
    fun `an artist with owned albums and an empty catalogue half says so and offers a retry`() =
        runTest {
            library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
            library.discographyByArtist.value = mapOf(artist.mbid.value to owned)
            library.tracksByMbid.value = mapOf(
                SampleLibrary.submarine.releaseGroupMbid.value to SampleLibrary.submarineTracks,
            )

            viewModel().state.test {
                awaitItem()
                var loaded = awaitItem()
                while (!loaded.discographySettled) loaded = awaitItem()
                assertEquals(1, loaded.ownedAlbums.size)
                assertTrue(loaded.catalogueAlbums.isEmpty())
                // Not the empty screen, and not a failure either - which is exactly why this
                // state used to have no representation at all.
                assertFalse(loaded.hasNothing)
                assertFalse(loaded.discographyUnavailable)
                assertTrue(loaded.discographyEmpty)
                assertTrue(loaded.canRetryDiscography)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * A failed lookup is reported with a full library half too, and is not reported as empty.
     *
     * The two answers are drawn as different sentences, so they have to be different facts: a
     * warming or offline catalogue has not told us the artist is finished.
     */
    @Test
    fun `a failed lookup is reported even when the library half is full`() = runTest {
        library.refreshDiscographyOutcome =
            Outcome.Failure(NeedlerError.CapabilityUnavailable("still warming"))
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to owned)

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.discographySettled) loaded = awaitItem()
            assertTrue(loaded.discographyUnavailable)
            assertNotNull(loaded.discographyError)
            assertFalse(loaded.discographyEmpty)
            assertTrue(loaded.canRetryDiscography)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A discography that is on screen and failed to refresh says it may be short.
     *
     * The legitimate half of the sentence the empty case used to monopolise. [ArtistUiState]
     * suppresses `discographyError` once there are un-owned rows to draw - "the discography is
     * unavailable" printed under a discography is a contradiction - and that suppression used to
     * take the whole message with it, so nine cached rows were indistinguishable from nine current
     * ones. The retry has to be offered alongside it, because the line names it.
     */
    @Test
    fun `a stale discography is marked as possibly short and can be refreshed`() = runTest {
        library.refreshDiscographyOutcome = Outcome.Failure(NeedlerError.Offline())
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to (owned + unowned))

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.discographySettled) loaded = awaitItem()
            assertEquals(2, loaded.catalogueAlbums.size)
            // Suppressed, because its output is on screen.
            assertNull(loaded.discographyError)
            assertFalse(loaded.discographyUnavailable)
            // But the fact survives, and so does the control the line points at.
            assertTrue(loaded.discographyIncomplete)
            assertTrue(loaded.canRetryDiscography)
            assertFalse(loaded.discographyEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A current discography claims nothing: no line, and no retry competing with the Pull all. */
    @Test
    fun `a discography that refreshed cleanly is not marked as short`() = runTest {
        stocked()

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.discographySettled) loaded = awaitItem()
            assertEquals(2, loaded.catalogueAlbums.size)
            assertFalse(loaded.discographyIncomplete)
            assertFalse(loaded.canRetryDiscography)
            assertTrue(loaded.canPullArtist)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Nothing is claimed about a lookup that has not answered: the sentence waits for it. */
    @Test
    fun `an unsettled lookup claims nothing about the catalogue`() = runTest {
        val state = ArtistUiState(loading = false, ownedAlbums = owned)

        assertFalse(state.discographyEmpty)
        assertFalse(state.canRetryDiscography)
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

    // ---- the way out of a name-derived artist -------------------------------

    /**
     * The reported case, and what it now offers.
     *
     * Observed on a device at v0.0.12: the artist with the most records in the user's library drew
     * four owned albums and the name-derived sentence, and nothing else. The sentence was accurate
     * and the screen was a dead end, which is the worst combination available - there was nothing to
     * dispute and nothing to do. The name is still a perfectly good catalogue query even when the id
     * is useless, and this is the control that asks it.
     */
    @Test
    fun `a name-derived artist is offered the catalogue, by name`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to owned)
        library.discographyByArtist.value = mapOf(SERVER_MINTED to owned)
        catalogue.returning(
            Artist(mbid = ArtistMbid("ar-real-dido"), name = "Some Local Band", ownedAlbumCount = 0),
        )

        val model = viewModel(artistId = SERVER_MINTED, artistName = "Some Local Band")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.artistNotInCatalogue) loaded = awaitItem()
            // The control exists before it is pressed, which is what makes the sentence honest.
            assertTrue(loaded.canFindInCatalogue)
            assertFalse(loaded.canRetryDiscography)

            model.onFindInCatalogue()
            advanceUntilIdle()
            var found = awaitItem()
            while (found.catalogueNamesakes.isEmpty() && !found.namesakeSearchDone) {
                found = awaitItem()
            }
            assertEquals(listOf("Some Local Band"), found.catalogueNamesakes.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
        // The artists bucket, asked for by name. Not rule 2's combined search, which would have
        // fetched a screenful of albums for a question entirely about artists.
        assertEquals(
            listOf(SearchBucket.ARTISTS to "Some Local Band"),
            catalogue.bucketQueries.map { it.first to it.second },
        )
    }

    /**
     * Three kinds of candidate are dropped, each because tapping it would lead nowhere.
     *
     * A candidate whose own id is name-derived cannot fetch a discography either, so it is the same
     * dead end one screen further on. A candidate whose name does not answer the query is not the
     * artist being looked at - the catalogue ranks on aliases and credits, which is how a search for
     * "wonder" put "Jr. Wonder" first. And the artist's own id would be a tap back to this screen.
     */
    @Test
    fun `candidates that lead nowhere are dropped`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to owned)
        library.discographyByArtist.value = mapOf(SERVER_MINTED to owned)
        catalogue.returning(
            Artist(mbid = ArtistMbid(SERVER_MINTED), name = "Dido"),
            Artist(mbid = SampleLibrary.nameDerivedArtist.mbid, name = "Dido"),
            Artist(mbid = ArtistMbid("ar-unrelated"), name = "Hamilton Mixtape"),
            Artist(mbid = ArtistMbid("ar-dido"), name = "Dido"),
            Artist(mbid = ArtistMbid("ar-dido-rowley"), name = "Dido Rowley"),
        )

        val model = viewModel(artistId = SERVER_MINTED, artistName = "Dido")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.artistNotInCatalogue) loaded = awaitItem()
            model.onFindInCatalogue()
            advanceUntilIdle()
            var found = awaitItem()
            while (!found.namesakeSearchDone) found = awaitItem()
            assertEquals(
                listOf("Dido", "Dido Rowley"),
                found.catalogueNamesakes.map { it.name },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A failed look-up is reported as itself, because it is worth another tap. */
    @Test
    fun `a failed catalogue look-up is reported and not read as an empty one`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to owned)
        library.discographyByArtist.value = mapOf(SERVER_MINTED to owned)
        catalogue.bucketOutcome = Outcome.Failure(NeedlerError.Offline())

        val model = viewModel(artistId = SERVER_MINTED, artistName = "Dido")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.artistNotInCatalogue) loaded = awaitItem()
            model.onFindInCatalogue()
            advanceUntilIdle()
            var failed = awaitItem()
            while (!failed.namesakeSearchFailed) failed = awaitItem()
            assertTrue(failed.catalogueNamesakes.isEmpty())
            // Not "the catalogue has nobody of this name": nothing was learned about that.
            assertFalse(failed.noNamesakesFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** An empty answer is a different fact from never having asked, and only one of them is said. */
    @Test
    fun `nobody of this name in the catalogue is said only once it has been asked`() = runTest {
        library.ownedByArtist.value = mapOf(SERVER_MINTED to owned)
        library.discographyByArtist.value = mapOf(SERVER_MINTED to owned)

        val model = viewModel(artistId = SERVER_MINTED, artistName = "Dido")
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.artistNotInCatalogue) loaded = awaitItem()
            assertFalse(loaded.noNamesakesFound)

            model.onFindInCatalogue()
            advanceUntilIdle()
            var asked = awaitItem()
            while (!asked.namesakeSearchDone) asked = awaitItem()
            assertTrue(asked.noNamesakesFound)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** An artist with no name anywhere has no query to make, so the control is absent. */
    @Test
    fun `an artist with no name is offered no catalogue search`() = runTest {
        val model = viewModel(artistId = SERVER_MINTED)
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.loading) loaded = awaitItem()
            assertFalse(loaded.canFindInCatalogue)
            model.onFindInCatalogue()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(catalogue.bucketQueries.isEmpty())
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

    /**
     * The header's artwork tints its letter placeholder from an identity, and on this
     * path there is no `Artist` to take one from. Falling back to the name would give
     * the same artist one colour in search and a different one here; the route's own
     * MBID is what keeps them the same, so it has to reach the state.
     */
    @Test
    fun `the route's artist id reaches the state even with no mirror row`() = runTest {
        library.ownedByArtist.value = mapOf(CATALOGUE_ONLY to emptyList())
        library.discographyByArtist.value = mapOf(CATALOGUE_ONLY to unowned)

        val model = viewModel(
            artistId = CATALOGUE_ONLY,
            artistName = SampleLibrary.CATALOGUE_ONLY_ARTIST_NAME,
        )
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.catalogueAlbums.isEmpty()) loaded = awaitItem()
            assertNull(loaded.artist)
            assertEquals(ArtistMbid(CATALOGUE_ONLY), loaded.mbid)
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

    // ---- the crate ----------------------------------------------------------
    // This screen could play an artist and shuffle an artist, and both replaced the crate. Each
    // test asserts the negative too: that `enqueue` was used and `playTracks`, the command that
    // replaces, was not.

    @Test
    fun `adding an artist appends to the crate rather than replacing it`() = runTest {
        stocked()
        playback.playbackState.value = playback.playbackState.value.copy(
            currentItem = QueueItem(id = "q-0", track = SampleLibrary.submarineTracks.first()),
            isPlaying = true,
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playableTracks.isEmpty()) loaded = awaitItem()
            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(AlbumNotice.AddedToCrate(trackCount = 8), after.notice)
            // The session's own figures, straight off `observeQueue`.
            assertEquals(8, after.crateTrackCount)
            assertEquals("8 in the crate · 28 min", after.crateLine)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(playback.enqueueCalls.single().playNext)
        assertEquals(8, playback.enqueueCalls.single().tracks.size)
        assertTrue("the crate must not be replaced", playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `adding an artist while nothing is loaded starts playback`() = runTest {
        stocked()

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playableTracks.isEmpty()) loaded = awaitItem()
            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(
                AlbumNotice.AddedToCrate(trackCount = 8, started = true),
                after.notice,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.playTracksCalls.size)
        assertTrue(playback.enqueueCalls.isEmpty())
    }

    @Test
    fun `adding one album row reads that album's tracks from the mirror`() = runTest {
        stocked()
        playback.playbackState.value = playback.playbackState.value.copy(
            currentItem = QueueItem(id = "q-0", track = SampleLibrary.submarineTracks.first()),
            isPlaying = true,
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            model.onAddAlbumToCrate(
                loaded.ownedAlbums.single().releaseGroupMbid,
                playNext = true,
            )
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(
                AlbumNotice.AddedToCrate(trackCount = 8, playNext = true),
                after.notice,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.single().playNext)
        assertEquals(
            SampleLibrary.submarineTracks.map { it.title },
            playback.enqueueCalls.single().tracks.map { it.title },
        )
    }

    @Test
    fun `an album with nothing playable in it adds nothing and says nothing`() = runTest {
        library.ownedByArtist.value = mapOf(artist.mbid.value to owned)
        library.discographyByArtist.value = mapOf(artist.mbid.value to owned)
        library.tracksByMbid.value = mapOf(
            SampleLibrary.submarine.releaseGroupMbid.value to
                SampleLibrary.submarinePartialTracks.filter { !it.hasPlayableFile },
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.ownedAlbums.isEmpty()) loaded = awaitItem()
            model.onAddAlbumToCrate(
                loaded.ownedAlbums.single().releaseGroupMbid,
                playNext = false,
            )
            advanceUntilIdle()
            assertNull(expectMostRecentItem().notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.isEmpty())
        assertTrue(playback.playTracksCalls.isEmpty())
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
