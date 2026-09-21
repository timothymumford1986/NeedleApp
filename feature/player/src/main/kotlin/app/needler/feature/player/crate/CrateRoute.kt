package app.needler.feature.player.crate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of the crate, screen 08.
 *
 * Nothing but wiring: the screen itself takes a [CrateUiState] and four callbacks, so every state of
 * it - full, empty, mid-album - renders from a literal value with no session behind it.
 */
@Composable
fun CrateRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrateViewModel = hiltViewModel(),
) {
    val state: CrateUiState by viewModel.state.collectAsStateWithLifecycle()

    CrateScreen(
        state = state,
        onBack = onBack,
        onClear = viewModel::clear,
        onPlayItem = viewModel::skipTo,
        onMove = viewModel::moveItem,
        modifier = modifier,
    )
}
