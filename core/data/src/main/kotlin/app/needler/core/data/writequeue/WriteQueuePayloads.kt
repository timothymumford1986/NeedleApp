package app.needler.core.data.writequeue

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.domain.model.AlbumRequest
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.RecordingMbid
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackRequest
import app.needler.core.domain.model.WriteOperation
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How a journalled mutation is stored.
 *
 * `write_queue` keeps an operation type and an opaque JSON payload, so the payload shapes live here
 * and are versioned by being additive: every field is optional or defaulted, because a queue row
 * written by an older build must still replay after an update rather than be dropped - the user's
 * offline star is not worth losing to a schema change.
 */
@Serializable
public data class WriteQueuePayload(
    @SerialName("release_group_mbid") val releaseGroupMbid: String? = null,
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("monitor_artist") val monitorArtist: Boolean = false,
    @SerialName("playlist_id") val playlistId: String? = null,
    @SerialName("playlist_name") val playlistName: String? = null,
    @SerialName("track_keys") val trackKeys: List<String> = emptyList(),
    @SerialName("positions") val positions: List<Int> = emptyList(),
    @SerialName("favourite_type") val favouriteType: String? = null,
    @SerialName("favourite_id") val favouriteId: String? = null,
    @SerialName("track_key") val trackKey: String? = null,
    @SerialName("played_at_millis") val playedAtMillis: Long? = null,
    @SerialName("submission") val submission: Boolean = true,
    /**
     * The file id the play actually used.
     *
     * Kept for the record only: a scrobble is **re-resolved against the mirror at flush time**,
     * because a quality upgrade between the play and the reconnect moves the file id on and
     * submitting the stale one scrobbles a row the server no longer has.
     */
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("format") val format: String? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
)

/** Serialises a [WriteOperation] to a row's `operation_type` + `payload` + `entity_key`. */
public object WriteQueueCodec {

    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(operation: WriteOperation): EncodedWrite = when (operation) {
        is WriteOperation.PlaceAlbumRequest -> EncodedWrite(
            type = WriteOperationTypeDb.PULL_REQUEST,
            entityKey = operation.request.releaseGroupMbid.value,
            payload = WriteQueuePayload(
                releaseGroupMbid = operation.request.releaseGroupMbid.value,
                albumTitle = operation.request.albumTitle,
                artistName = operation.request.artistName,
                year = operation.request.year,
                monitorArtist = operation.request.monitorArtist,
            ),
        )

        is WriteOperation.PlaceTrackRequest -> EncodedWrite(
            type = WriteOperationTypeDb.PULL_REQUEST,
            entityKey = operation.request.recordingMbid.value,
            payload = WriteQueuePayload(
                recordingMbid = operation.request.recordingMbid.value,
                trackTitle = operation.request.trackTitle,
                artistName = operation.request.artistName,
                monitorArtist = operation.request.monitorArtist,
            ),
        )

        is WriteOperation.CancelRequest -> EncodedWrite(
            type = WriteOperationTypeDb.PULL_CANCEL,
            entityKey = operation.releaseGroupMbid.value,
            payload = WriteQueuePayload(releaseGroupMbid = operation.releaseGroupMbid.value),
        )

        is WriteOperation.RetryRequest -> EncodedWrite(
            type = WriteOperationTypeDb.PULL_RETRY,
            entityKey = operation.releaseGroupMbid.value,
            payload = WriteQueuePayload(releaseGroupMbid = operation.releaseGroupMbid.value),
        )

        is WriteOperation.EditPlaylist -> encodePlaylistEdit(operation.edit)

        is WriteOperation.SetFavourite -> EncodedWrite(
            type = if (operation.starred) WriteOperationTypeDb.STAR else WriteOperationTypeDb.UNSTAR,
            entityKey = favouriteEntityKey(operation.target),
            payload = WriteQueuePayload(
                favouriteType = favouriteTypeToken(operation.target),
                favouriteId = favouriteEntityKey(operation.target),
                trackKey = (operation.target as? FavouriteTarget.OfTrack)?.key?.canonicalString,
            ),
        )

        is WriteOperation.SubmitScrobble -> EncodedWrite(
            type = WriteOperationTypeDb.SCROBBLE,
            entityKey = operation.scrobble.trackKey.canonicalString,
            payload = WriteQueuePayload(
                trackKey = operation.scrobble.trackKey.canonicalString,
                playedAtMillis = operation.scrobble.playedAt.toEpochMilliseconds(),
                submission = operation.scrobble.submission,
                fileId = operation.scrobble.fetchHandle.fileId.value,
                durationMs = operation.scrobble.fetchHandle.durationMs,
                sizeBytes = operation.scrobble.fetchHandle.sizeBytes,
                format = operation.scrobble.fetchHandle.format?.name,
                bitrateKbps = operation.scrobble.fetchHandle.bitrateKbps,
            ),
        )
    }

