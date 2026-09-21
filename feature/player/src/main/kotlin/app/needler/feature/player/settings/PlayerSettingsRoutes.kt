package app.needler.feature.player.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqSettings
import app.needler.feature.player.output.OutputPickerScreen
import app.needler.feature.player.output.OutputUiState
import app.needler.feature.player.output.OutputViewModel

/**
 * The three settings screens the player owns, wired to their repositories.
 *
 * They are `:feature:player`'s rather than `:app`'s Settings' because every one of them is a
 * playback control: the equaliser and crossfade both need the custom Media3 audio processor chain in
 * `:player:service`, and the output picker replaces the system dialog so that Cast and Bluetooth
 * appear in one list. Settings links to them; it does not contain them.
 */

/** The equaliser, screen 19. */
@Composable
fun EqualiserRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EqualiserViewModel = hiltViewModel(),
) {
    val settings: EqSettings by viewModel.state.collectAsStateWithLifecycle()

    EqualiserScreen(
        settings = settings,
        onEnabledChange = viewModel::setEnabled,
        onPresetSelected = viewModel::selectPreset,
        onBandChange = viewModel::setBandGain,
        onPreampChange = viewModel::setPreamp,
        onResetToFlat = viewModel::resetToFlat,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Crossfade, screen 20. */
@Composable
fun CrossfadeRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrossfadeViewModel = hiltViewModel(),
) {
    val settings: CrossfadeSettings by viewModel.state.collectAsStateWithLifecycle()

    CrossfadeScreen(
        settings = settings,
        onDurationChange = viewModel::setDuration,
        onSuppressWithinAlbumChange = viewModel::setSuppressWithinAlbum,
        onFadeOnSkipChange = viewModel::setFadeOnSkip,
        onFadeOnPauseChange = viewModel::setFadeOnPause,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The output picker, screen 21.
 *
 * Drawn as a sheet over whatever is behind it. The host decides whether that is a real modal sheet
 * or a destination of its own; [OutputPickerScreen] draws the backdrop and the sheet, and
 * `OutputSheet` is the content alone for a host that brings its own sheet.
 */
@Composable
fun OutputPickerRoute(
    modifier: Modifier = Modifier,
    backdrop: (@Composable () -> Unit)? = null,
    viewModel: OutputViewModel = hiltViewModel(),
) {
    val state: OutputUiState by viewModel.state.collectAsStateWithLifecycle()

    OutputPickerScreen(
        state = state,
        onSelect = viewModel::select,
        onVolumeChange = viewModel::setVolume,
        modifier = modifier,
        backdrop = backdrop,
    )
}
