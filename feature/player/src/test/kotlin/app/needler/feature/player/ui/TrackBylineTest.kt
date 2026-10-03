package app.needler.feature.player.ui

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.player.screenshot.PlayerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player's artist line, in both of its states.
 *
 * The device audit filed it as "plain grey text, no route to album or artist - yet the album screen
 * renders the same artist as a blue link". It is a link now, and the half of that worth guarding is the
 * other half: `Track` carries an artist *name* and no identity, so the destination is resolved through
 * the album and is null until the mirror answers - and for an album the mirror has no artist MBID for it
 * stays null forever. An accent-blue link that does nothing when tapped is a worse screen than the grey
 * text it replaced, so the capability has to drive the styling and not the other way round.
 *
 * These assertions are the pair: a reachable artist is clickable and labelled, an unreachable one is
 * neither. The colour is checked by the committed screenshots; what is checked here is the click action,
 * because that is the part that can be wrong without the image changing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class TrackBylineTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a resolved artist is a labelled link that navigates`() {
        var opened = 0
        showing(onOpenArtist = { opened++ })

        val artist = compose.onNodeWithContentDescription("Go to The Marias")
        artist.assertHasClickAction()
        artist.performClick()
        assertEquals(1, opened)

        // The album half is never a link - the player is already showing the album - and keeps the dot.
        compose.onNodeWithText("· Submarine").assertIsDisplayed()
    }

    /**
     * The link is a legal target, which a single line of player type is not on its own.
     *
     * REQUIREMENTS.md "Accessibility" puts the floor at 48dp. One line of `bodyLarge` measures about
     * 20dp, so this was a little under half of it - and the reason nobody caught it by eye is that the
     * node it replaced was dead text, which has no target to be too small. The floor is on the
     * clickable node rather than on a parent, which is why the assertion can see it at all: a 48dp box
     * drawn *around* a 20dp target leaves this height reading 20dp, and the press reading as a miss.
     */
    @Test
    fun `the link is a 48dp target`() {
        showing(onOpenArtist = {})

        compose.onNodeWithContentDescription("Go to The Marias").assertHeightIsAtLeast(48.dp)
    }

    /** Still true at 200% text, where the name wraps to two lines and the target grows rather than
     * shrinks. */
    @Test
    fun `the link survives 200 percent text`() {
        showing(onOpenArtist = {}, fontScale = 2f)

        compose.onNodeWithContentDescription("Go to The Marias").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `an artist with no MBID is plain text, not a link that does nothing`() {
        showing(onOpenArtist = null)

        // The name is still there. Only the capability is gone.
        val artist = compose.onNodeWithText("The Marias")
        artist.assertIsDisplayed()
        artist.assertHasNoClickAction()

        // And the label the link would have carried is absent from the tree entirely, so TalkBack never
        // offers a destination there is no route to.
        assertEquals(
            "an unreachable artist must not carry the link's own label",
            0,
            compose.onAllNodesWithContentDescription("Go to The Marias").fetchSemanticsNodes().size,
        )
    }

    private fun showing(onOpenArtist: (() -> Unit)?, fontScale: Float = 1f) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    TrackByline(
                        artistName = "The Marias",
                        albumTitle = "Submarine",
                        onOpenArtist = onOpenArtist,
                        style = NeedlerTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
