package app.needler.core.data.repository

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.FavouriteDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.FavouriteTypeDb
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.WireTime
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.Starred2Dto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Binary favourites, via Subsonic `star` and `unstar`.
 *
 * There is no rating API here on purpose: `setRating` on this server validates its input and returns
 * success **without persisting anything**, so a star-rating control would silently do nothing.
 *
 * A star is written to the mirror immediately so the UI responds at once, with `pending_sync` set
 * until the server has agreed. The `favourite` table carries the resolved (release group, disc,
 * track) tuple beside the opaque id precisely so "show my starred songs" is a join rather than a
 * fetch-then-filter: it cannot join to `track` on an id, because a track's identity is the tuple.
 */
public class DefaultFavouriteRepository(
    private val favouriteDao: FavouriteDao,
    private val trackDao: TrackDao,
    private val writeQueue: WriteQueue,
    private val networkMonitor: NetworkMonitor,
    private val subsonic: SubsonicApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : FavouriteRepository {

    override fun observeFavourites(): Flow<Favourites> = combine(
        favouriteDao.observeStarredAlbums(),
        favouriteDao.observeStarredArtists(),
        favouriteDao.observeStarredTracks(),
    ) { albums: List<AlbumEntity>, artists: List<ArtistEntity>, tracks: List<TrackEntity> ->
        Favourites(
            albums = albums.map { EntityMappers.album(it, isFavourite = true) },
            artists = artists.map { EntityMappers.artist(it, isFavourite = true) },
            tracks = tracks.map { EntityMappers.track(it, isFavourite = true) },
        )
    }

    override fun observeIsFavourite(target: FavouriteTarget): Flow<Boolean> = when (target) {
        is FavouriteTarget.OfAlbum -> favouriteDao.observeAlbumIsStarred(target.releaseGroupMbid.value)
        is FavouriteTarget.OfArtist -> favouriteDao.observeArtistIsStarred(target.mbid.value)
        is FavouriteTarget.OfTrack -> favouriteDao.observeTrackIsStarred(
            releaseGroupMbid = target.key.releaseGroupMbid.value,
            discNo = target.key.discNumber,
            trackNo = target.key.trackNumber,
        )
    }

    /**
     * Stars or unstars.
     *
     * The mirror is written first and unconditionally: the heart fills under the user's finger
     * whatever the network is doing. `pending_sync` records that the server has not agreed yet, and
     * the write queue clears it on replay.
     */
    override suspend fun setFavourite(target: FavouriteTarget, starred: Boolean): Outcome<Unit> {
        val type: FavouriteTypeDb = typeOf(target)
        val entityId: String = entityIdOf(target)
        if (starred) {
            favouriteDao.upsert(
                FavouriteEntity(
                    entityType = type,
                    entityId = entityId,
                    starredAt = nowMillis(),
                    releaseGroupMbid = (target as? FavouriteTarget.OfTrack)
                        ?.key?.releaseGroupMbid?.value,
                    discNo = (target as? FavouriteTarget.OfTrack)?.key?.discNumber,
                    trackNo = (target as? FavouriteTarget.OfTrack)?.key?.trackNumber,
                    pendingSync = true,
                ),
            )
        } else {
            favouriteDao.delete(type.dbValue, entityId)
        }

        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.SetFavourite(target, starred))
            return Outcome.Ok
        }

        val id: String? = subsonicIdFor(target)
        if (id == null) {
            // A starred track whose file the mirror does not know cannot be addressed on the Subsonic
            // lane. The local star stands and the queue will retry once a sync has resolved the row.
            writeQueue.enqueue(WriteOperation.SetFavourite(target, starred))
            return Outcome.Ok
        }

        val call: Outcome<Unit> = networkCall {
            if (starred) star(id) else unstar(id)
        }
        return when (call) {
            is Outcome.Success -> {
                if (starred) favouriteDao.setPendingSync(type.dbValue, entityId, pendingSync = false)
                Outcome.Ok
            }
            is Outcome.Failure -> if (call.error.isRetryable) {
                writeQueue.enqueue(WriteOperation.SetFavourite(target, starred))
                Outcome.Ok
            } else {
                call
            }
        }
    }

    /**
     * Re-reads `getStarred2` into the mirror.
     *
     * Stars the user made offline are **kept**: a refresh replaces the synced rows and leaves
     * anything still `pending_sync` alone, or a sync that landed between the tap and the replay
     * would quietly undo the tap.
     */
    override suspend fun refreshFavourites(): Outcome<Unit> {
        val call = networkCall { subsonic.starred2() }
        val starred: Starred2Dto = when (call) {
            is Outcome.Failure -> return call
            is Outcome.Success -> call.value
        }
        val rows: MutableList<FavouriteEntity> = ArrayList()

        for (album in starred.album) {
            val mbid = SubsonicIds.releaseGroupMbid(album.id, album.musicBrainzId) ?: continue
            rows.add(
                FavouriteEntity(
                    entityType = FavouriteTypeDb.ALBUM,
                    entityId = mbid.value,
                    starredAt = WireTime.toEpochMillis(WireTime.fromIso(album.starred)),
                    releaseGroupMbid = mbid.value,
                    discNo = null,
                    trackNo = null,
                    pendingSync = false,
                ),
            )
        }
        for (artist in starred.artist) {
            val mbid = SubsonicIds.artistMbid(artist.id, artist.musicBrainzId) ?: continue
            rows.add(
                FavouriteEntity(
                    entityType = FavouriteTypeDb.ARTIST,
                    entityId = mbid.value,
                    starredAt = WireTime.toEpochMillis(WireTime.fromIso(artist.starred)),
                    releaseGroupMbid = null,
                    discNo = null,
                    trackNo = null,
                    pendingSync = false,
                ),
            )
        }
        for (song: ChildDto in starred.song) {
            val album = SubsonicIds.releaseGroupMbid(song.albumId, song.parent) ?: continue
            val disc: Int = song.discNumber?.takeIf { it >= 1 } ?: 1
            val trackNo: Int = song.track?.takeIf { it >= 1 } ?: continue
            rows.add(
                FavouriteEntity(
                    entityType = FavouriteTypeDb.TRACK,
                    // Keyed on the stable track key, not on `tr-<file id>`. The server's own id for
                    // a starred song moves under a quality upgrade, and a `favourite` row keyed on
                    // it would duplicate the star every time a file was replaced.
                    entityId = TrackKeyDb(album.value, disc, trackNo).canonical,
                    starredAt = WireTime.toEpochMillis(WireTime.fromIso(song.starred)),
                    releaseGroupMbid = album.value,
                    discNo = disc,
                    trackNo = trackNo,
                    pendingSync = false,
                ),
            )
        }

        favouriteDao.deleteSyncedOfType(FavouriteTypeDb.ALBUM.dbValue)
        favouriteDao.deleteSyncedOfType(FavouriteTypeDb.ARTIST.dbValue)
        favouriteDao.deleteSyncedOfType(FavouriteTypeDb.TRACK.dbValue)
        if (rows.isNotEmpty()) favouriteDao.upsertAll(rows)
        return Outcome.Ok
    }

    // ------------------------------------------------------------------ internals

    private suspend fun star(id: String) {
        when {
            SubsonicIds.isAlbumId(id) -> subsonic.star(albumIds = listOf(id))
            SubsonicIds.isArtistId(id) -> subsonic.star(artistIds = listOf(id))
            else -> subsonic.star(ids = listOf(id))
        }
    }

    private suspend fun unstar(id: String) {
        when {
            SubsonicIds.isAlbumId(id) -> subsonic.unstar(albumIds = listOf(id))
            SubsonicIds.isArtistId(id) -> subsonic.unstar(artistIds = listOf(id))
            else -> subsonic.unstar(ids = listOf(id))
        }
    }

    private suspend fun subsonicIdFor(target: FavouriteTarget): String? = when (target) {
        is FavouriteTarget.OfAlbum -> SubsonicIds.albumId(target.releaseGroupMbid)
        is FavouriteTarget.OfArtist -> SubsonicIds.artistId(target.mbid)
        is FavouriteTarget.OfTrack -> {
            val row: TrackEntity? = trackDao.getTrack(
                releaseGroupMbid = target.key.releaseGroupMbid.value,
                discNo = target.key.discNumber,
                trackNo = target.key.trackNumber,
            )
            // Resolved now rather than stored: `tr-<file id>` is a fetch handle and moves under a
            // quality upgrade.
            row?.takeIf(EntityMappers::hasPlayableFile)?.fileId?.let(SubsonicIds::trackId)
        }
    }

    private fun typeOf(target: FavouriteTarget): FavouriteTypeDb = when (target) {
        is FavouriteTarget.OfAlbum -> FavouriteTypeDb.ALBUM
        is FavouriteTarget.OfArtist -> FavouriteTypeDb.ARTIST
        is FavouriteTarget.OfTrack -> FavouriteTypeDb.TRACK
    }

    private fun entityIdOf(target: FavouriteTarget): String = when (target) {
        is FavouriteTarget.OfAlbum -> target.releaseGroupMbid.value
        is FavouriteTarget.OfArtist -> target.mbid.value
        is FavouriteTarget.OfTrack -> target.key.canonicalString
    }
}