    private fun encodePlaylistEdit(edit: PlaylistEdit): EncodedWrite = when (edit) {
        is PlaylistEdit.Create -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_CREATE,
            entityKey = null,
            payload = WriteQueuePayload(
                playlistName = edit.name,
                trackKeys = edit.trackKeys.map { it.canonicalString },
            ),
        )
        is PlaylistEdit.Rename -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_RENAME,
            entityKey = edit.id.value,
            payload = WriteQueuePayload(playlistId = edit.id.value, playlistName = edit.name),
        )
        is PlaylistEdit.AddTracks -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_ADD_TRACKS,
            entityKey = edit.id.value,
            payload = WriteQueuePayload(
                playlistId = edit.id.value,
                trackKeys = edit.trackKeys.map { it.canonicalString },
            ),
        )
        is PlaylistEdit.RemoveTracks -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_REMOVE_TRACKS,
            entityKey = edit.id.value,
            payload = WriteQueuePayload(playlistId = edit.id.value, positions = edit.positions),
        )
        is PlaylistEdit.Reorder -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_REORDER,
            entityKey = edit.id.value,
            payload = WriteQueuePayload(
                playlistId = edit.id.value,
                trackKeys = edit.trackKeys.map { it.canonicalString },
            ),
        )
        is PlaylistEdit.Delete -> EncodedWrite(
            type = WriteOperationTypeDb.PLAYLIST_DELETE,
            entityKey = edit.id.value,
            payload = WriteQueuePayload(playlistId = edit.id.value),
        )
    }

    /**
     * Back to a [WriteOperation].
     *
     * Returns null for a row this build cannot make sense of - a payload from a future version, or
     * one corrupted on disk. The caller drops it rather than retrying forever, and says so.
     */
    public fun decode(type: WriteOperationTypeDb, payload: String): WriteOperation? {
        val body: WriteQueuePayload = runCatching {
            json.decodeFromString(WriteQueuePayload.serializer(), payload)
        }.getOrNull() ?: return null
        return when (type) {
            WriteOperationTypeDb.PULL_REQUEST -> decodeRequest(body)
            WriteOperationTypeDb.PULL_CANCEL -> body.releaseGroupMbid
                ?.let { WriteOperation.CancelRequest(ReleaseGroupMbid(it)) }
            WriteOperationTypeDb.PULL_RETRY -> body.releaseGroupMbid
                ?.let { WriteOperation.RetryRequest(ReleaseGroupMbid(it)) }
            WriteOperationTypeDb.PLAYLIST_CREATE -> WriteOperation.EditPlaylist(
                PlaylistEdit.Create(
                    name = body.playlistName.orEmpty(),
                    trackKeys = body.trackKeys.mapNotNull(::trackKey),
                ),
            )
            WriteOperationTypeDb.PLAYLIST_RENAME -> body.playlistId?.let { id ->
                WriteOperation.EditPlaylist(
                    PlaylistEdit.Rename(PlaylistId(id), body.playlistName.orEmpty()),
                )
            }
            WriteOperationTypeDb.PLAYLIST_ADD_TRACKS -> body.playlistId?.let { id ->
                WriteOperation.EditPlaylist(
                    PlaylistEdit.AddTracks(PlaylistId(id), body.trackKeys.mapNotNull(::trackKey)),
                )
            }
            WriteOperationTypeDb.PLAYLIST_REMOVE_TRACKS -> body.playlistId?.let { id ->
                WriteOperation.EditPlaylist(PlaylistEdit.RemoveTracks(PlaylistId(id), body.positions))
            }
            WriteOperationTypeDb.PLAYLIST_REORDER -> body.playlistId?.let { id ->
                WriteOperation.EditPlaylist(
                    PlaylistEdit.Reorder(PlaylistId(id), body.trackKeys.mapNotNull(::trackKey)),
                )
            }
            WriteOperationTypeDb.PLAYLIST_DELETE -> body.playlistId?.let { id ->
                WriteOperation.EditPlaylist(PlaylistEdit.Delete(PlaylistId(id)))
            }
            WriteOperationTypeDb.STAR -> favouriteTarget(body)
                ?.let { WriteOperation.SetFavourite(it, starred = true) }
            WriteOperationTypeDb.UNSTAR -> favouriteTarget(body)
                ?.let { WriteOperation.SetFavourite(it, starred = false) }
            WriteOperationTypeDb.SCROBBLE -> decodeScrobble(body)
        }
    }

    private fun decodeRequest(body: WriteQueuePayload): WriteOperation? {
        val recording: String? = body.recordingMbid
        if (recording != null) {
            return WriteOperation.PlaceTrackRequest(
                TrackRequest(
                    recordingMbid = RecordingMbid(recording),
                    trackTitle = body.trackTitle,
                    artistName = body.artistName,
                    monitorArtist = body.monitorArtist,
                ),
            )
        }
        val album: String = body.releaseGroupMbid ?: return null
        return WriteOperation.PlaceAlbumRequest(
            AlbumRequest(
                releaseGroupMbid = ReleaseGroupMbid(album),
                albumTitle = body.albumTitle,
                artistName = body.artistName,
                year = body.year,
                monitorArtist = body.monitorArtist,
            ),
        )
    }

    private fun decodeScrobble(body: WriteQueuePayload): WriteOperation? {
        val key: TrackKey = trackKey(body.trackKey ?: return null) ?: return null
        val playedAt: Long = body.playedAtMillis ?: return null
        return WriteOperation.SubmitScrobble(
            ScrobbleEvent(
                trackKey = key,
                fetchHandle = TrackFetchHandle(
                    fileId = FileId(body.fileId ?: EntityMappers.MISSING_FILE_ID),
                    sizeBytes = body.sizeBytes,
                    durationMs = body.durationMs,
                    format = body.format?.let { name ->
                        runCatching { AudioFormat.valueOf(name) }.getOrNull()
                    },
                    bitrateKbps = body.bitrateKbps,
                ),
                playedAt = Instant.fromEpochMilliseconds(playedAt),
                submission = body.submission,
            ),
        )
    }

    public fun trackKey(canonical: String): TrackKey? =
        TrackKeyDb.parse(canonical)?.let(EntityMappers::trackKey)

    private fun favouriteTarget(body: WriteQueuePayload): FavouriteTarget? = when (body.favouriteType) {
        "album" -> body.favouriteId?.let { FavouriteTarget.OfAlbum(ReleaseGroupMbid(it)) }
        "artist" -> body.favouriteId?.let {
            FavouriteTarget.OfArtist(app.needler.core.domain.model.ArtistMbid(it))
        }
        "track" -> body.trackKey?.let(::trackKey)?.let { FavouriteTarget.OfTrack(it) }
        else -> null
    }

    public fun favouriteTypeToken(target: FavouriteTarget): String = when (target) {
        is FavouriteTarget.OfAlbum -> "album"
        is FavouriteTarget.OfArtist -> "artist"
        is FavouriteTarget.OfTrack -> "track"
    }

    /**
     * The `entity_key` column: what this write is *about*.
     *
     * It is indexed so a later write about the same thing can find and supersede an earlier one -
     * three offline stars of the same album must replay once, not three times.
     */
    public fun favouriteEntityKey(target: FavouriteTarget): String = when (target) {
        is FavouriteTarget.OfAlbum -> target.releaseGroupMbid.value
        is FavouriteTarget.OfArtist -> target.mbid.value
        is FavouriteTarget.OfTrack -> target.key.canonicalString
    }
}

/** One encoded write, ready for a `write_queue` row. */
public data class EncodedWrite(
    val type: WriteOperationTypeDb,
    val entityKey: String?,
    val payload: WriteQueuePayload,
) {
    public fun payloadJson(): String =
        WriteQueueCodec.json.encodeToString(WriteQueuePayload.serializer(), payload)
}
