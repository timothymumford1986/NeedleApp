package app.needler.feature.player.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.PlayerViewModel

/**
 * The stateful half of Now Playing.
 *
 * Split from [NowPlayingScreen] exactly as `ConnectRoute` is split from `ConnectScreen`: the screen
 * needs neither Hilt nor a session to render, which is what lets every state of it be screenshotted
 * and asserted from a literal [PlayerUiState].
 *
 * Note what is *not* done here: `progress` is collected into a [State] and handed on as a lambda,
 * never read in this composable. Reading it here would recompose the route - and therefore the whole
 * screen - several times a second, which is the exact cost `PlaybackController` splits its flows to
 * avoid.
 *
 * @param onOpenArtist opens the artist's page, and has no default on purpose - a default of `{}` is
 *   exactly the inert tap target this parameter exists to remove. The artist line in the player was
 *   plain grey text while the same artist on album detail was a link, and this is the half of the fix
 *   that `:feature:player` cannot supply for itself: the artist destination lives in `:app`'s graph.
 *   Now Playing is a sibling of Home rather than a destination inside it, so the caller has to leave
 *   this layer before it can navigate - see the note on this route in `NeedlerNavHost`.
 */
@Composable
fun NowPlayingRoute(
    onClose: () -> Unit,
    onOpenCrate: () -> Unit,
    onChooseOutput: () -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state: PlayerUiState by viewModel.state.collectAsStateWithLifecycle()
    val progress: State<PlaybackProgress> = viewModel.progress.collectAsStateWithLifecycle()

    NowPlayingScreen(
        state = state,
        progress = { progress.value },
        onClose = onClose,
        onOpenCrate = onOpenCrate,
        onPlayPause = viewModel::playPause,
        onNext = viewModel::skipToNext,
        onPrevious = viewModel::skipToPrevious,
        onSeek = viewModel::seekToFraction,
        onToggleShuffle = viewModel::toggleShuffle,
        onCycleRepeat = viewModel::cycleRepeat,
        onChooseOutput = onChooseOutput,
        onToggleFavourite = viewModel::toggleFavourite,
        onChooseSleepTimer = viewModel::setSleepTimer,
        onOpenArtist = onOpenArtist,
        modifier = modifier,
        onOverrideQuality = viewModel::overridePlayingTrackQuality,
        onClearQualityOverride = viewModel::clearPlayingTrackQualityOverride,
    )
}

/**
 * The mini player, wired to the session.
 *
 * Composes nothing at all when the crate is empty. That is the one place a player surface is allowed
 * to disappear rather than draw its empty state: the bar sits between the content and the bottom
 * navigation, so an empty one would permanently steal 72 dp from every list on the phone to say
 * nothing. [MiniPlayer] still draws its own empty state, for the moment between a crate being
 * cleared and the bar animating away, and for any host that wants the bar always present.
 */
@Composable
fun MiniPlayerRoute(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state: PlayerUiState by viewModel.state.collectAsStateWithLifecycle()
    if (!state.hasTrack) return

    MiniPlayer(
        state = state,
        onExpand = onExpand,
        onPlayPause = viewModel::playPause,
        onNext = viewModel::skipToNext,
        modifier = modifier,
    )
}
