package app.needler.feature.player

import app.cash.turbine.test
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.feature.player.fake.FakePlaybackController
import app.needler.feature.player.fake.PlayerFixtures
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
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

        viewModel.state.test {
            assertFalse(awaitItem().isPlaying)
            viewModel.playPause()
            assertTrue(awaitItem().isPlaying)
        }
        assertEquals(listOf("playPause"), controller.commands)
    }

    @Test
    fun `skipping forwards and back is the controller's business, not ours`() = runTest {
        val viewModel = PlayerViewModel(controller)
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
        val viewModel = PlayerViewModel(controller)
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(0.5f)

        assertEquals(listOf("seekTo(100000)"), controller.commands)
    }

    @Test
    fun `a track of unknown length is not seeked to a guess`() = runTest {
        controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem, durationMs = null))
        val viewModel = PlayerViewModel(controller)
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(0.5f)

        assertTrue("no seek should have been sent", controller.commands.isEmpty())
    }

    @Test
    fun `a fraction outside the track is clamped rather than refused`() = runTest {
        controller.emitState(
            PlaybackState(currentItem = PlayerFixtures.playingItem, durationMs = 200_000L),
        )
        val viewModel = PlayerViewModel(controller)
        viewModel.state.test { awaitItem() }

        viewModel.seekToFraction(1.4f)
        viewModel.seekToFraction(-0.2f)

        assertEquals(listOf("seekTo(200000)", "seekTo(0)"), controller.commands)
    }

    @Test
    fun `shuffle toggles from whatever the session says, not from a local copy`() = runTest {
        controller.emitState(PlaybackState(shuffleEnabled = true))
        val viewModel = PlayerViewModel(controller)
        viewModel.state.test { awaitItem() }

        viewModel.toggleShuffle()

        assertEquals(listOf("setShuffleEnabled(false)"), controller.commands)
    }

    @Test
    fun `repeat cycles off, all, one, off`() = runTest {
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

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
        val viewModel = PlayerViewModel(controller)

        viewModel.state.test {
            assertEquals(PlayerUiState.Idle, awaitItem())
        }
    }
}
