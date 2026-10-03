package app.needler.feature.library.artist

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The stateful half of the artist screen.
 *
 * `:app` builds this route as `artist/{artistId}`, where the argument name is
 * [ArtistViewModel.ARTIST_ID_ARG] and the value is the bare artist MBID — not
 * the `ar-` prefixed Subsonic id.
 *
 * ## Two optional arguments `:app` has to add
 *
 * [ArtistViewModel.ARTIST_NAME_ARG] and [ArtistViewModel.ARTIST_SUBTITLE_ARG] carry
 * what the caller already knew, and the one caller that needs them is catalogue
 * search. An artist found there has no row in the mirror — `refreshArtistDiscography`
 * writes album rows and never an artist row — so without the name this screen cannot
 * say who it is about, which is how tapping a search result came to land on "That
 * artist is not here". The full route is
 * `artist/{artistId}?artistName={artistName}&artistSubtitle={artistSubtitle}`.
 *
 * They are read from the `SavedStateHandle` rather than passed down through this
 * composable so that they survive a rotation and process death, and so that
 * `:feature:search` needs to know nothing but the route.
 *
 * ## The one wiring `:app` has to add for [onOpenArtist]
 *
 * An artist whose id DroppedNeedle derived from their name can never fetch a discography, and the
 * only way out is the catalogue's own artists of that name - see
 * [ArtistViewModel.onFindInCatalogue]. Each of those opens **this same screen** for a real
 * MusicBrainz MBID, so the destination already exists: `:app` passes
 * `onOpenArtist = { navController.navigate(artistRoute(it.value)) }`, which is the identical line
 * it already passes to `SearchRoute`. Until it does, the artist screen compiles and the namesake
 * rows do nothing when tapped; nothing else on the screen is affected.
 */
@Composable
fun ArtistRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ArtistScreen(
        state = state,
        widthSizeClass = widthSizeClass,
        onBack = onBack,
        onAlbumClick = onOpenAlbum,
        onPull = viewModel::onPull,
        onPullArtist = viewModel::onPullArtist,
        onRetryDiscography = viewModel::refreshDiscography,
        onFindInCatalogue = viewModel::onFindInCatalogue,
        onOpenArtist = onOpenArtist,
        onPlay = viewModel::onPlay,
        onShuffle = viewModel::onShuffle,
        onPlayAlbum = viewModel::onPlayAlbum,
        onAddToCrate = viewModel::onAddToCrate,
        onAddAlbumToCrate = viewModel::onAddAlbumToCrate,
        onToggleFavourite = viewModel::onToggleFavourite,
        onMonitorArtistChange = viewModel::onMonitorArtistChange,
        onConfirmRequest = viewModel::onConfirmRequest,
        onDismissRequestSheet = viewModel::onDismissRequestSheet,
        modifier = modifier,
    )
}
