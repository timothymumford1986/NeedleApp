package app.needler.feature.library.album

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ArtistMbid

/**
 * The stateful half of the album screen.
 *
 * Navigation is the host's business, so opening the artist is a callback rather
 * than something the ViewModel does. Everything else — playing, pulling,
 * downloading, retrying — is a method on [AlbumViewModel], because all of it is
 * a repository call whose result the screen has to render.
 *
 * `:app` builds this route as `album/{albumId}`, where the argument name is
 * [AlbumViewModel.ALBUM_ID_ARG] and the value is the bare release-group MBID —
 * **not** the `al-` prefixed Subsonic id. REQUIREMENTS.md "Identity model":
 * the release-group MBID is the join key, and the prefix belongs to the
 * Subsonic lane alone.
 */
@Composable
fun AlbumRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AlbumScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onBack = onBack,
        onPlay = viewModel::onPlay,
        onShuffle = viewModel::onShuffle,
        onPlayTrack = viewModel::onPlayTrack,
        onPull = viewModel::onPull,
        onCancelPull = viewModel::onCancelPull,
        onRetryPull = viewModel::onRetryPull,
        onDownloadToDevice = viewModel::onDownloadToDevice,
        onRemoveFromDevice = viewModel::onRemoveFromDevice,
        onRetryTrack = viewModel::onRetryTrack,
        onOpenArtist = { state.album?.artistMbid?.let(onOpenArtist) },
        onDismissNotice = viewModel::onDismissNotice,
        modifier = modifier,
    )
}
