package app.needler.core.domain.repository

import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Binary favourites, via Subsonic `star` and `unstar`.
 *
 * There is no rating API here on purpose: `setRating` on this server validates its input and returns
 * success without persisting anything, so a star-rating control would silently do nothing. Binary
 * favourites do persist and are the supported mechanism.
 */
public interface FavouriteRepository {

    /**
     * Starred albums, artists and tracks from the mirror, recently starred first.
     *
     * ## Deliberately not windowed
     *
     * Every other list-shaped read in the domain now takes a `limit` and an `offset` -
     * `LibraryRepository.observeArtists`, `PlaylistRepository.observePlaylists`,
     * `PinRepository.observeDownloadedAlbums` - and this one does not. That is a decision, not an
     * omission.
     *
     * [Favourites] is three lists, not one, so a single offset has nothing to index into. Applying
     * the same window to each bucket is worse than no window at all: the one caller that pages
     * favourites, the Android Auto browse tree, flattens them into `albums + artists + tracks`, and a
     * per-bucket window over a flattened list drops and repeats rows between pages. Paging the
     * flattened sequence properly would take three counts and three windowed queries per page turn,
     * six statements to window a list the user built by hand.
     *
     * And that is the other half of it: this collection is bounded by human effort rather than by
     * library size. REQUIREMENTS.md "Assumptions made" puts libraries in "the low thousands of
     * albums"; nobody stars thousands of things. The performance budgets that forced windows onto
     * artists, playlists and downloads - "Scroll: no dropped frames on a 5,000-album grid" - are
     * about lists that grow with the library, and this one does not. The caller slices in memory,
     * which is the right trade here and the wrong one there.
     */
    public fun observeFavourites(): Flow<Favourites>

    public fun observeIsFavourite(target: FavouriteTarget): Flow<Boolean>

    /**
     * Stars or unstars. Applied to the mirror immediately so the UI responds at once; the server call
     * is journalled in the write queue when offline.
     */
    public suspend fun setFavourite(target: FavouriteTarget, starred: Boolean): Outcome<Unit>

    /** Re-reads `getStarred2` into the mirror. Called by sync. */
    public suspend fun refreshFavourites(): Outcome<Unit>
}
