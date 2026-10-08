package app.needler.feature.library.genres

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Genre
import app.needler.feature.library.screenshot.NeedlerDevice
import app.needler.feature.library.screenshot.NeedlerScreenshots
import app.needler.feature.library.screenshot.TabletFrame
import app.needler.feature.library.screenshot.assertRendered
import app.needler.feature.library.screenshot.captureNeedlerScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the genres list.
 *
 * The list is alphabetical here because these are the images a reader compares
 * against the product, and REQUIREMENTS.md orders genres alphabetically — unlike
 * the ViewModel tests, which deliberately feed an unsorted list to prove the screen
 * does not sort.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class GenresScreenshotTest {

    @Test
    fun `a shelf of genres`() {
        capture("genres", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        capture("genres", NeedlerDevice.Tablet, LOADED)
    }

    @Test
    fun `offline, where it reads exactly the same`() {
        capture("genres-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `nothing tagged`() {
        capture("genres-empty", NeedlerDevice.Phone, GenresUiState(loading = false))
    }

    @Test
    fun `loading`() {
        capture("genres-loading", NeedlerDevice.Phone, GenresUiState(loading = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("genres-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    /**
     * The shelf with names long enough to break it, including the composite the mirror splits.
     *
     * Every other fixture on this screen is one or two short words, which is why no image here could
     * say anything about the fault the mirror was changed for. The device reported 138 genres, one of
     * them the single string `Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock` - a
     * Subsonic `genre` field carrying five tags that nothing split - and
     * `GenreCodec.splitComposite` is the fix. `GenreCodecTest` asserts the split; nothing asserted
     * what the shelf does if one arrives anyway, and one can: the codec runs on the write path, so a
     * row written before the migration, or by a server sending a separator the codec does not know,
     * reaches this screen whole.
     *
     * So [PATHOLOGICAL] is the state the fix exists to prevent plus the state it cannot prevent - a
     * genuinely long single tag - and the image is the answer to "is this legible, or does one row
     * take the screen". Both are rendered at 200% text as well, which is where a chip sized to
     * "Jazz" meets a name of sixty characters.
     */
    @Test
    fun `names long enough to break the shelf`() {
        capture(
            "genres-long-names",
            NeedlerDevice.Phone,
            GenresUiState(loading = false, genres = PATHOLOGICAL),
        )
    }

    @Test
    fun `names long enough to break the shelf, at 200 percent text size`() {
        capture(
            "genres-long-names-large-text",
            NeedlerDevice.Phone,
            GenresUiState(loading = false, genres = PATHOLOGICAL),
            fontScale = 2f,
        )
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: GenresUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            when (device) {
                NeedlerDevice.Phone -> Screen(state, WindowWidthSizeClass.Compact)
                NeedlerDevice.Tablet -> TabletFrame {
                    Screen(state, WindowWidthSizeClass.Expanded)
                }
            }
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: GenresUiState, widthSizeClass: WindowWidthSizeClass) {
        GenresScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onGenreClick = {},
            onSyncNow = {},
        )
    }

    private companion object {

        /**
         * The composite string the device reported, unsplit.
         *
         * Not an invented worst case: this is the exact value `GenreCodec`'s own KDoc and
         * `GenreCodecTest` quote, from a server whose `genre` field held five tags separated by
         * semicolons.
         */
        const val COMPOSITE: String =
            "Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock"

        /**
         * One real long tag, the composite, and two short ones for scale.
         *
         * The short rows are the point of the mixture rather than padding: a long name that pushes
         * its count off the row, or wraps to four lines, is only visible beside a row that does not.
         */
        val PATHOLOGICAL: List<Genre> = listOf(
            Genre(name = COMPOSITE, albumCount = 1),
            Genre(name = "Alternative Dance", albumCount = 6),
            Genre(name = "Experimental Electronic And Ambient Soundscapes", albumCount = 3),
            Genre(name = "Jazz", albumCount = 5),
        )

        val LOADED = GenresUiState(
            loading = false,
            genres = listOf(
                Genre(name = "Alternative rock", albumCount = 31),
                Genre(name = "Dream pop", albumCount = 12),
                Genre(name = "Indie folk", albumCount = 8),
                Genre(name = "Jazz", albumCount = 5),
                Genre(name = "Neo-soul", albumCount = 9),
                Genre(name = "Psychedelic soul", albumCount = 4),
                // Zero, not unknown: `observeGenres` folds the album mirror's genre column,
                // so every genre it returns has a counted number behind it. A row that drew
                // nothing here was the blank in `genres-phone.png`, against a `0 tracks` on the
                // genre screen for the same genre.
                Genre(name = "Shoegaze", albumCount = 0),
                Genre(name = "Soul", albumCount = 17),
            ),
        )
    }
}
