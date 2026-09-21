package app.needler.feature.library.artist

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The stateful half of the artist screen.
 *
 * `:app` builds this route as `artist/{artistId}`, where the argument name is
 * [ArtistViewModel.ARTIST_ID_ARG] and the value is the bare artist MBID — not
 * the `ar-` prefixed Subsonic id.
 */
@Composable
fun ArtistRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ArtistScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onBack = onBack,
        onAlbumClick = onOpenAlbum,
        onPull = viewModel::onPull,
        modifier = modifier,
    )
}
