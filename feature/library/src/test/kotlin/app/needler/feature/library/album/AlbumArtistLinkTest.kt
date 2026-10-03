package app.needler.feature.library.album

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.AlbumState
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The artist link in the album's title block, measured.
 *
 * ## What this guards
 *
 * REQUIREMENTS.md "Accessibility" puts the floor for any control at 48dp. The link is one line of
 * `bodyStrong`, which measures about 20dp, so for as long as it had no height of its own it was a
 * little under half the legal target - on a screen where the two controls either side of it, Play and
 * Shuffle, are 52dp.
 *
 * It is invisible to the screenshot suite twice over. A 20dp target and a 48dp one drawn with the text
 * centred in it differ by 28dp of empty space on a screen that scrolls, which `assertRendered` cannot
 * see; and a target raised by a 48dp box *around* the text would look identical to one raised on the
 * text's own node while behaving differently - the first leaves the press landing outside the thing
 * that takes it. So the assertion is on the height of the node that carries the click, which is the one
 * place where getting it wrong shows up.
 *
 * ## Why the click action is asserted beside the height
 *
 * Because the floor is applied inside the branch that makes the text a link at all - an album whose
 * artist the mirror has no MBID for gets neither, and must keep getting neither. A test that checked
 * only the height would pass if the modifier chain were re-ordered so the 48dp applied to every album
 * and the gesture to none, which is a dead 48dp target: worse than the small live one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class AlbumArtistLinkTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the artist link is a 48dp target that navigates`() {
        var opened = 0
        showAlbum(onOpenArtist = { opened++ })

        val link = compose.onNodeWithContentDescription(ARTIST_LINK)
        link.assertHeightIsAtLeast(48.dp)
        link.assertHasClickAction()
        link.performClick()

        assertEquals(1, opened)
    }

    /** Still a legal target once the name has wrapped to two lines at 200% text. */
    @Test
    fun `the artist link survives 200 percent text`() {
        showAlbum(fontScale = 2f)

        compose.onNodeWithContentDescription(ARTIST_LINK).assertHeightIsAtLeast(48.dp)
    }

    private fun showAlbum(fontScale: Float = 1f, onOpenArtist: () -> Unit = {}) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(PHONE_WIDTH)
                            .requiredHeight(PHONE_HEIGHT),
                    ) {
                        AlbumScreen(
                            state = AlbumUiState(
                                loading = false,
                                album = SampleLibrary.submarine.copy(state = AlbumState.Owned),
                            ),
                            widthSizeClass = WindowWidthSizeClass.Compact,
                            onBack = {},
                            onPlayPause = {},
                            onShuffle = {},
                            onPlayTrack = {},
                            onAddToCrate = {},
                            onAddTrackToCrate = { _, _ -> },
                            onPull = {},
                            onCancelPull = {},
                            onRetryPull = {},
                            onDownloadToDevice = {},
                            onRemoveFromDevice = {},
                            onRetryTrack = {},
                            onOpenArtist = onOpenArtist,
                            onDismissNotice = {},
                            onToggleFavourite = {},
                            onToggleTrackFavourite = {},
                            onMonitorArtistChange = {},
                            onConfirmRequest = {},
                            onDismissRequestSheet = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** `design/html/04-AlbumOwned.html`, the artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp
        val PHONE_HEIGHT = 844.dp

        /** `SampleLibrary.submarine` carries an artist slug, so the link is live. */
        const val ARTIST_LINK = "Go to The Marías"
    }
}
