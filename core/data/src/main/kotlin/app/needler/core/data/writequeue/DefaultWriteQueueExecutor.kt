package app.needler.core.data.writequeue

import app.needler.core.data.local.dao.FavouriteDao
import app.needler.core.data.local.dao.PlaylistDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.SubsonicMappers
import app.needler.core.data.mapper.networkCall
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.WriteOperation
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.subsonic.dto.PlaylistDto
import app.needler.core.network.v1.RequestKind
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.CancelRequestDto
import app.needler.core.network.v1.dto.RetryRequestDto
import app.needler.core.network.v1.dto.TrackRequestDto

/**
 * Replays one journalled mutation against whichever lane owns it.
 *
 * Two conventions collide here and cannot be shared, which is the reason the request cancel and
 * retry cases look odd beside everything else: **a refused request answers HTTP 200 with
 * `success=false`**, while the equivalent download-task calls use real statuses. A client that
 * assumed one convention would either report every refusal as success or every success as a failure.
 */
public class DefaultWriteQueueExecutor(
    private val v1: V1Api,
    private val subsonic: SubsonicApi,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val favouriteDao: FavouriteDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : WriteQueueExecutor {

    override suspend fun execute(operation: WriteOperation, entityKey: String?): Outcome<Unit> =
        when (operation) {
            is WriteOperation.PlaceAlbumRequest -> networkCall {
                v1.requestAlbum(
                    AlbumRequestDto(
                        musicbrainzId = operation.request.releaseGroupMbid.value,
                        artist = operation.request.artistName,
                        album = operation.request.albumTitle,
                        year = operation.request.year,
                        monitorArtist = operation.request.monitorArtist,
                    ),
                )
                Unit
            }

            is WriteOperation.PlaceTrackRequest -> networkCall {
                v1.requestTrack(
                    recordingMbid = operation.request.recordingMbid.value,
                    request = TrackRequestDto(
                        artistName = operation.request.artistName.orEmpty(),
                        trackTitle = operation.request.trackTitle.orEmpty(),
                    ),
                )
                Unit
            }

            is WriteOperation.CancelRequest -> networkCall {
                v1.cancelRequest(operation.releaseGroupMbid.value, RequestKind.Album)
            }.flatMapInBand { dto: CancelRequestDto -> dto.success to dto.message }

            is WriteOperation.RetryRequest -> networkCall {
                v1.retryRequest(operation.releaseGroupMbid.value, RequestKind.Album)
            }.flatMapInBand { dto: RetryRequestDto -> dto.success to dto.message }

            is WriteOperation.EditPlaylist -> replayPlaylistEdit(operation.edit, entityKey)

            is WriteOperation.SetFavourite -> replayFavourite(operation)

            is WriteOperation.SubmitScrobble -> networkCall {
                subsonic.scrobble(
                    ids = listOf(operation.scrobble.fetchHandle.subsonicTrackId),
                    timesMillis = listOf(operation.scrobble.playedAt.toEpochMilliseconds()),
                    submission = operation.scrobble.submission,
                )
            }
        }

    // ------------------------------------------------------------------ playlists

    private suspend fun replayPlaylistEdit(edit: PlaylistEdit, entityKey: String?): Outcome<Unit> =
        when (edit) {
            is PlaylistEdit.Create -> replayCreate(edit, entityKey)

            is PlaylistEdit.Rename -> networkCall {
                subsonic.updatePlaylist(
                    playlistId = SubsonicIds.playlistId(edit.id.value),
                    name = edit.name,
                )
            }

            is PlaylistEdit.AddTracks -> networkCall {
                subsonic.updatePlaylist(
                    playlistId = SubsonicIds.playlistId(edit.id.value),
                    songIdsToAdd = resolveTrackIds(edit.trackKeys),
                )
            }

            is PlaylistEdit.RemoveTracks -> networkCall {
                subsonic.updatePlaylist(
                    playlistId = SubsonicIds.playlistId(edit.id.value),
                    songIndexesToRemove = edit.positions,
                )
            }

            // `createPlaylist` with an existing id **replaces** that playlist's contents, which is the
            // protocol's own quirk and the only way to express a reorder. Last-write-wins is the
            // documented outcome: Subsonic offers no revision or conflict signal to do better.
            is PlaylistEdit.Reorder -> networkCall {
                subsonic.createPlaylist(
                    playlistId = SubsonicIds.playlistId(edit.id.value),
                    songIds = resolveTrackIds(edit.trackKeys),
                )
                Unit
            }

            is PlaylistEdit.Delete -> networkCall {
                subsonic.deletePlaylist(SubsonicIds.playlistId(edit.id.value))
            }
        }

    /**
     * Creates the playlist the user made offline, then **re-keys the local row** to the server's id.
     *
     * A playlist created with no connection is a real row under a locally minted id with
     * `local_only` set; replay adopts the server id and clears the flag. Inserting a second row
     * instead would leave the user looking at their playlist twice.
     */
    private suspend fun replayCreate(edit: PlaylistEdit.Create, localId: String?): Outcome<Unit> {
        val songIds: List<String> = resolveTrackIds(edit.trackKeys)
        val created: Outcome<PlaylistDto> = networkCall {
            subsonic.createPlaylist(name = edit.name, songIds = songIds)
        }
        return when (created) {
            is Outcome.Failure -> created
            is Outcome.Success -> {
                val serverId: String? = SubsonicIds.playlistIdOrNull(created.value.id)?.value
                when {
                    serverId == null -> Outcome.Failure(
                        NeedlerError.ProtocolViolation("createPlaylist returned no id"),
                    )
                    localId != null -> {
                        playlistDao.adoptServerId(
                            localId = localId,
                            serverId = serverId,
                            updatedAt = nowMillis(),
                        )
                        Outcome.Ok
                    }
                    else -> {
                        val row: PlaylistEntity? =
                            SubsonicMappers.playlistEntity(created.value, nowMillis())
                        if (row != null) playlistDao.upsert(row)
                        Outcome.Ok
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- favourites

    private suspend fun replayFavourite(operation: WriteOperation.SetFavourite): Outcome<Unit> {
        val id: String = subsonicIdFor(operation.target)
            ?: return Outcome.Failure(
                // A starred track whose album the mirror no longer knows cannot be addressed on the
                // Subsonic lane at all, so there is nothing to retry.
                unreplayable("no Subsonic id for " + WriteQueueCodec.favouriteEntityKey(operation.target)),
            )
        val call: Outcome<Unit> = networkCall {
            if (operation.starred) starById(id) else unstarById(id)
        }
        if (call is Outcome.Success) {
            favouriteDao.setPendingSync(
                entityType = WriteQueueCodec.favouriteTypeToken(operation.target),
                entityId = WriteQueueCodec.favouriteEntityKey(operation.target),
                pendingSync = false,
            )
        }
        return call
    }

    /**
     * `star` and `unstar` route by id prefix, so albums, artists and tracks can be mixed - but the
     * explicit parameters are less ambiguous and this server accepts both.
     */
    private suspend fun starById(id: String) {
        when {
            SubsonicIds.isAlbumId(id) -> subsonic.star(albumIds = listOf(id))
            SubsonicIds.isArtistId(id) -> subsonic.star(artistIds = listOf(id))
            else -> subsonic.star(ids = listOf(id))
        }
    }

    private suspend fun unstarById(id: String) {
        when {
            SubsonicIds.isAlbumId(id) -> subsonic.unstar(albumIds = listOf(id))
            SubsonicIds.isArtistId(id) -> subsonic.unstar(artistIds = listOf(id))
            else -> subsonic.unstar(ids = listOf(id))
        }
    }

    private suspend fun subsonicIdFor(target: FavouriteTarget): String? = when (target) {
        is FavouriteTarget.OfAlbum -> SubsonicIds.albumId(target.releaseGroupMbid)
        is FavouriteTarget.OfArtist -> SubsonicIds.artistId(target.mbid)
        is FavouriteTarget.OfTrack -> resolveTrackId(target.key)
    }

    /**
     * A stable track key to the `tr-<file id>` the Subsonic lane needs, read **now**.
     *
     * Every playlist and scrobble replay goes through here rather than carrying an id from the time
     * the write was queued, because the server upgrades files in place and the id moves.
     */
    private suspend fun resolveTrackId(key: TrackKey): String? {
        val row: TrackEntity = trackDao.getTrack(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
        ) ?: return null
        if (!EntityMappers.hasPlayableFile(row)) return null
        return SubsonicIds.trackId(row.fileId!!)
    }

    private suspend fun resolveTrackIds(keys: List<TrackKey>): List<String> =
        keys.mapNotNull { resolveTrackId(it) }
}

/**
 * Folds the request lane's in-band refusal onto a modelled failure.
 *
 * `DELETE /api/v1/requests/active/{mbid}` and `POST /api/v1/requests/retry/{mbid}` answer **200 with
 * `success=false`** when they refuse - "cannot cancel a request with status processing", say - and
 * reserve `403` for someone else's request. A refusal is permanent for this entry, so the write
 * queue drops it and tells the user rather than retrying for ever.
 */
private inline fun <T> Outcome<T>.flatMapInBand(
    crossinline read: (T) -> Pair<Boolean, String>,
): Outcome<Unit> = when (this) {
    is Outcome.Failure -> this
    is Outcome.Success -> {
        val (succeeded: Boolean, message: String) = read(value)
        if (succeeded) Outcome.Ok else Outcome.Failure(NeedlerError.Rejected(message = message))
    }
}
