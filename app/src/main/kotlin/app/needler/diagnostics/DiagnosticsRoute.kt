package app.needler.diagnostics

import android.content.ActivityNotFoundException
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of Diagnostics: holds the [DiagnosticsViewModel] and owns the one thing a
 * `ViewModel` must not do.
 *
 * Split from [DiagnosticsScreen] for the reason `ConnectRoute` is split from `ConnectScreen`: the
 * screen then needs neither Hilt nor a log to render, which is what lets an empty log, a full one, a
 * truncated one and a failed export all be rendered from a literal.
 *
 * ## Why the share intent is launched here
 *
 * Because starting an activity needs a window, and a `ViewModel` does not have one. It has the
 * application `Context`, which *would* work with `FLAG_ACTIVITY_NEW_TASK` and would put the chooser in
 * a task of its own instead of over Needler — so backing out of the share sheet would not return the
 * user to this screen. `LocalContext` inside a composition is the activity, so the chooser opens where
 * it belongs and dismissing it comes back here.
 *
 * The effect is keyed on [DiagnosticsUiState.pendingShare] and clears it through
 * [DiagnosticsViewModel.onShareHandled] immediately, so a rotation while the chooser is up does not
 * open a second chooser. This is exactly the shape `SettingsRoute` uses for `signedOut`.
 *
 * `ActivityNotFoundException` is caught rather than allowed to propagate. `Intent.createChooser`
 * resolves to the system chooser, which exists on every device with a launcher, so this should be
 * unreachable — but a crash is a bad way to find out otherwise, on a screen whose entire purpose is to
 * help diagnose a problem.
 *
 * ## Registering it
 *
 * `NeedlerNavHost` should add a `composable("diagnostics")` in the same nested graph as `settings`,
 * and pass `onOpenDiagnostics = { navController.navigate("diagnostics") }` to `SettingsRoute`.
 *
 * @param onBack pops back to Settings. Wire it to `navController::popBackStack`, as the equaliser and
 *   crossfade screens are wired.
 * @param widthSizeClass the window width, as every screen in this app takes it. Used only to cap and
 *   centre the content.
 */
@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The ViewModel snapshots the buffer when it is constructed, which is the first time this screen
    // opens. Returning to a retained ViewModel would otherwise show the log as it was minutes ago.
    LaunchedEffect(Unit) { viewModel.onRefresh() }

    val pendingShare: DiagnosticsShare? = state.pendingShare
    LaunchedEffect(pendingShare) {
        if (pendingShare == null) return@LaunchedEffect
        try {
            context.startActivity(
                DiagnosticsExport.chooserIntent(
                    uri = pendingShare.uri,
                    subject = SHARE_SUBJECT,
                    body = SHARE_BODY,
                ),
            )
        } catch (unavailable: ActivityNotFoundException) {
            // Nothing on this device can receive a file. Silent rather than crashing; the notice the
            // export already set still says the file was written and where.
        }
        viewModel.onShareHandled()
    }

    DiagnosticsScreen(
        state = state,
        callbacks = DiagnosticsCallbacks(
            onShare = viewModel::onShare,
            onRefresh = viewModel::onRefresh,
            onClear = viewModel::onClear,
        ),
        onBack = onBack,
        widthSizeClass = widthSizeClass,
        modifier = modifier,
    )
}

/** What a mail client puts in the subject line, so a bug report arrives recognisable. */
private const val SHARE_SUBJECT: String = "Needler diagnostics log"

/**
 * What it puts in the body.
 *
 * An attachment with an empty body is an email most people do not send, and the three questions below
 * are the ones a maintainer asks first. The reminder about reading the file is not boilerplate: the
 * log contains every server address the app touched, and a user is entitled to know that before they
 * press send even though the credentials are gone.
 */
private const val SHARE_BODY: String =
    "Attached is Needler's diagnostics log for this session.\n\n" +
        "What I was doing:\n\n" +
        "What I expected:\n\n" +
        "What happened instead:\n\n" +
        "The log lists the requests Needler made, with passwords and tokens removed. It does " +
        "include your server's address. Have a read before you send it."
