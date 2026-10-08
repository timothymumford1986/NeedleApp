package app.needler.feature.library.playlists

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of one playlist's screen.
 *
 * Navigation is the host's business, so leaving the screen is a callback. That
 * matters twice here: [onBack] is the top bar's back button, and it is also what a
 * confirmed delete does, because the playlist this screen is showing has just
 * stopped existing. The "Deleted" notice is therefore lost with the screen, and
 * that is the right trade — the playlist being gone from the list behind it is the
 * confirmation, and keeping the screen open to read a sentence about a playlist
 * that no longer exists would be worse.
 *
 * `:app` builds this route as `playlist/{playlistId}`, where the argument name is
 * [PlaylistViewModel.PLAYLIST_ID_ARG] and the value is the **bare** playlist id —
 * not the `pl-` prefixed Subsonic one. A playlist created offline carries a
 * provisional `local-…` id, which is a legal value for this route and must not be
 * filtered out anywhere on the way in.
 */
@Composable
fun PlaylistRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PlaylistScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onBack = onBack,
        onPlay = viewModel::onPlay,
        onShuffle = viewModel::onShuffle,
        onAddToCrate = viewModel::onAddToCrate,
        onPlayTrack = viewModel::onPlayTrack,
        onRemoveTrack = viewModel::onRemoveTrack,
        onMoveUp = viewModel::onMoveUp,
        onMoveDown = viewModel::onMoveDown,
        onRetryTrack = viewModel::onRetryTrack,
        onAddTracksClick = viewModel::onAddTracksClick,
        onPickerQueryChange = viewModel::onPickerQueryChange,
        onToggleCandidate = viewModel::onToggleCandidate,
        onAddSelected = viewModel::onAddSelected,
        onPickerCancel = viewModel::onPickerCancel,
        onRenameClick = viewModel::onRenameClick,
        onRenameNameChange = viewModel::onRenameNameChange,
        onRenameConfirm = viewModel::onRenameConfirm,
        onRenameCancel = viewModel::onRenameCancel,
        onDeleteClick = viewModel::onDeleteClick,
        onDeleteConfirm = { viewModel.onDeleteConfirm(onDeleted = onBack) },
        onDeleteCancel = viewModel::onDeleteCancel,
        onDismissNotice = viewModel::onDismissNotice,
        modifier = modifier,
    )
}
