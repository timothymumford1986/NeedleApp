package app.needler.feature.player.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.PlayerViewModel
import app.needler.feature.player.crate.CrateUiState
import app.needler.feature.player.crate.CrateViewModel

/**
 * The tablet sidebar, wired to the session.
 *
 * Two view models, not one, because the panel shows both halves of playback and they are separate
 * for a reason: the transport comes from [PlayerViewModel] and the crate from [CrateViewModel], so a
 * reorder does not invalidate the transport and a play/pause does not re-diff the list. They are
 * scoped to whatever host composes this - the navigation scaffold - so the panel keeps its state
 * across destination changes, which is what "permanent" means on screen 09.
 */
@Composable
fun PlayerSidebarRoute(
    onChooseOutput: () -> Unit,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    crateViewModel: CrateViewModel = hiltViewModel(),
) {
    val state: PlayerUiState by playerViewModel.state.collectAsStateWithLifecycle()
    val crate: CrateUiState by crateViewModel.state.collectAsStateWithLifecycle()
    val progress: State<PlaybackProgress> = playerViewModel.progress.collectAsStateWithLifecycle()

    PlayerSidebarContent(
        state = state,
        crate = crate,
        progress = { progress.value },
        onPlayPause = playerViewModel::playPause,
        onNext = playerViewModel::skipToNext,
        onPrevious = playerViewModel::skipToPrevious,
        onSeek = playerViewModel::seekToFraction,
        onToggleShuffle = playerViewModel::toggleShuffle,
        onCycleRepeat = playerViewModel::cycleRepeat,
        onChooseOutput = onChooseOutput,
        onPlayItem = crateViewModel::skipTo,
        onMove = crateViewModel::moveItem,
        modifier = modifier,
    )
}
