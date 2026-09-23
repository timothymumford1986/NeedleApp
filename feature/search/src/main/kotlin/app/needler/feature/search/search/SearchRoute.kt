package app.needler.feature.search.search

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The stateful half of Search: holds the [SearchViewModel] and turns taps into
 * navigation.
 *
 * Split from [SearchScreen] for the same reason `LibraryRoute` is split from
 * `LibraryScreen`: the screen itself then needs neither Hilt nor a repository to
 * render, which is what lets every state — including the ones that need a
 * degraded server to reach — be screenshotted and asserted on from a literal
 * [SearchUiState].
 *
 * ## Why opening a result records the query
 *
 * `SearchRepository.recordRecentQuery` has to be called from somewhere, and the
 * honest signal that a search worked is that the user opened something out of
 * it. The keyboard's search key is the weaker signal: results appear while the
 * user is still typing, so most successful searches never involve it at all.
 * Both are wired — [SearchViewModel.onSubmitQuery] and
 * [SearchViewModel.onResultOpened] — and the route calls the second on its way
 * to navigating.
 *
 * ## The identifiers handed out
 *
 * Both callbacks carry the bare MusicBrainz identifier, **not** the `al-` or
 * `ar-` prefixed Subsonic id. REQUIREMENTS.md "Identity model": the release
 * group MBID is the join key that makes the merged list on this screen possible
 * in the first place, and the Subsonic prefix belongs to the Subsonic lane
 * alone. `:feature:library`'s `AlbumRoute` reads the same bare value back out of
 * its navigation argument, so the two ends already agree.
 *
 * @param onOpenAlbum an album row, owned or not. There is one album screen, not
 *   two: an un-owned result opens the same destination and differs only in the
 *   state it finds there.
 * @param onCancel the **Cancel** link on the phone (03) and the back arrow on
 *   the tablet (10). Search is reachable both as a navigation destination and
 *   from the library's search box, so what "back" means is the host's to decide.
 */
@Composable
fun SearchRoute(
    widthSizeClass: WindowWidthSizeClass,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SearchScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onSubmitQuery = viewModel::onSubmitQuery,
        onCancel = onCancel,
        onRecentQuerySelect = viewModel::onRecentQuerySelect,
        onClearRecentQueries = viewModel::onClearRecentQueries,
        onSuggestionSelect = viewModel::onSuggestionSelect,
        onArtistClick = { artist: Artist ->
            viewModel.onResultOpened()
            onOpenArtist(artist.mbid)
        },
        onAlbumClick = { album: Album ->
            viewModel.onResultOpened()
            onOpenAlbum(album.releaseGroupMbid)
        },
        onPull = viewModel::onPull,
        onPlayTrack = viewModel::onPlayTrack,
        onDismissNotice = viewModel::onDismissNotice,
        modifier = modifier,
    )
}
