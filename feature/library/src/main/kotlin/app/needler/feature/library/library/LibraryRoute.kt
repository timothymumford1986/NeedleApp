package app.needler.feature.library.library

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The stateful half of Library: holds the [LibraryViewModel] and turns taps
 * into navigation.
 *
 * Split from [LibraryScreen] for the same reason `ConnectRoute` is split from
 * `ConnectScreen`: the screen itself then needs neither Hilt nor a repository
 * to render, which is what lets every state be screenshotted and asserted on
 * from a literal [LibraryUiState].
 */
@Composable
fun LibraryRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LibraryScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onTabSelect = viewModel::onTabSelect,
        onSortSelect = viewModel::onSortSelect,
        onViewModeToggle = viewModel::onViewModeToggle,
        onSearchClick = onOpenSearch,
        onAlbumClick = onOpenAlbum,
        onAlbumPlay = viewModel::onAlbumPlay,
        onArtistClick = onOpenArtist,
        onSongPlay = viewModel::onSongPlay,
        onSyncNow = viewModel::onSyncNow,
        modifier = modifier,
    )
}
