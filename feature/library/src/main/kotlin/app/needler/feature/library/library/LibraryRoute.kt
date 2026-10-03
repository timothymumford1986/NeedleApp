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
 *
 * @param onOpenPlaylists opens the Playlists screen. **The host does not supply
 *   this yet, and until it does the control is drawn and inert.** It has a
 *   default only so that `:app` keeps compiling while the one-line change is
 *   applied; it is not a default anything should keep. `NeedlerNavHost` already
 *   registers `ROUTE_PLAYLISTS`, and what it needs at its `LibraryRoute` call is
 *   `onOpenPlaylists = { navController.navigate(ROUTE_PLAYLISTS) { launchSingleTop = true } }`.
 * @param onOpenGenres the same for `ROUTE_GENRES`:
 *   `onOpenGenres = { navController.navigate(ROUTE_GENRES) { launchSingleTop = true } }`.
 *   Both routes were registered and never navigated to, which is why four
 *   finished screens shipped unreachable; [LibraryScreen] carries the reasoning
 *   for where the entry point sits.
 */
@Composable
fun LibraryRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaylists: () -> Unit = {},
    onOpenGenres: () -> Unit = {},
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
        onAlbumAddToCrate = viewModel::onAlbumAddToCrate,
        onSongAddToCrate = viewModel::onSongAddToCrate,
        onDismissNotice = viewModel::onDismissNotice,
        onSyncNow = viewModel::onSyncNow,
        onOpenPlaylists = onOpenPlaylists,
        onOpenGenres = onOpenGenres,
        modifier = modifier,
    )
}
