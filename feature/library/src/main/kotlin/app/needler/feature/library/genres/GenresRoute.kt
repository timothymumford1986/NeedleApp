package app.needler.feature.library.genres

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of the genres list.
 *
 * `:app` builds this route as `genres`. It takes no arguments.
 *
 * [onOpenGenre] is handed the genre's **name**, because that is its identity
 * everywhere it matters: `LibraryRepository.observeTracksByGenre` takes a name, the
 * mirror stores names in the album's genre column, and the Subsonic `ge-<slug>` id
 * is a slug of the name that nothing in this app ever needs. The host must encode
 * the name into the route it builds — a genre can legitimately contain a space, an
 * ampersand or a slash ("Drum & bass", "Rock/Pop"), and an unencoded slash would
 * silently become a second path segment and match no destination.
 */
@Composable
fun GenresRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenGenre: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenresViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GenresScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onGenreClick = { genre -> onOpenGenre(genre.name) },
        modifier = modifier,
    )
}
