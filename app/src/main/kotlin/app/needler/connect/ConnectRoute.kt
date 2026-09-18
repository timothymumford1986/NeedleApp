package app.needler.connect

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of Connect: holds the [ConnectViewModel] and reports
 * success upwards.
 *
 * Split from [ConnectScreen] so the screen itself needs neither Hilt nor a
 * repository to render - which is what lets every failure case be screenshotted
 * and unit-tested from a literal state.
 *
 * @param onConnected called once, when onboarding has completed. The host
 *   navigates away and drops this route from the back stack; pressing back from
 *   the library must not land on a sign-in form that has already succeeded.
 */
@Composable
fun ConnectRoute(
    widthSizeClass: WindowWidthSizeClass,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.connected) {
        if (state.connected) onConnected()
    }

    ConnectScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onServerChange = viewModel::onServerChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConnect = viewModel::connect,
        onTrustCertificate = viewModel::trustCertificate,
        modifier = modifier,
    )
}
