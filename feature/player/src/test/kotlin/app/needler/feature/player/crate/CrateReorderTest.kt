package app.needler.feature.player.crate

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.PlayQueue
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reordering the crate, read off the semantics tree rather than off a drag.
 *
 * The device audit reported that nothing on screen 08 moved a track. The gesture and the controller
 * command were both already there - `PlaybackController.moveQueueItem`, `PlayQueue.withItemMoved`,
 * `QueueReorderState` - and what was missing was a route to them that is not a long press and a
 * drag. REQUIREMENTS.md "Accessibility": "TalkBack must reach the crate's reordering through an
 * accessible action, not only by dragging."
 *
 * ## Why these assertions and not a screenshot or a synthetic drag
 *
 * A render cannot show a custom accessibility action, and a synthetic drag asserts the gesture
 * plumbing rather than the thing REQUIREMENTS.md asks for. These tests invoke the actions TalkBack's
 * local context menu invokes, through `SemanticsActions.CustomActions`, which is the same list
 * TalkBack reads. If the action disappears, or stops being wired to a move, or starts sending the
 * wrong pair of indices, exactly one of these fails.
 *
 * ## The rule being protected
 *
 * [CrateScreen] decides it: a row moves only within Up next, the Playing row never moves, and
 * nothing can be moved above it - because `PlayQueue.upNext` is everything after `currentIndex`, so
 * a row above the playing one would be drawn in neither of the screen's two sections. The last two
 * tests here are what stop that regressing in either direction: a Playing row that gains a move
 * action, or a first Up next row that gains one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [PlayerScreenshots.SDK],
    application = Application::class,
    // `design/html/08-Queue.html`: 390x844, as the *device* rather than as a box drawn on the
    // test runtime's smaller default. A `requiredWidth` wider than the root is centred and
    // overflows it, which puts part of every row outside the window a touch is injected into.
    qualifiers = "w390dp-h844dp-xhdpi",
)
class CrateReorderTest {

    @get:Rule
    val compose = createComposeRule()

    /** Every move the screen asked for, as the pair of whole-queue indices it sent. */
    private val moves: MutableList<Pair<Int, Int>> = mutableListOf()

    // ---- the move is reachable without a drag -------------------------------

    @Test
    fun `an up next row moves down from the semantics tree, with no drag`() {
        showing()

        invoke(row("Paranoia"), MOVE_DOWN)

        assertEquals(listOf(2 to 3), moves)
    }

    @Test
    fun `an up next row moves up from the semantics tree, with no drag`() {
        showing()

        invoke(row("Paranoia"), MOVE_UP)

        assertEquals(listOf(2 to 1), moves)
    }

    /**
     * The order the controller is told, not only the pair of indices.
     *
     * `CrateViewModel` sends the same two numbers straight to `PlaybackController.moveQueueItem`, so
     * what the pair *means* is whatever `PlayQueue.withItemMoved` makes of it. Applying it here is
     * what checks the screen's arithmetic against the queue rather than against itself: an off-by-one
     * in `CrateUiState.queueIndexOfUpNext` would still produce a plausible-looking pair.
     */
    @Test
    fun `the pair the screen sends moves that track and leaves the playing row alone`() {
        showing()

        invoke(row("Paranoia"), MOVE_UP)

        val (from: Int, to: Int) = moves.single()
        val moved: PlayQueue = PlayerFixtures.crate.withItemMoved(from, to)
        assertEquals(
            listOf("q1", "q3", "q2", "q4", "q5", "q6", "q7"),
            moved.items.map { it.id },
        )
        assertEquals("the playing row changed", "q1", moved.currentItem?.id)
    }

    // ---- the Playing row is an anchor ---------------------------------------

    @Test
    fun `the playing row offers no move at all`() {
        showing()

        assertEquals(emptyList<String>(), actionLabelsOf(row("Sienna")))
    }

    /**
     * Nothing can be moved above the playing row, from either route.
     *
     * The first Up next row is the one that would do it, so it is the one asserted. The crate here
     * is playing its *third* track, so there are two rows above the playing one already and a
     * "move up" from the top of Up next would land among them - where `PlayQueue.upNext` does not
     * reach and this screen draws nothing.
     */
    @Test
    fun `the first up next row cannot be moved above what is playing`() {
        showing(midCrate)

        val labels: List<String> = actionLabelsOf(row("Time (You and I)"))
        assertEquals(listOf(MOVE_DOWN), labels)
    }

