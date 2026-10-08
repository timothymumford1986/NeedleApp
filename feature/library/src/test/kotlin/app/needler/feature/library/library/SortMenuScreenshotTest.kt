package app.needler.feature.library.library

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerDevice
import app.needler.feature.library.screenshot.NeedlerScreenshots
import app.needler.feature.library.screenshot.assertRendered
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sort control with its menu open, which is the only image that says which sorts exist.
 *
 * ## Why no golden could answer that before
 *
 * `Played` was removed from [LibrarySort] because it "reports an order it did not apply" - the
 * enum's own notes say so - and the image set could not confirm the removal from any angle. Every
 * library golden draws the pill shut, with one word on it, and the words on the four rows behind it
 * appear in no committed PNG at all. A fifth option could be added back tomorrow and all 228
 * goldens would still pass.
 *
 * ## Why this is a file of its own, with a compose rule
 *
 * Two reasons, both mechanical. The expansion is `remember`ed inside `SortControl`, a private
 * composable of `LibraryScreen`, so the only way to open it is to press the pill - there is no state
 * to hand to [LibraryScreen]. And the menu is `NeedlerDropdownMenu`, which is Material's
 * `DropdownMenu`, which is a `Popup`: a *separate window*. Roborazzi's composable capture - what
 * `captureNeedlerScreen` uses, and what every other golden in this module is taken with - renders
 * one composition into one bitmap and would write the screen with the pill pressed and no menu on
 * it. [captureScreenRoboImage] is the call that walks Robolectric's window roots and composites all
 * of them, which is what makes the menu appear in the file.
 *
 * ## What is asserted beyond the pixels
 *
 * That the menu opened at all, by [assertMenuOpened], which re-captures the same screen shut and
 * fails if the committed image matches it. Without that this test degrades silently in exactly the
 * way the goldens it is fixing did: if the popup stops being composited, or the semantics action
 * stops reaching the pill, the capture becomes a second copy of `library-grid-phone.png` under a
 * name that promises a menu.
 *
 * `LibraryControlsTest` asserts the labels, the selected state and the callback. This asserts that a
 * reader of the image set can see them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [NeedlerScreenshots.SDK],
    application = Application::class,
    // The pack's phone artboard as the window. `captureNeedlerScreen` gets this from Roborazzi's own
    // size option; a rule-driven test has to ask Robolectric, or it lays out into the default
    // 320x470 and `captureScreenRoboImage` writes a 320x470 screen.
    qualifiers = "w390dp-h844dp-xhdpi",
)
class SortMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the sort menu, open, listing every sort the library has`() {
        compose.setContent { Screen() }
        compose.waitForIdle()

        val shut: File = File.createTempFile("needler-sort-menu-shut", ".png")
        captureScreenRoboImage(file = shut)

        // The semantics action rather than a tap: the pill's coordinates depend on the controls row
        // wrapping, and what is being recorded is the menu rather than the hit box.
        compose.onNodeWithContentDescription(SORT_PILL)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        val file = File(NeedlerScreenshots.outputDirectory, "library-sort-menu-phone.png")
        captureScreenRoboImage(file = file)

        assertRendered(file, NeedlerDevice.Phone)
        assertMenuOpened(golden = file, shut = shut)
    }

    @Composable
    private fun Screen() {
        NeedlerTheme(reducedMotion = true) {
            LibraryScreen(
                state = LOADED,
                widthSizeClass = WindowWidthSizeClass.Compact,
                onTabSelect = {},
                onSortSelect = {},
                onViewModeToggle = {},
                onSearchClick = {},
                onAlbumClick = {},
                onAlbumPlay = {},
                onArtistClick = {},
                onSongPlay = {},
                onOpenPlaylists = {},
                onOpenGenres = {},
                onAlbumAddToCrate = { _, _ -> },
                onSongAddToCrate = { _, _ -> },
                onDismissNotice = {},
                onSyncNow = {},
                scrollAnchor = AlbumScrollAnchor(),
            )
        }
    }

    /** Fails if the committed image is the screen with the menu shut. See the file's KDoc. */
    private fun assertMenuOpened(golden: File, shut: File) {
        assertFalse(
            golden.name + " is byte-identical to the same screen with the sort menu shut, so the " +
                "menu is not in it",
            golden.readBytes().contentEquals(shut.readBytes()),
        )
        shut.delete()
    }

    private companion object {
        /**
         * `SortControl`'s content description, which is `"Sort: " + sort.spokenLabel`.
         *
         * Spelled out rather than built from [LibrarySort.RECENT], so that renaming the spoken label
         * fails here loudly instead of quietly finding nothing to press.
         */
        const val SORT_PILL: String = "Sort: recently added"

        /** The pack's own library, as every other library golden draws it. */
        val LOADED = LibraryUiState(
            loading = false,
            stats = SampleLibrary.stats,
            albums = SampleLibrary.albums,
            artists = SampleLibrary.artists,
            renderedAt = SampleLibrary.renderedAt,
        )
    }
}
