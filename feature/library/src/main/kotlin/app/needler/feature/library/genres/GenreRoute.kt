package app.needler.feature.library.genres

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of one genre's screen.
 *
 * `:app` builds this route as `genre/{genre}`, where the argument name is
 * [GenreViewModel.GENRE_ARG] and the value is the genre's name, URL-encoded by the
 * host. The name is the identity: `getSongsByGenre` and the mirror both key on it,
 * and the Subsonic `ge-<slug>` id is a slug of the name that nothing in this app
 * needs to know about.
 *
 * Navigation is the host's business, so [onBack] is a callback; everything else is
 * playback, which is a method on [GenreViewModel] because it goes to the shared
 * session through the domain's controller.
 */
@Composable
fun GenreRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GenreScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onBack = onBack,
        onPlayAll = viewModel::onPlayAll,
        onShuffleAll = viewModel::onShuffleAll,
        onPlayTrack = viewModel::onPlayTrack,
        onShowMore = viewModel::onShowMore,
        onSyncNow = viewModel::onSyncNow,
        modifier = modifier,
    )
}
