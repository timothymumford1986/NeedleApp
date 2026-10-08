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
 * `:app` builds this route as
 * `artist/{artistId}?artistName={artistName}&artistSubtitle={artistSubtitle}`, where the
 * argument names are [ArtistViewModel.ARTIST_ID_ARG], [ArtistViewModel.ARTIST_NAME_ARG] and
 * [ArtistViewModel.ARTIST_SUBTITLE_ARG], and the id is the bare artist MBID — not the `ar-`
 * prefixed Subsonic id.
 *
 * ## The two optional arguments, and who fills them
 *
 * The name and subtitle carry what the caller already knew. Most callers know nothing worth
 * carrying and pass neither: the library, search, an album's artist link and a notification tap
 * all open an artist the mirror has a row for, and the row is where the name comes from. The
 * caller that does know something is [onOpenCatalogueArtist] below.
 *
 * Both are read from the `SavedStateHandle` rather than passed down through this composable, so
 * the name survives a rotation and process death and so a caller needs to know nothing but the
 * route.
 *
 * ## Why there are two open-artist callbacks and not one wide one
 *
 * An artist whose id DroppedNeedle derived from their name can never fetch a discography, and the
 * only way out is the catalogue's own artists of that name - see
 * [ArtistViewModel.onFindInCatalogue]. Each of those rows opens **this same screen** for a real
 * MusicBrainz MBID that the mirror has no row for: `refreshArtistDiscography` writes album rows
 * and never an artist row, so no amount of waiting produces a name. Tapping one therefore used to
 * land on a header reading "Unknown artist" over the sentence "That artist is not here", for an
 * artist whose name the previous screen had just printed.
 *
 * The obvious fix was to widen [onOpenArtist] to `(ArtistMbid, String, String?) -> Unit`. **That
 * was rejected, and not because it was wrong.** The same callback name and shape is a parameter of
 * `SearchRoute`, `LibraryRoute` and `AlbumRoute`, every one of them satisfied by the identical
 * `{ navController.navigate(artistRoute(it.value)) }` line in `:app`; widening the shape here
 * invites widening it there, which touches three feature modules to add two arguments that only
 * one call site in one of them can fill. A second callback costs one parameter and leaves every
 * existing call site compiling untouched.
 *
 * So: [onOpenArtist] for an artist the destination can name for itself, and
 * [onOpenCatalogueArtist] for the namesake rows, which is the only place in the app that knows a
 * name the mirror does not.
 *
 * @param onOpenArtist open another artist by MBID alone. Pushed rather than replaced: the listener
 *   followed a link and Back has to walk it back.
 * @param onOpenCatalogueArtist open one of the catalogue's namesakes, carrying the MBID, the name
 *   the row displayed and MusicBrainz's disambiguation comment — or null where the catalogue sent
 *   none. No subtitle is invented for the null case; `ArtistUiState.subtitle` already has an
 *   honest fallback and a minted one would be a claim no source made. Required, with no default:
 *   this is the wiring that was missing for eleven versions of a finished screen, and a default
 *   delegating to [onOpenArtist] would let the next host omit it and reintroduce the nameless
 *   header without failing to compile.
 */
@Composable
fun ArtistRoute(
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onOpenAlbum: (ReleaseGroupMbid) -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    onOpenCatalogueArtist: (ArtistMbid, String, String?) -> Unit,
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
        onShowMoreDiscography = viewModel::onShowMoreDiscography,
        onFindInCatalogue = viewModel::onFindInCatalogue,
        onOpenArtist = onOpenArtist,
        onOpenCatalogueArtist = onOpenCatalogueArtist,
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
        // Wired for the first time. The method has existed since this ViewModel was written and
        // nothing called it, so the notice the crate and the pull actions leave behind could not be
        // dismissed on this screen - while the identical notice on the album screen could.
        onDismissNotice = viewModel::onDismissNotice,
    )
}
