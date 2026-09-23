package app.needler.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of the update banner: holds the [UpdateBannerViewModel], starts the check, and
 * owns the one thing a view model cannot do for itself — launching the Settings page that grants
 * install-unknown-apps and hearing when the listener comes back.
 *
 * Split from [UpdateBanner] exactly as `LibraryRoute` is split from `LibraryScreen` and
 * `ConnectRoute` from `ConnectScreen`: the bar itself then needs neither Hilt nor a repository to
 * render, which is what lets every one of its states be built from a literal
 * [UpdateBannerUiState] in a test.
 *
 * ## Mount it once, in the chrome
 *
 * This is designed to sit in `NeedlerNavigationScaffold` as a third slot beside `miniPlayer` and
 * `sidebar`, and it belongs there for the same reason those do: it is chrome. It has to survive a
 * tab switch, it must not be re-created by every destination, and no single screen owns it. Mount
 * it once, above the bottom navigation bar, and it will compose to nothing — no node, no height, no
 * layout cost — on every launch on which there is no update, which is almost all of them. That is
 * the [UpdateBannerUiState.visible] check inside [UpdateBanner] and it is deliberately the first
 * thing that composable does.
 *
 * It takes no callbacks and no navigation. There is nowhere for it to navigate *to*: the whole
 * interaction is the bar, the platform's own confirmation dialogue, and possibly one trip into
 * Settings and back.
 *
 * ## The permission round trip
 *
 * On Android 8 and later the listener, not the manifest, decides whether Needler may install
 * packages; `REQUEST_INSTALL_PACKAGES` only makes the app eligible to ask.
 *
 * The permission is not asked for up front, and the first press of **Update** is what discovers it
 * is missing: the bar then changes to "Allow Needler to install updates" and its action becomes
 * **Allow**. Two presses rather than one, and worth it — asking for the right to install packages
 * before anyone has said they want an update is the kind of prompt people refuse on principle, and
 * a refusal here is remembered by the platform, not by us.
 *
 * The Settings page is launched through an activity result contract rather than a bare
 * `startActivity`. The contract is the point: it is what gives the app a callback when the listener
 * comes back, so granting the permission continues the update instead of dropping them back on a
 * banner they now have to press a second time with no explanation of why the first press did
 * nothing.
 *
 * The result code is ignored — that Settings page does not report one — and
 * [UpdateBannerViewModel.onInstallPermissionSettingsClosed] simply re-asks the platform.
 */
@Composable
fun UpdateBannerRoute(
    modifier: Modifier = Modifier,
    viewModel: UpdateBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val installPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onInstallPermissionSettingsClosed()
    }

    // Once per composition of the host, which is once per app launch: the scaffold outlives every
    // destination, so this does not fire again on a tab switch. Everything that decides whether a
    // request actually goes out is behind the call.
    LaunchedEffect(Unit) { viewModel.onShown() }

    UpdateBanner(
        state = state,
        onAction = {
            if (state.phase == UpdatePhase.PermissionRequired) {
                installPermission.launch(viewModel.installPermissionIntent())
            } else {
                viewModel.onAction()
            }
        },
        onDismiss = viewModel::onDismiss,
        modifier = modifier,
    )
}
