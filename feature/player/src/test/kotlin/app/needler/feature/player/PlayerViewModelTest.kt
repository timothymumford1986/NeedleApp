package app.needler.feature.player

import app.cash.turbine.test
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.NetworkStatus
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.feature.player.fake.FakeFavouriteRepository
import app.needler.feature.player.fake.FakeLibraryRepository
import app.needler.feature.player.fake.FakePins
import app.needler.feature.player.fake.FakePlaybackController
import app.needler.feature.player.fake.FakePlaybackSettingsRepository
import app.needler.feature.player.fake.FakeSessions
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.ui.SleepTimerChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transport, against the domain interface.
 *
 * Nothing here needs Media3, a session or a graph - which is one of the three things
 * `PlaybackController` exists to make true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val controller = FakePlaybackController()
    private val favourites = FakeFavouriteRepository()
    private val library = FakeLibraryRepository()
    private val pins = FakePins()
    private val playbackSettings = FakePlaybackSettingsRepository()
    private val sessions = FakeSessions()

    /**
     * The view model under test.
     *
     * Six dependencies now, and every one of them a domain interface - which is the point
     * `PlaybackController` makes about itself: "testing a player screen with no session running... needs
     * no fake Media3", and the same is true of the mirror, the cache index and the preference store
     * behind the other five.
     */
    private fun viewModel(): PlayerViewModel = PlayerViewModel(
        controller = controller,
        favourites = favourites,
        library = library,
        pins = pins,
        playbackSettings = playbackSettings,
        sessions = sessions,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing playing is a state, not an absence`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertFalse(state.hasTrack)
            assertEquals("Nothing playing", state.title)
            assertEquals("Play an album and it lands in the crate", state.subtitle)
            // REQUIREMENTS.md: the output is always named, whether or not anything is playing.
            assertEquals("This device", state.outputName)
            assertNull(state.formatBadge)
        }
    }

    @Test
    fun `the playing track's title, artist, album and badge reach the screen`() = runTest {
        controller.emitState(
            PlaybackState(
                currentItem = PlayerFixtures.playingItem,
                isPlaying = true,
                durationMs = 200_000L,
                output = PlayerFixtures.livingRoomSpeaker,
            ),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertTrue(state.hasTrack)
            assertEquals("Sienna", state.title)
            assertEquals("The Marias · Submarine", state.subtitle)
            assertEquals("FLAC", state.formatBadge)
            assertEquals("Living room speaker", state.outputName)
            assertEquals(200_000L, state.durationMs)
        }
    }

    @Test
    fun `position is not part of the state that the screen binds to`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            // Several ticks, which must not disturb the state flow at all - the whole reason
            // PlaybackController splits its flows.
            controller.emitProgress(PlaybackProgress(positionMs = 1_000L))
            controller.emitProgress(PlaybackProgress(positionMs = 2_000L))
            controller.emitProgress(PlaybackProgress(positionMs = 3_000L))
            expectNoEvents()
        }
    }

    @Test
    fun `the crate's size reaches the state but an equal size does not re-emit`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(0, awaitItem().upNextCount)

            controller.emitQueue(PlayerFixtures.crate)
            assertEquals(6, awaitItem().upNextCount)

            // A different queue with the same number of rows after the playing one: nothing about
            // the transport has changed, so nothing is emitted.
            controller.emitQueue(PlayerFixtures.crate.withItemMoved(2, 3))
            expectNoEvents()
        }
    }

    @Test
    fun `the primary button sends one command and flips`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitItem().isPlaying)
            viewModel.playPause()
            assertTrue(awaitItem().isPlaying)
        }
        assertEquals(listOf("playPause"), controller.commands)
    }

    @Test
    fun `skipping forwards and back is the controller's business, not ours`() = runTest {
        val viewModel = viewModel()
        viewModel.skipToNext()
        viewModel.skipToPrevious()

        // Notably no threshold logic here: the "restart the track instead of leaving it" rule
        // belongs to the implementation, so that two surfaces cannot disagree about the button.
        assertEquals(listOf("skipToNext", "skipToPrevious"), controller.commands)
    }

    @Test
    fun `seeking converts a fraction into a position using the current duration`() = runTest {
        controller.emitState(
            PlaybackState(currentItem = PlayerFixtures.playingItem, durationMs = 200_000L),
        )
        val viewModel = viewModel()
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(0.5f)

        assertEquals(listOf("seekTo(100000)"), controller.commands)
    }

    @Test
    fun `a track of unknown length is not seeked to a guess`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem, durationMs = null))
        val viewModel = viewModel()
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(0.5f)

        assertTrue("no seek should have been sent", controller.commands.isEmpty())
    }

    @Test
    fun `a fraction outside the track is clamped rather than refused`() = runTest {
        controller.emitState(
            PlaybackState(currentItem = PlayerFixtures.playingItem, durationMs = 200_000L),
        )
        val viewModel = viewModel()
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(1.4f)
        viewModel.seekToFraction(-0.2f)

        assertEquals(listOf("seekTo(200000)", "seekTo(0)"), controller.commands)
    }

    @Test
    fun `shuffle toggles from whatever the session says, not from a local copy`() = runTest {
        controller.emitState(PlaybackState(shuffleEnabled = true))
        val viewModel = viewModel()
        viewModel.state.test { awaitItem() }

        viewModel.toggleShuffle()

        assertEquals(listOf("setShuffleEnabled(false)"), controller.commands)
    }

    @Test
    fun `repeat cycles off, all, one, off`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(RepeatMode.OFF, awaitItem().repeatMode)
            viewModel.cycleRepeat()
            assertEquals(RepeatMode.ALL, awaitItem().repeatMode)
            viewModel.cycleRepeat()
            assertEquals(RepeatMode.ONE, awaitItem().repeatMode)
            viewModel.cycleRepeat()
            assertEquals(RepeatMode.OFF, awaitItem().repeatMode)
        }
    }

    @Test
    fun `a playback failure arrives as a line a listener can act on`() = runTest {
        controller.emitState(
            PlaybackState(
                currentItem = PlayerFixtures.playingItem,
                error = app.needler.core.domain.model.NeedlerError.StreamSlotsExhausted,
            ),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(
                "The server is out of streaming slots. Try again in a moment.",
                awaitItem().errorMessage,
            )
        }
    }

    @Test
    fun `an empty queue leaves the state idle rather than throwing`() = runTest {
        controller.emitQueue(PlayQueue.Empty)
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(PlayerUiState.Idle, awaitItem())
        }
    }

    // ---- favourites (REQUIREMENTS.md: binary stars, never ratings) -----------

    @Test
    fun `the heart reads the mirror for the playing track`() = runTest {
        val starred = FakeFavouriteRepository(
            starred = setOf(FakeFavouriteRepository.trackKey(PlayerFixtures.sienna.key)),
        )
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = PlayerViewModel(
            controller = controller,
            favourites = starred,
            library = library,
            pins = pins,
            playbackSettings = playbackSettings,
            sessions = sessions,
        )

        viewModel.state.test {
            assertTrue(awaitItem().isFavourite)
        }
    }

    @Test
    fun `starring sends the opposite of what is on screen`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitItem().isFavourite)
            viewModel.toggleFavourite()
            assertTrue(awaitItem().isFavourite)
            viewModel.toggleFavourite()
            assertFalse(awaitItem().isFavourite)
        }

        val key: String = FakeFavouriteRepository.trackKey(PlayerFixtures.sienna.key)
        assertEquals(listOf(key + "=true", key + "=false"), favourites.calls)
    }

    @Test
    fun `nothing playing is nothing to star`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.toggleFavourite()
            expectNoEvents()
        }
        assertTrue("no star should have been sent", favourites.calls.isEmpty())
    }

    @Test
    fun `a permanently refused star is said out loud rather than swallowed`() = runTest {
        favourites.result = FakeFavouriteRepository.Refused
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            assertNull(awaitItem().favouriteErrorMessage)
            viewModel.toggleFavourite()
            // The mirror still flipped - FavouriteRepository writes locally first - so the heart fills and
            // the line explains that the server disagreed.
            assertEquals(
                "Your account is not allowed to change favourites.",
                awaitItem().favouriteErrorMessage,
            )
        }
    }

    @Test
    fun `a favourite failure is not a playback failure`() = runTest {
        favourites.result = FakeFavouriteRepository.Refused
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.toggleFavourite()
            val state: PlayerUiState = awaitItem()
            // Two fields, because they are two things: `error` is the session's, and a track that plays
            // afterwards must not clear a star the server refused.
            assertNull(state.errorMessage)
            assertEquals(
                "Your account is not allowed to change favourites.",
                state.favouriteErrorMessage,
            )
        }
    }

    // ---- the artist line ----------------------------------------------------

    @Test
    fun `the artist resolves to an MBID through the track's album`() = runTest {
        val resolved = FakeLibraryRepository(
            albums = mapOf(PlayerFixtures.submarine.releaseGroupMbid.value to PlayerFixtures.submarine),
        )
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = PlayerViewModel(
            controller = controller,
            favourites = favourites,
            library = resolved,
            pins = pins,
            playbackSettings = playbackSettings,
            sessions = sessions,
        )

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertEquals(PlayerFixtures.mariasMbid, state.artistMbid)
            assertTrue(state.canOpenArtist)
            // The two halves of the byline, so the screen can draw one as a link and one as text.
            assertEquals("The Marias", state.artistName)
            assertEquals("Submarine", state.albumTitle)
        }
    }

    @Test
    fun `an artist the mirror cannot identify is not offered as a link`() = runTest {
        val unresolved = FakeLibraryRepository(
            albums = mapOf(
                PlayerFixtures.submarine.releaseGroupMbid.value to
                    PlayerFixtures.submarineWithoutArtistMbid,
            ),
        )
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = PlayerViewModel(
            controller = controller,
            favourites = favourites,
            library = unresolved,
            pins = pins,
            playbackSettings = playbackSettings,
            sessions = sessions,
        )

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertNull(state.artistMbid)
            assertFalse("a link with nowhere to go must not be drawn", state.canOpenArtist)
            // The name is still there: only the capability is missing.
            assertEquals("The Marias", state.artistName)
        }
    }

    // ---- the sleep timer ----------------------------------------------------

    @Test
    fun `a duration is armed as an instant, measured once at the tap`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            val before: Long = System.currentTimeMillis()
            viewModel.setSleepTimer(SleepTimerChoice.MINUTES_30)

            val timer: SleepTimer = awaitItem().sleepTimer
            assertTrue("expected a timed stop, got " + timer, timer is SleepTimer.At)
            // A window, not a value: the instant is "now plus thirty minutes", and the only thing a test
            // can honestly say about "now" is roughly when it was. [before] is read *first*, so the gap
            // can only be thirty minutes plus however long the two statements took - never less. The
            // window was written the other way round and failed on a clock that ticked 2 ms between the
            // two reads, which is a test asserting the arrow of time backwards rather than a defect.
            val ahead: Long = (timer as SleepTimer.At).instant.toEpochMilliseconds() - before
            assertTrue("expected about 30 minutes, got " + ahead + " ms", ahead in 1_800_000L..1_860_000L)
        }
        assertEquals(listOf("setSleepTimer(At)"), controller.commands)
    }

    @Test
    fun `end of track and off go through the controller as themselves`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.setSleepTimer(SleepTimerChoice.END_OF_TRACK)
            assertEquals(SleepTimer.EndOfTrack, awaitItem().sleepTimer)

            viewModel.setSleepTimer(SleepTimerChoice.OFF)
            assertEquals(SleepTimer.Off, awaitItem().sleepTimer)
        }
        assertEquals(
            listOf("setSleepTimer(EndOfTrack)", "setSleepTimer(Off)"),
            controller.commands,
        )
    }

    @Test
    fun `the armed timer arrives on the state every surface already reads`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(SleepTimer.Off, awaitItem().sleepTimer)
            // Armed by something else entirely - the notification, Wear, another client of the session -
            // and it still reaches this screen, because it rides on PlaybackState.
            controller.emitState(PlaybackState(sleepTimer = SleepTimer.EndOfTrack))
            assertEquals(SleepTimer.EndOfTrack, awaitItem().sleepTimer)
        }
    }

    // ---- the quality tag pair ------------------------------------------------

    @Test
    fun `with nothing downloaded the server tag is the one in force`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            // Unmetered, Wi-Fi rung Original: the server sends the file untouched, so the tag shows the
            // source's own quality rather than naming a rung nobody reached.
            assertEquals(StreamFormat.Original, state.serverFormat)
            assertEquals("FLAC", state.serverTagValue)
            assertFalse(state.isPulled)
            assertNull(state.pulledTagValue)
        }
    }

    @Test
    fun `a metered connection moves the server tag to the data rung`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
        playbackSettings.setDataStreamRung(StreamRung.MP3_192)
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            // The whole value of this tag: it changes when the user leaves the house.
            assertEquals(StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 192), state.serverFormat)
            assertEquals("MP3 192", state.serverTagValue)
        }
    }

    @Test
    fun `an override on the track beats the data rung in both directions`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
        playbackSettings.setDataStreamRung(StreamRung.MP3_128)
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals("MP3 128", awaitItem().serverTagValue)

            viewModel.overridePlayingTrackQuality(StreamRung.ORIGINAL)
            assertEquals("FLAC", awaitItem().serverTagValue)

            viewModel.clearPlayingTrackQualityOverride()
            assertEquals("MP3 128", awaitItem().serverTagValue)
        }
    }

    @Test
    fun `a downloaded copy takes over and the server tag goes dormant`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
        playbackSettings.setDataStreamRung(StreamRung.MP3_192)
        pins.cachedAudio.value = downloaded(PlayerFixtures.sienna)
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertTrue(state.isPulled)
            assertEquals("FLAC", state.pulledTagValue)
            // Still true, still shown, and no longer what is playing - which is what the dimmed tag says.
            assertEquals("MP3 192", state.serverTagValue)
        }
    }

    @Test
    fun `a merely cached copy is not called pulled`() = runTest {
        // REQUIREMENTS.md: cached-while-listening bytes are "evicted to keep the device above its
        // free-space floor". Calling them pulled would promise offline availability the app cannot keep.
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        pins.cachedAudio.value = downloaded(PlayerFixtures.sienna).copy(pinned = false)
        val viewModel = viewModel()

        viewModel.state.test {
            val state: PlayerUiState = awaitItem()
            assertFalse(state.isPulled)
            assertNull(state.pulledTagValue)
        }
    }

    @Test
    fun `a part-downloaded track is not called pulled either`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        pins.cachedAudio.value = downloaded(PlayerFixtures.sienna).copy(isComplete = false)
        val viewModel = viewModel()

        viewModel.state.test {
            assertNull(awaitItem().pulledTagValue)
        }
    }

    @Test
    fun `stale bytes drop the pulled tag rather than outliving the copy`() = runTest {
        // The server replaced the file on a quality upgrade, so the resolver will discard these bytes on
        // the next play - REQUIREMENTS.md "Invalidating upgraded files". A tag naming them would describe
        // a copy that is about to stop existing.
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem))
        pins.cachedAudio.value = downloaded(PlayerFixtures.sienna).copy(
            sourceHandle = PlayerFixtures.sienna.fetch.copy(fileId = FileId("an-older-file")),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            assertNull(awaitItem().pulledTagValue)
        }
    }

    /** A complete, pinned, current on-device copy of [track] - what `Pulled:` means. */
    private fun downloaded(track: Track): CachedAudio = CachedAudio(
        key = track.key,
        filePath = "/audio/" + track.key.canonicalString,
        sizeOnDiskBytes = 41_000_000L,
        isComplete = true,
        pinned = true,
        sourceHandle = track.fetch,
    )
}
