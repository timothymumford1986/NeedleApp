package app.needler.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.nowplaying.MiniPlayer
import app.needler.ui.navigation.NeedlerDestination
import app.needler.ui.navigation.NeedlerNavigationScaffold
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mini player where it actually sits: between a destination and the bottom bar.
 *
 * ## The gap this fills
 *
 * There were four `player-mini-*` goldens and eight `nav-*` goldens, and no image anywhere with both
 * things in it. The four mini-player images are `:feature:player`'s, taken on a 390x80dp frame with
 * the bar alone on it - which is the right way to look at the card's own layout and says nothing at
 * all about the two questions that only arise once it is installed: whether it stacks *above* the
 * bottom bar or draws over it, and whether the destination's content ends above it or runs
 * underneath. [NeedlerNavigationScaffold] has had a `miniPlayer` slot since it was written and
 * [NavigationScreenshotTest] has never passed anything to it, so the eight nav goldens are all of
 * the empty case.
 *
 * ## Why this is a separate file
 *
 * [NavigationScreenshotTest] renders every destination at both widths from one loop, and its whole
 * argument is that one composable produces all eight. Threading a mini player through that loop
 * would mean either eight more images of a card that does not change between destinations, or a flag
 * that makes the loop no longer a loop. One image, of the one destination where the question is
 * live, is the proportionate answer.
 *
 * Library is that destination: it is the first screen and the only one whose content reaches the
 * bottom of the window, so a card overlaying it covers album rows. The expanded width is not
 * rendered at all - at expanded width the scaffold has no bottom bar and the player is the 400dp
 * sidebar, which `:feature:player` screenshots at its own width.
 *
 * ## Why the real MiniPlayer and a hand-built state
 *
 * The real composable, because a stand-in of the right height would answer the layout question and
 * none of the drawing ones - whether the card's raised surface reads against the bar's, whether its
 * hairline doubles up with the bar's top edge. `:feature:player`'s `PlayerFixtures` is in that
 * module's *test* source set and so is not reachable from here, which is why [PLAYING] is typed out
 * below rather than borrowed. It is deliberately the same record the pack draws, so this card and
 * `player-mini-phone.png` can be read as the same card in two places.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class MiniPlayerOverNavScreenshotTest {

    @Test
    fun `the mini player above the bottom bar on a phone`() {
        capture("nav-library-mini-player", fontScale = 1f)
    }

    /**
     * The same at 200% text, where the card and the bar grow at once.
     *
     * Both are fixed-height chrome in the pack - a 64dp card over a bottom bar with labels under its
     * icons - so this is where the two of them together take a quarter of the window, and where a
     * card that grew without the bar giving ground would sit on top of the labels. The isolated
     * `player-mini-large-text-phone.png` renders the card on an 80dp frame, which cannot show what it
     * does to the bar below it.
     */
    @Test
    fun `the mini player above the bottom bar at 200 percent text`() {
        capture("nav-library-mini-player-large-text", fontScale = 2f)
    }

    private fun capture(name: String, fontScale: Float) {
        val file = captureNeedlerScreen(name, NeedlerDevice.Phone, fontScale) {
            NeedlerNavigationScaffold(
                widthSizeClass = WindowWidthSizeClass.Compact,
                selected = NeedlerDestination.Library,
                onSelect = {},
                pullsBadgeCount = PULLS_BADGE,
                miniPlayer = {
                    MiniPlayer(state = PLAYING, onExpand = {}, onPlayPause = {}, onNext = {})
                },
                // Never drawn at Compact width - the scaffold does not call the slot - but the
                // parameter has no default, and `NavigationScreenshotTest` passes it unconditionally
                // for the same reason.
                sidebar = {},
            ) {
                DestinationFiller(NeedlerDestination.Library)
            }
        }
        assertRendered(file, NeedlerDevice.Phone)
    }

    private companion object {
        /** The count the pack draws on the Pulls item, on every screen. */
        const val PULLS_BADGE = 2

        private const val SUBMARINE = "marias-submarine"

        /** Sienna, from Submarine, which is the track the pack's own mini player draws. */
        val SIENNA: Track = Track(
            key = TrackKey(
                releaseGroupMbid = ReleaseGroupMbid(SUBMARINE),
                discNumber = 1,
                trackNumber = 1,
            ),
            title = "Sienna",
            artistName = "The Marias",
            albumTitle = "Submarine",
            durationMs = 200_000L,
            fetch = TrackFetchHandle(
                fileId = FileId(SUBMARINE + "-1"),
                sizeBytes = null,
                durationMs = 200_000L,
                format = AudioFormat.FLAC,
                bitrateKbps = null,
            ),
            // Null, as `:feature:player`'s own fixtures are: nothing below `:app`'s image loader can
            // turn an `ArtworkRef` into a URL, so a ref would render as the same letter placeholder
            // and only suggest that it had been resolved.
            artwork = null,
        )

        val PLAYING = PlayerUiState(
            item = QueueItem(id = "q1", track = SIENNA),
            isPlaying = true,
            durationMs = 200_000L,
            output = OutputTarget.Bluetooth(
                id = "bt-living-room",
                displayName = "Living room speaker",
                isConnected = true,
            ),
            upNextCount = 6,
        )
    }
}
