package app.needler.feature.library.playlists

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.PlaylistId

/**
 * The stateful half of the playlists list.
 *
 * Split from [PlaylistsScreen] for the reason the rest of this module is split:
 * the screen then needs neither Hilt nor a repository to render, so every state —
 * empty, offline, mid-create, mid-delete, with a queued edit — is a literal
 * [PlaylistsUiState] in a screenshot test.
 *
 * Navigation is the host's business, so opening a playlist is a callback.
 * Everything else is a method on [PlaylistsViewModel], because all of it is a
 * repository write whose result the screen has to say something about.
 *
 * `:app` builds this route as `playlists`. It takes no arguments.
 */
@Composable
fun PlaylistsRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenPlaylist: (PlaylistId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PlaylistsScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onCreateClick = viewModel::onCreateClick,
        onDraftNameChange = viewModel::onDraftNameChange,
        onCreateConfirm = viewModel::onCreateConfirm,
        onCreateCancel = viewModel::onCreateCancel,
        onPlaylistClick = { playlist -> onOpenPlaylist(playlist.id) },
        onPlay = viewModel::onPlay,
        onAddToCrate = viewModel::onAddToCrate,
        onDeleteRequest = viewModel::onDeleteRequest,
        onDeleteConfirm = viewModel::onDeleteConfirm,
        onDeleteCancel = viewModel::onDeleteCancel,
        onDismissNotice = viewModel::onDismissNotice,
        modifier = modifier,
    )
}
