package app.needler.feature.player.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Track
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
 * The player's answer to an album with no cover, which used to be a different answer from everywhere
 * else in the app.
 *
 * The device audit found Illinois and Hello Nasty drawing a flat tinted square in the player and the
 * album's initial over a derived tint on the library grid and in search. One absence, two pictures,
 * and the player's was the one that reads as a failed image load. [PlayerArtwork] now calls
 * `:core:design`'s `NeedlerArtwork`, so there is one fallback in the app rather than two.
 *
 * ## Why these assertions and not a screenshot
 *
 * There are screenshots, and they are what shows that the hero sleeve looks right. What they cannot
 * show is *why* it is right: every fixture in this module carries no artwork, because a JVM render
 * cannot resolve an `ArtworkRef` to a URL, so the old flat square was already in every committed PNG
 * and nobody read it as a defect. An image of a tinted square and an image of a cover that has not
 * arrived are the same image. So the letter is asserted on by name here, and the coverless case is
 * asserted beside the case where a cover exists and has not arrived - a pair that a branch on
 * `artwork == null`, which is what shipped, cannot pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [PlayerScreenshots.SDK],
    application = Application::class,
    // Pinned so the measurements below are arithmetic rather than whatever Robolectric's default
    // device happens to be: xhdpi gives two pixels per dp, and the window is tall enough to lay the
    // 270 dp hero out beside a 56 dp thumbnail without either being constrained.
    qualifiers = "w411dp-h891dp-xhdpi",
)
class PlayerArtworkTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an album with no cover draws its initial, not a blank square`() {
        showing(PlayerFixtures.illinois, HERO)

        compose.onNodeWithText(INITIAL, useUnmergedTree = true).assertExists()
        // And it still names the record, so a screen reader is told what the letter stands for.
        compose.onNodeWithContentDescription(SPOKEN).assertExists()
    }

    /**
     * The pair that pins the fix.
     *
     * An album whose cover exists and has not arrived has to draw the same thing as an album with no
     * cover at all, because nothing at this layer can tell them apart: Coil reports neither case
     * here. A fallback branched on `artwork == null` passes the test above and fails this one.
     */
    @Test
    fun `an album whose cover has not arrived draws the same initial`() {
        showing(PlayerFixtures.illinoisWithCover, HERO)

        compose.onNodeWithText(INITIAL, useUnmergedTree = true).assertExists()
    }

    /**
     * Nothing playing is not a coverless album.
     *
     * There is no release group to derive a tint from and no title to take a letter from, so the slot
     * stays the pack's flat placeholder and `ArtworkOnRecord` writes "Nothing playing" across it. A
     * letter here would be an invention about what is loaded.
     */
    @Test
    fun `nothing playing draws no letter and says nothing`() {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                PlayerArtwork(
                    artwork = null,
                    identity = null,
                    albumTitle = null,
                    artistName = null,
                    modifier = Modifier.size(HERO),
                )
            }
        }

        assertEquals(
            "the empty slot must carry no letter",
            0,
            compose.onAllNodesWithText(INITIAL, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    /**
     * The scale question: a placeholder tuned for a 56 dp row thumbnail drawn at the player's 270 dp.
     *
     * `NeedlerArtworkPlaceholder` sizes the glyph from the tile's shorter side rather than from the
     * type scale, so the only thing worth asserting is that the fraction actually holds across the
     * range the app draws - a letter that scaled by the type scale instead would be a 20 sp glyph
     * lost in a 270 dp sleeve, and one with a fixed line height would be clipped at the top of it.
     * Both tiles are measured in one composition so the comparison is between two real layouts.
     *
     * It holds. Measured at xhdpi: a 112 px tile carries a 53 px glyph and a 540 px tile carries a
     * 253 px one - 47 per cent of the tile at both ends, against a tile that grew by 4.82 and a
     * glyph that grew by 4.77. Nothing in `NeedlerArtworkPlaceholder` needed changing for the
     * player's size, which is the answer to "a placeholder tuned for a 56 dp thumbnail may not hold
     * up at 300 dp": it is sized from the tile and not from the type scale, and `avatarInitial`
     * carries no line height of its own for a 108 sp glyph to be clipped by.
     */
    @Test
    fun `the letter is the same fraction of the tile at row size and at player size`() {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Column {
                    Tile(THUMB)
                    Tile(HERO)
                }
            }
        }

        val tiles = compose.onAllNodesWithContentDescription(SPOKEN)
        val letters = compose.onAllNodesWithText(INITIAL, useUnmergedTree = true)
        assertEquals("both tiles should be in the tree", 2, tiles.fetchSemanticsNodes().size)
        assertEquals("both letters should be in the tree", 2, letters.fetchSemanticsNodes().size)

        val thumbTile: Int = tiles[0].fetchSemanticsNode().size.height
        val heroTile: Int = tiles[1].fetchSemanticsNode().size.height
        val thumbLetter: Int = letters[0].fetchSemanticsNode().size.height
        val heroLetter: Int = letters[1].fetchSemanticsNode().size.height

        // The glyph grows with the tile. Compared as a ratio rather than in pixels so the assertion
        // does not depend on which font the JVM resolved for the display family.
        val tileRatio: Float = heroTile.toFloat() / thumbTile.toFloat()
        val letterRatio: Float = heroLetter.toFloat() / thumbLetter.toFloat()
        assertTrue(
            "the letter did not scale with the tile: tiles grew by " + tileRatio +
                " and the letter by " + letterRatio,
            letterRatio > tileRatio * (1f - TOLERANCE) && letterRatio < tileRatio * (1f + TOLERANCE),
        )

        // And it fits, at both ends. A glyph taller than its tile is the clipping that a fixed line
        // height produces, and one that is a sliver of it is the type scale leaking back in.
        listOf(thumbLetter to thumbTile, heroLetter to heroTile).forEach { (letter, tile) ->
            assertTrue(
                "a letter of " + letter + " px does not fit a tile of " + tile + " px",
                letter < tile,
            )
            assertTrue(
                "a letter of " + letter + " px is lost in a tile of " + tile + " px",
                letter > tile / 4,
            )
        }
    }

    @Composable
    private fun Tile(side: Dp) {
        PlayerArtwork(
            artwork = null,
            identity = PlayerFixtures.illinois.releaseGroupMbid.value,
            albumTitle = PlayerFixtures.illinois.albumTitle,
            artistName = PlayerFixtures.illinois.artistName,
            modifier = Modifier.size(side),
        )
    }

    private fun showing(track: Track, side: Dp) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                PlayerArtwork(
                    artwork = track.artwork,
                    identity = track.releaseGroupMbid.value,
                    albumTitle = track.albumTitle,
                    artistName = track.artistName,
                    modifier = Modifier.size(side),
                )
            }
        }
    }

    private companion object {
        /** The sleeve on Now Playing (07). */
        val HERO: Dp = 270.dp

        /** The crate row and the mini player: `artworkThumb`. */
        val THUMB: Dp = 56.dp

        /** Illinois, so `I`. */
        const val INITIAL: String = "I"

        const val SPOKEN: String = "Illinois by Sufjan Stevens"

        /**
         * How far the two ratios may differ.
         *
         * Not zero: a font size in sp is rounded to whole pixels when the text is laid out, and at
         * 22 sp that rounding is a larger share of the glyph than it is at 108 sp. Five per cent is
         * well inside that and nowhere near the factor a scaling bug would be out by.
         */
        const val TOLERANCE: Float = 0.05f
    }
}
