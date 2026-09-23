package app.needler.settings

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of Settings: holds the [SettingsViewModel] and hands navigation upwards.
 *
 * Split from [SettingsScreen] for the reason `ConnectRoute` is split from `ConnectScreen` and
 * `LibraryRoute` from `LibraryScreen`: the screen then needs neither Hilt nor a repository to
 * render, which is what lets every state it can be in be screenshotted and asserted on from a
 * literal [SettingsUiState].
 *
 * ## This route never navigates
 *
 * Every destination it can reach arrives as a parameter, including the two sub-screens that are
 * plainly "part of Settings" from the user's point of view. That is not ceremony. The equaliser and
 * crossfade screens live in `:feature:player` - `EqualiserRoute` and `CrossfadeRoute`, screens 19
 * and 20 - because both need the custom Media3 audio-processor chain in `:player:service`, and this
 * module cannot reach `:feature:player`'s routes without deciding how they are registered. Deciding
 * that here would be answering a navigation question by accident, which is exactly what
 * `NeedlerNavHost` already declined to do for the output picker.
 *
 * ## The output picker is deliberately absent
 *
 * Screen 21 is not on screen 12, and it should not be added to it. The pack draws the "Play on"
 * picker as a sheet over the dimmed player, and `OutputPickerRoute` takes no dismiss callback at
 * all - so a Settings row leading to it would strand the listener on a screen with no way off.
 * `NowPlayingRoute.onChooseOutput` and `PlayerSidebarRoute.onChooseOutput` are where it belongs,
 * and both are currently wired to `{}` in `NeedlerNavHost` awaiting exactly that decision. There is
 * no `onOpenOutputPicker` on this route for that reason.
 *
 * @param widthSizeClass the window width, as every other screen in this app takes it. Settings has
 *   no tablet artboard in the pack, so it is used only to cap and centre the content rather than to
 *   choose a different layout.
 * @param onOpenEqualiser opens screen 19. Wire it to `:feature:player`'s `EqualiserRoute`.
 * @param onOpenCrossfade opens screen 20. Wire it to `:feature:player`'s `CrossfadeRoute`.
 * @param onChangeServer opens onboarding so a different server can be connected. The pack links
 *   both this and Sign out to the Connect artboard.
 * @param onSignedOut called once, after the sign-out has actually completed. The host navigates to
 *   Connect and drops Home from the back stack; a sign-out that failed never calls it, and the
 *   screen says why instead.
 * @param onOpenLicences opens the licences and full terms. Null - the default - removes the link
 *   rather than drawing one that does nothing, because there is no licences screen in the pack yet.
 * @param onSignInAgain re-authenticates an expired companion session, which means returning to
 *   Connect for the account password: Needler never stores it, so there is no silent renewal and
 *   REQUIREMENTS.md requires the user be prompted. Null leaves the day-25 expiry warning as a
 *   statement with no action attached.
 */
@Composable
fun SettingsRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenEqualiser: () -> Unit,
    onOpenCrossfade: () -> Unit,
    onChangeServer: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLicences: (() -> Unit)? = null,
    onSignInAgain: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reported upwards rather than acted on here, exactly as ConnectRoute reports `connected`. The
    // sign-out has already happened by the time this fires: the session is cleared, so the host is
    // free to pop Home off the back stack without racing the repository.
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    SettingsScreen(
        state = state,
        callbacks = SettingsCallbacks(
            onSyncNow = viewModel::onSyncNow,
            onChangeServer = onChangeServer,
            onGaplessChange = viewModel::onGaplessChange,
            onOpenCrossfade = onOpenCrossfade,
            onOpenEqualiser = onOpenEqualiser,
            onTranscodeOnMobileDataChange = viewModel::onTranscodeOnMobileDataChange,
            onScrobblingChange = viewModel::onScrobblingChange,
            onNotifyPullFinishedChange = viewModel::onNotifyPullFinishedChange,
            onNotifyPullFailedChange = viewModel::onNotifyPullFailedChange,
            onNotifyNewReleaseChange = viewModel::onNotifyNewReleaseChange,
            onKeepPulledAlbumsChange = viewModel::onKeepPulledAlbumsChange,
            onWifiOnlyDownloadsChange = viewModel::onWifiOnlyDownloadsChange,
            onRemoveDownload = viewModel::onRemoveDownload,
            onClearCachedMusic = viewModel::onClearCachedMusic,
            onCheckForUpdates = viewModel::onCheckForUpdates,
            onArmDestructiveAction = viewModel::onArmDestructiveAction,
            onCancelDestructiveAction = viewModel::onCancelDestructiveAction,
            onConfirmDestructiveAction = viewModel::onConfirmDestructiveAction,
            onOpenLicences = onOpenLicences,
            onSignInAgain = onSignInAgain,
        ),
        widthSizeClass = widthSizeClass,
        modifier = modifier,
    )
}
