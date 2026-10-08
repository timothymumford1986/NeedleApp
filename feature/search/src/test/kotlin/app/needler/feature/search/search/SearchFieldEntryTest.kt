package app.needler.feature.search.search

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.search.screenshot.NeedlerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the search field does with the query that was already in it.
 *
 * ## The defect
 *
 * `search` is a bottom-navigation destination, so leaving the tab disposes the
 * screen but not [SearchViewModel], and REQUIREMENTS.md "Search behaviour" has
 * the field running its query live from the first keystroke. Both are deliberate:
 * `SearchViewModel.SUBSCRIPTION_TIMEOUT_MS` exists so that "a rotation, or a trip
 * into an album and straight back, does not lose the user's search". What was
 * wrong was the caret. `BasicTextField`'s `String` overload seeds its selection
 * to `TextRange(0)`, so arriving back at the field put the caret at index 0 and
 * the next keystroke **prepended**: typing "beastie" over a previous "dido"
 * searched for "beastiedido", found nothing, and looked like a fault in search.
 *
 * The fix hands the field a `TextFieldValue` with the stale query selected, so
 * the first keystroke replaces it. Clearing the query on arrival was rejected -
 * see `NeedlerSearchField`'s `TextFieldValue` overload - because the effect that
 * would do it re-fires after a configuration change, which is the rotation the
 * KDoc above promises not to lose, and because it throws away a query the user
 * may have come back to edit.
 *
 * ## Why these are driven rather than rendered
 *
 * Every assertion here is on the field's **selection** or on the text left after
 * a real keystroke. The whole defect is one invisible index: "dido" selected and
 * "dido" with the caret in front of it are the same pixels, and the screenshots
 * in `SearchScreenshotTest` pass either way - they would have been re-recorded as
 * the new truth by the same command that changed them. This is the only place the
 * behaviour is checkable.
 *
 * `application = Application::class` keeps Hilt out of it, as in the screenshot
 * tests: the stateless [SearchScreen] is driven from a literal [SearchUiState],
 * with the state the ViewModel would hold kept in [query] so a test can change it
 * the way a suggestion tap or the clear button would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class SearchFieldEntryTest {

    @get:Rule
    val compose = createComposeRule()

    /** The ViewModel's query, which is the one thing that outlives this screen. */
    private val query: MutableState<String> = mutableStateOf("")

    /**
     * Bumped to throw the composition away and build a fresh one over the same
     * [query] - which is a rotation, and is also a trip into an album and back:
     * both dispose the screen and keep the ViewModel.
     */
    private val generation: MutableState<Int> = mutableStateOf(0)

    // ---- arrival ------------------------------------------------------------

    @Test
    fun `arriving with the last query still in it offers that query selected`() {
        showing("dido")

        assertEquals("the query must not be cleared on arrival", "dido", fieldText())
        assertEquals(
            "the whole query has to be selected, or the first keystroke prepends to it",
            TextRange(0, 4),
            fieldSelection(),
        )
    }

    /**
     * The regression. On the `String` overload this produced "beastiedido".
     *
     * `performTextInput` goes through the field's own insert-at-cursor path, the
     * one the IME uses, so what it does with a selection is what a keyboard does
     * with it rather than something this test decided.
     */
    @Test
    fun `the first keystroke replaces a stale query instead of prepending to it`() {
        showing("dido")

        field().performTextInput("beastie")

        assertEquals("beastie", fieldText())
        assertEquals("and the ViewModel is told to search for exactly that", "beastie", query.value)
    }

    @Test
    fun `arriving with nothing typed leaves an ordinary empty field`() {
        showing("")

        assertEquals("", fieldText())
        assertEquals(TextRange(0, 0), fieldSelection())
        assertEquals("the placeholder is drawn", 1, nodesWithText(PLACEHOLDER))
        assertEquals("and there is nothing to clear", 0, nodesWithDescription(CLEAR))

        field().performTextInput("dido")

        assertEquals("dido", fieldText())
        assertEquals("dido", query.value)
        assertEquals("the placeholder is gone", 0, nodesWithText(PLACEHOLDER))
    }

    // ---- the query that must not be lost ------------------------------------

    @Test
    fun `a rotation, or a trip into an album and back, keeps the query and selects it again`() {
        showing("dido")
        field().performTextInput("beastie boys")
        assertEquals("beastie boys", fieldText())

        recreate()

        assertEquals(
            "SUBSCRIPTION_TIMEOUT_MS keeps this query on purpose; the screen must show it",
            "beastie boys",
            fieldText(),
        )
        assertEquals(
            "and a fresh arrival selects it, exactly as the first one did",
            TextRange(0, "beastie boys".length),
            fieldSelection(),
        )
    }

    // ---- the ViewModel changing the text underneath -------------------------

    @Test
    fun `the clear button leaves the field genuinely empty, with the caret in it`() {
        showing("dido")

        compose.onNodeWithContentDescription(CLEAR).performClick()

        assertEquals("", query.value)
        assertEquals("", fieldText())
        assertEquals(TextRange(0, 0), fieldSelection())
        assertEquals("the clear button goes with the text", 0, nodesWithDescription(CLEAR))
        assertEquals("the placeholder comes back", 1, nodesWithText(PLACEHOLDER))

        field().performTextInput("beastie")

        assertEquals("beastie", fieldText())
    }

    /**
     * A completion is a starting point, not something to overtype, so it arrives
     * with the caret after it rather than selected. Same path as a recent-search
     * row: both reach this screen only as a changed [SearchUiState.query].
     */
    @Test
    fun `a suggestion lands with the caret after it, so the next character appends`() {
        showing("beast")

        setQueryFromViewModel("beastie boys")

        assertEquals("beastie boys", fieldText())
        assertEquals(TextRange(12, 12), fieldSelection())

        field().performTextInput("!")

        assertEquals("beastie boys!", fieldText())
    }

    /**
     * The re-seed must not fire on the recomposition a keystroke itself causes.
     * If it did, every character would drag the caret back to the end of the text
     * and editing a query in place would be impossible - a worse defect than the
     * prepend. See `SearchHeader` for why the comparison is a keyed effect.
     */
    @Test
    fun `typing in the middle of a query edits it there`() {
        showing("dido")

        field().performTextInputSelection(TextRange(2))
        field().performTextInput("X")

        assertEquals("diXdo", fieldText())
    }

    // ---- plumbing -----------------------------------------------------------

    /** Puts the screen up with [initial] in the ViewModel, as a tab switch would. */
    private fun showing(initial: String) {
        query.value = initial
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                key(generation.value) {
                    SearchScreen(
                        state = SearchUiState(query = query.value),
                        widthSizeClass = WindowWidthSizeClass.Compact,
                        onQueryChange = { text -> query.value = text },
                        onClearQuery = { query.value = "" },
                        onSubmitQuery = {},
                        onCancel = {},
                        onRecentQuerySelect = { text -> query.value = text },
                        onClearRecentQueries = {},
                        onSuggestionSelect = { suggestion -> query.value = suggestion.text },
                        onRetrySearch = {},
                        onOpenSettings = {},
                        onArtistClick = {},
                        onAlbumClick = {},
                        onPull = {},
                        onStopPull = {},
                        onPlayTrack = {},
                        onAddTrackToCrate = { _, _ -> },
                        onAddAlbumToCrate = { _, _ -> },
                        onShowAll = {},
                        onLoadMore = {},
                        onMonitorArtistChange = {},
                        onConfirmPull = {},
                        onCancelPull = {},
                        onDismissNotice = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** What a suggestion row, or a recent search, does to this screen. */
    private fun setQueryFromViewModel(text: String) {
        compose.runOnIdle { query.value = text }
        compose.waitForIdle()
    }

    /** Throws the composition away and builds it again over the surviving query. */
    private fun recreate() {
        compose.runOnIdle { generation.value++ }
        compose.waitForIdle()
    }

    private fun field(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction())

    private fun fieldText(): String = field()
        .fetchSemanticsNode()
        .config
        .getOrElse(SemanticsProperties.EditableText) { AnnotatedString("") }
        .text

    private fun fieldSelection(): TextRange = field()
        .fetchSemanticsNode()
        .config
        .getOrElse(SemanticsProperties.TextSelectionRange) { TextRange.Zero }

    private fun nodesWithDescription(description: String): Int =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    private fun nodesWithText(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private companion object {
        /** The clear button's own label, from `NeedlerSearchField`. */
        const val CLEAR = "Clear search"

        /** Drawn only while the field is empty. */
        const val PLACEHOLDER = "Artist, album or song"
    }
}
