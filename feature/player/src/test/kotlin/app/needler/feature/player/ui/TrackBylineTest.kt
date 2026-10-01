package app.needler.feature.player.ui

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun showing(onOpenArtist: (() -> Unit)?) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
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
