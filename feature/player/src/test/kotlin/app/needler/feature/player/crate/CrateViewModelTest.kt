package app.needler.feature.player.crate

import app.cash.turbine.test
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.PlaybackState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The crate.
 *
 * The test that matters most here is the reorder one. `PlayQueue.withItemMoved` exists because
 * recomputing the current index from the indices alone means dragging any row past the Playing row
 * restarts playback on a different track - "the classic reorder bug", invisible until someone
 * reorders while listening. So there is a test for exactly that, expressed as what a user would see:
 * drag the last row to the top, and the same song keeps playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrateViewModelTest {

    private val controller = FakePlaybackController(initialQueue = PlayerFixtures.crate)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the crate splits into a playing row and up next, with the pack's summary`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            // The first emission is the empty initial value before the queue flow arrives.
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()

            assertEquals("Sienna", state.playing?.track?.title)
            assertEquals(6, state.upNext.size)
            // Screen 08 prints exactly this.
            assertEquals("6 tracks · 21 min", state.upNextSummary)
            assertEquals("6 up next", state.upNextCountLabel)
        }
    }

    @Test
    fun `dragging a row past the playing row does not change what is playing`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()
            assertEquals("Sienna", state.playing?.track?.title)

            // The last row of the crate, dragged all the way to the top - straight past Playing.
            viewModel.moveItem(fromIndex = 6, toIndex = 0)

            var moved: CrateUiState = awaitItem()
            // The optimistic copy and the session's echo are the same arrangement, so one or two
            // emissions are both correct; take the last.
            while (moved.queue.items.firstOrNull()?.id != "q7") moved = awaitItem()

            assertEquals("Superstar", moved.queue.items.first().track.title)
            assertEquals(
                "the same song must still be playing",
                "Sienna",
                moved.playing?.track?.title,
            )
            assertEquals(1, moved.queue.currentIndex)
        }
        assertTrue(controller.commands.contains("moveQueueItem(6,0)"))
    }

    @Test
    fun `a move is applied locally before the session echoes it`() = runTest {
        // The session is deliberately silent: a drag must still look instant.
        controller.echoMoves = false
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()

            viewModel.moveItem(fromIndex = 1, toIndex = 3)

            val moved: CrateUiState = awaitItem()
            assertEquals(
                listOf("q1", "q3", "q4", "q2", "q5", "q6", "q7"),
                moved.queue.items.map { it.id },
            )
        }
        assertEquals(listOf("moveQueueItem(1,3)"), controller.commands)
    }

    @Test
    fun `a stale index is ignored rather than clamped`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()

            viewModel.moveItem(fromIndex = 6, toIndex = 99)

            expectNoEvents()
        }
        assertTrue("nothing should have been sent", controller.commands.isEmpty())
    }

    @Test
    fun `an accessible move up is the same move a drag makes`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()

            // "Move up in the crate" on the third row of Up next, which is queue index 3.
            viewModel.moveItem(fromIndex = 3, toIndex = 2)

            var moved: CrateUiState = awaitItem()
            while (moved.queue.items[2].id != "q4") moved = awaitItem()
            assertEquals("Time (You and I)", moved.upNext[1].track.title)
        }
        assertEquals(listOf("moveQueueItem(3,2)"), controller.commands)
    }

    @Test
    fun `tapping a row jumps by id, never by index`() = runTest {
        val viewModel = CrateViewModel(controller)
        viewModel.skipTo("q5")

        assertEquals(listOf("skipToQueueItem(q5)"), controller.commands)
    }

    @Test
    fun `clear empties the crate and stops`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()

            viewModel.clear()

            val cleared: CrateUiState = awaitItem()
            assertTrue(cleared.isEmpty)
            assertNull(cleared.playing)
            assertEquals("0 tracks", cleared.upNextSummary)
        }
        assertEquals(listOf("clearQueue"), controller.commands)
    }

    @Test
    fun `an up next position maps onto the whole queue`() {
        val state = CrateUiState(queue = PlayerFixtures.crate)
        assertEquals(1, state.queueIndexOfUpNext(0))
        assertEquals(6, state.queueIndexOfUpNext(5))
        assertEquals(-1, state.queueIndexOfUpNext(6))
    }

    @Test
    fun `with nothing loaded the whole crate is up next`() {
        val queue = PlayQueue(items = PlayerFixtures.crate.items, currentIndex = null)
        val state = CrateUiState(queue = queue)

        assertNull(state.playing)
        assertEquals(7, state.upNext.size)
        assertEquals(0, state.queueIndexOfUpNext(0))
    }

    @Test
    fun `the playing row's glyph follows the session, not the queue`() = runTest {
        val viewModel = CrateViewModel(controller)

        viewModel.state.test {
            var state: CrateUiState = awaitItem()
            if (state.isEmpty) state = awaitItem()
            assertEquals(false, state.isPlaying)

            controller.emitState(PlaybackState(currentItem = PlayerFixtures.playingItem, isPlaying = true))
            assertEquals(true, awaitItem().isPlaying)
        }
    }
}

/**
 * How long an optimistically-applied reorder is still the truth.
 *
 * Split out of the view model precisely so it can be asserted like this: it decides whether a row
 * stays where the finger left it or springs back, which is the difference between a drag that feels
 * solid and one that flickers.
 */
class CrateReconcilerTest {

    private val queue: PlayQueue = PlayerFixtures.crate
    private val reordered: PlayQueue = queue.withItemMoved(6, 0)

    @Test
    fun `with nothing pending there is nothing to keep`() {
        assertNull(CrateReconciler.keep(pending = null, upstream = queue))
    }

    @Test
    fun `the echo landing drops the local copy`() {
        assertNull(CrateReconciler.keep(pending = reordered, upstream = reordered))
    }

    @Test
    fun `the session not having caught up keeps it`() {
        assertEquals(reordered, CrateReconciler.keep(pending = reordered, upstream = queue))
    }

    @Test
    fun `the crate changing underneath drops it`() {
        val withOneMore: PlayQueue = queue.withItemsInserted(
            newItems = listOf<QueueItem>(PlayerFixtures.item("q8", PlayerFixtures.pelota)),
        )
        assertNull(CrateReconciler.keep(pending = reordered, upstream = withOneMore))
    }

    @Test
    fun `a row being consumed drops it too`() {
        val shorter: PlayQueue = queue.withItemRemoved("q4")
        assertNull(CrateReconciler.keep(pending = reordered, upstream = shorter))
    }
}
