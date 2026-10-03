package app.needler.settings

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The stateful half of Downloaded albums: holds the [DownloadsViewModel] and nothing else.
 *
 * Split from [DownloadsScreen] for the reason `SettingsRoute` is split from `SettingsScreen`: the
 * screen then needs neither Hilt nor a cache index to render, which is what lets a full list, an
 * empty device, a removal in flight and a removal that failed all be rendered from a literal.
 *
 * This route never navigates. [onBack] is the only destination it has, and it is handed in.
 *
 * ## Registering it
 *
 * `NeedlerNavHost` needs a `composable("downloads")` in the same nested graph as `settings`, wired
 * exactly as Licences and Diagnostics are:
 *
 * ```
 * private const val ROUTE_DOWNLOADS = "downloads"
 *
 * composable(ROUTE_DOWNLOADS) {
 *     DownloadsRoute(
 *         widthSizeClass = widthSizeClass,
 *         onBack = { navController.popBackStack() },
 *     )
 * }
 * ```
 *
 * and `SettingsRoute` needs `onOpenDownloads = { navController.navigate(ROUTE_DOWNLOADS) { launchSingleTop = true } }`.
 *
 * Until that lands, `SettingsScreen` keeps drawing the whole list inline, which is the pre-existing
 * behaviour rather than a gap - see `SettingsCallbacks.onOpenDownloads`. The list is the only way the
 * user can reclaim space (REQUIREMENTS.md "Storage, and why there is no budget" leaves no storage
 * limit in the product), so an unregistered route must not be able to put part of it out of reach.
 *
 * @param onBack pops back to Settings. Wire it to `navController::popBackStack`, as the equaliser,
 *   crossfade, licences and diagnostics screens are wired.
 * @param widthSizeClass the window width, as every screen in this app takes it. Used only to cap and
 *   centre the content.
 */
@Composable
fun DownloadsRoute(
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DownloadsScreen(
        state = state,
        onRemove = viewModel::onRemove,
        onBack = onBack,
        widthSizeClass = widthSizeClass,
        modifier = modifier,
    )
}
