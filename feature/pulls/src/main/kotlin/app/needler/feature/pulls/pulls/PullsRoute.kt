package app.needler.feature.pulls.pulls

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The stateful half of Pulls: holds the [PullsViewModel] and turns taps into
 * navigation.
 *
 * Split from [PullsScreen] for the same reason `LibraryRoute` is split from
 * `LibraryScreen`: the screen itself then needs neither Hilt nor a repository to
 * render, which is what lets every state — each bucket, the held notice, the
 * empty queue, an action that failed, a tablet — be screenshotted and asserted
 * on from a literal [PullsUiState].
 *
 * Cancelling and retrying are methods on the ViewModel, because both are
 * repository calls whose result the screen has to render. Opening, playing and
 * going to Search are callbacks, because all three are navigation and navigation
 * is the host's business.
 *
 * ## Why [onOpenSearch] has a default and `LibraryRoute`'s does not
 *
 * Every empty state on this screen now ends in a button to Search — three of them said "find an
 * album under Search" and offered no way to get there, which REQUIREMENTS.md "Accessibility" does
 * not accept from a route. The host wires it exactly as `LibraryRoute`'s is wired, with
 * `selectTab(NeedlerDestination.Search)`.
 *
 * It takes a default anyway, which `LibraryRoute`'s equivalent does not, and that is a deliberate
 * concession rather than a style: `:app` is not this module's to change, several agents are editing
 * it, and a required parameter would make this module's compilation depend on another's call site
 * landing first. A no-op default is the thing that cannot break a build it does not own. The real
 * wiring is one line and is done.
 *
 * ## The lanes need no route registering
 *
 * Pulls, History and Wanted are tabs **inside** this one destination — see
 * [PullsLane] — so `app/src/main/kotlin/app/needler/ui/navigation` is untouched
 * and this composable's contract with `:app` is unchanged but for nothing. A
 * host that was calling `PullsRoute(widthSizeClass, onOpenAlbum)` before still
 * is. Selecting a lane, refreshing one and paging history are all ViewModel
 * methods, because all three are repository calls whose result the screen has to
 * render; none of them leaves this destination.
 *
 * ## Why playback is a callback rather than a `PlaybackController`
 *
 * `:feature:library` injects `Optional<PlaybackController>` and declares a
 * `@BindsOptionalOf` for it, because `:player:service` does not exist yet and a
 * feature module that made the whole app fail to compile would be a bad
 * neighbour. This module deliberately does not repeat that declaration: a second
 * `@BindsOptionalOf` for the same key in the same `SingletonComponent` is a risk
 * taken on a graph that `:app` assembles and this module cannot build or test,
 * and the gain would be one call.
 *
 * Handing **Play** to the host costs nothing and is arguably more honest — the
 * host already owns the player and the back stack, and can decide whether
 * tapping Play on a freshly landed album should start it or open it. A host with
 * no player yet passes `onOpenAlbum` for both, which is the default below.
 */
@Composable
fun PullsRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit = onOpenAlbum,
    onOpenSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PullsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PullsScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onClearDone = viewModel::onClearDone,
        onOpenAlbum = onOpenAlbum,
        onPlayAlbum = onPlayAlbum,
        onCancel = viewModel::onCancel,
        onRetry = viewModel::onRetry,
        onDismissNotice = viewModel::onDismissNotice,
        onSelectLane = viewModel::onSelectLane,
        onRefreshLane = viewModel::onRefreshLane,
        onLoadMoreHistory = viewModel::onLoadMoreHistory,
        onRetryRequest = viewModel::onRetryRequest,
        onOpenSearch = onOpenSearch,
        modifier = modifier,
    )
}