    /** And the last row has nowhere below it, which is the same rule at the other end. */
    @Test
    fun `the last up next row offers only a move up`() {
        showing(shortCrate)

        assertEquals(listOf(MOVE_UP), actionLabelsOf(row("Paranoia")))
    }

    // ---- the drag reaches no further than the actions ------------------------

    /**
     * The gesture is restricted to match, which is the half of this that actually changed.
     *
     * The accessible actions never offered a move across the Playing row - the first Up next row has
     * had `onMoveUp = null` since it was written. The drag did: `isDraggable` accepted every row key
     * including the Playing row's, so a long press and an upward drag sent a move to a slot at or
     * above `currentIndex`, where `PlayQueue.upNext` does not reach and the crate screen draws
     * nothing. A track dragged there was still in the session's queue and had left the screen.
     *
     * The crate here is playing its third track, so there is somewhere above the Playing row for a
     * row to be dragged *to*. Nothing is sent, because there is no draggable row above it any more.
     */
    @Test
    fun `an up next row cannot be dragged above what is playing`() {
        showing(midCrate)

        drag(row("Time (You and I)"), -ONTO_PLAYING)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    /**
     * And the Playing row itself does not drag, for the mirror image of the same reason.
     *
     * Downwards, deliberately. There is nothing draggable *above* the Playing row in the lazy list -
     * only the "Playing" heading - so an upward drag of it sent no move even before the restriction
     * and an assertion about one would have had no teeth. Downwards is where the Up next rows are,
     * and a move that put the Playing row among them is what pushed the row it displaced into the
     * invisible prefix above `currentIndex`.
     */
    @Test
    fun `the playing row cannot be dragged`() {
        showing(midCrate)

        drag(row("Paranoia"), INTO_UP_NEXT)

        assertEquals(emptyList<Pair<Int, Int>>(), moves)
    }

    /**
     * A row that *can* move still moves on a drag, so the restriction has not simply killed the
     * gesture.
     *
     * Without this the two assertions above would pass against a `CrateRow` that attached no
     * pointer input at all.
     */
    @Test
    fun `a row with somewhere to go still drags`() {
        showing(midCrate)

        drag(row("Pelota"), -ONE_ROW_UP)

        assertEquals(listOf(4 to 3), moves)
    }

    /**
     * The handle goes with the gesture, read off the layout because it carries no semantics.
     *
     * `NeedlerDragHandleIcon` is `clearAndSetSemantics {}` - deliberately, it is decoration beside a
     * row that already describes itself - so its presence is not assertable from the semantics tree
     * and a screenshot of the right size is still the right size without it. What it does leave is
     * 14dp of glyph and the row's own 14dp gap, which pushes the title right. A row that drew a
     * handle it will not respond to would be the app promising a move it then refuses.
     */
    @Test
    fun `the playing row draws no handle, and an up next row does`() {
        showing(midCrate)

        val playing: Float = titleLeftOf("Paranoia")
        val queued: Float = titleLeftOf("Time (You and I)")
        assertTrue(
            "the playing row's title starts at " + playing + " and a queued row's at " + queued +
                ", so the handle is on the wrong row or on both",
            playing < queued,
        )
    }

    // ---- the handle matches the actions -------------------------------------

    /**
     * A lone Up next row gets no move action, because it has nowhere to go.
     *
     * Asserted because the alternative - a row that offers "Move down in the crate" and then does
     * nothing when it is invoked - is the failure mode `CrateRow`'s `canReorder` exists to prevent,
     * and it is invisible to every other test in this package.
     */
    @Test
    fun `a crate of two offers nothing to reorder`() {
        showing(
            PlayQueue(
                items = PlayerFixtures.crate.items.take(2),
                currentIndex = 0,
            ),
        )

        assertEquals(emptyList<String>(), actionLabelsOf(row("Hamptons")))
    }

    /**
     * A move invoked through the accessible route still reaches the screen's callback when the crate
     * is long enough for several, so the actions are not accidentally bound to one row's closure.
     */
    @Test
    fun `every up next row carries its own move`() {
        showing()

        invoke(row("Hamptons"), MOVE_DOWN)
        invoke(row("Time (You and I)"), MOVE_UP)

        assertEquals(listOf(1 to 2, 3 to 2), moves)
    }

    // ---- plumbing -----------------------------------------------------------

    /** The crate playing its third track, so two rows sit above the Playing row. */
    private val midCrate: PlayQueue
        get() = PlayQueue(items = PlayerFixtures.crate.items, currentIndex = 2)

    /** Three rows, so the last of Up next is on screen without scrolling. */
    private val shortCrate: PlayQueue
        get() = PlayQueue(items = PlayerFixtures.crate.items.take(3), currentIndex = 0)

    /**
     * A long press and an upward drag of one row, as a finger performs it.
     *
     * The clock is driven by hand because `detectDragGesturesAfterLongPress` waits on a timeout
     * before it accepts anything: with the clock advancing itself, the press and the move land in
     * the same frame and the gesture is read as a tap. The three `performTouchInput` blocks are one
     * gesture - the pointer stays down between them, because they share one node interaction.
     */
    private fun drag(node: SemanticsNodeInteraction, dy: Float) {
        compose.mainClock.autoAdvance = false
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS)
        node.performTouchInput { moveBy(Offset(0f, dy)) }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        node.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    /** Where one row's title starts, from the unmerged tree - the merged row has no title node. */
    private fun titleLeftOf(title: String): Float =
        compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun row(title: String): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(title + ",", substring = true)

    /**
     * The custom-action labels one row offers, in the order TalkBack would list them.
     *
     * An absent `CustomActions` entry is an empty list rather than a failure: a row with no move is
     * a state this screen deliberately has, and it must be assertable the same way as a row with
     * one.
     */
    private fun actionLabelsOf(node: SemanticsNodeInteraction): List<String> {
        val semantics: SemanticsNode = node.fetchSemanticsNode()
        val actions: List<CustomAccessibilityAction> =
            semantics.config.getOrElseNullable(SemanticsActions.CustomActions) { null }.orEmpty()
        return actions.map { it.label }
    }

    private fun invoke(node: SemanticsNodeInteraction, label: String) {
        val actions: List<CustomAccessibilityAction> =
            node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val action: CustomAccessibilityAction? = actions.firstOrNull { it.label == label }
        assertTrue(
            "no \"" + label + "\" action; the row offers " + actions.map { it.label },
            action != null,
        )
        assertTrue(label + " reported failure", action!!.action())
        compose.waitForIdle()
    }

    /**
     * The screen at the pack's phone artboard, 390x844.
     *
     * Sized rather than left to the test runtime's default, because a `LazyColumn` does not compose
     * a row it has not laid out: the whole crate is 642dp tall and the last of its rows is off a
     * shorter screen, so an assertion about that row would fail for the wrong reason. These tests
     * read the semantics tree, and a row has to exist in it to be read.
     */
    private fun showing(queue: PlayQueue = PlayerFixtures.crate) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CrateScreen(
                        state = CrateUiState(queue = queue, isPlaying = true),
                        onBack = {},
                        onPlayItem = {},
                        onMove = { from, to -> moves += from to to },
                        onRemove = {},
                        onClear = {},
                    )
                }
            }
        }
    }

    private companion object {
        const val MOVE_UP: String = "Move up in the crate"
        const val MOVE_DOWN: String = "Move down in the crate"

        /** Comfortably past the platform long-press timeout, which is 500ms. */
        const val LONG_PRESS_MS: Long = 1_000L
        const val FRAME_MS: Long = 32L

        /**
         * Pixels, at the xhdpi density these qualifiers set: a 64dp row is 128px.
         *
         * [ONE_ROW_UP] clears the row above by a little. [INTO_UP_NEXT] has to clear the Playing
         * row's own lower half *and* the "Up next" heading between the two sections, and anything
         * from there to the bottom of the last queued row is an equally good test, so it is set well
         * clear of the heading rather than exactly one row.
         */
        const val ONE_ROW_UP: Float = 140f
        const val INTO_UP_NEXT: Float = 300f

        /**
         * Up from the first Up next row onto the Playing row, across the heading between them.
         *
         * [ONE_ROW_UP] is not enough and the eight pixels it fell short by are the whole point: a
         * drag that stops inside the "Up next" heading finds no drop target either way, so an
         * assertion built on it would pass against an unrestricted drag and prove nothing. This
         * lands squarely on the Playing row, which is what the restriction has to refuse.
         */
        const val ONTO_PLAYING: Float = 220f
    }
}
