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

    /** Starred albums, artists and tracks from the mirror, recently starred first. */
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
