package app.needler.core.data.mapper

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PinSourceDb
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.projection.ArtistIndexRow
import app.needler.core.data.local.projection.DownloadedAlbumRow
import app.needler.core.data.local.projection.PinnedAlbumRow
import app.needler.core.data.local.projection.PlaylistTrackRow
import app.needler.core.data.local.projection.PullRow
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.RecordingMbid
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlinx.datetime.Instant

/**
 * Mirror rows to domain objects: the last hop of every read path.
 *
 * Nothing on a screen is built from a DTO. Reads come from Room and are mapped here, which is what
 * makes offline need no separate code path - the same function answers whether the row landed during
 * a sync a second ago or a fortnight ago on a train.
 */
public object EntityMappers {

    // -------------------------------------------------------------------- albums

    /**
     * One album row as a domain [Album].
     *
     * [pin] and [pull] enrich the two states that carry live detail - a pinned album's download
     * progress and an acquiring album's percentage. List queries pass neither, because a grid needs
     * the badge and not the percentage and joining both tables per row would cost more than it says;
     * detail screens pass both.
     */
    public fun album(
        row: AlbumEntity,
        pin: PinEntity? = null,
        pull: PullEntity? = null,
        isFavourite: Boolean = false,
    ): Album {
        val mbid = ReleaseGroupMbid(row.releaseGroupMbid)
        return Album(
            releaseGroupMbid = mbid,
            title = row.title,
            artistName = row.artistName,
            artistMbid = row.artistMbid?.let { ArtistMbid(it) },
            state = albumState(row, pin, pull),
            year = row.year,
            trackCount = row.trackCount,
            discCount = row.discCount,
            durationMs = row.durationMs,
            quality = AudioQuality(
                format = row.format?.let { AudioFormat.fromServerToken(it) },
                bitrateKbps = row.bitrateKbps,
            ),
            sizeBytes = row.sizeBytes,
            addedAt = WireTime.fromEpochMillis(row.addedAt),
            artwork = artworkFor(row),
            genres = GenreCodec.decode(row.genres),
            isFavourite = isFavourite,
            qualityPolicySummary = row.qualityPolicySummary,
        )
    }

    public fun albumState(row: AlbumEntity, pin: PinEntity?, pull: PullEntity?): AlbumState =
        when (row.state) {
            AlbumStateDb.NOT_OWNED -> AlbumState.NotOwned
            AlbumStateDb.PENDING_APPROVAL -> AlbumState.PendingApproval(
                requestedAt = WireTime.fromEpochMillis(pull?.createdAt),
            )
            AlbumStateDb.ACQUIRING -> AlbumState.Acquiring(
                progress = pull?.let(::pullProgress) ?: PullProgress.Unknown,
                stage = pull?.let { pullState(it) } ?: app.needler.core.domain.model.PullState.QUEUED,
            )
            AlbumStateDb.OWNED -> AlbumState.Owned
            AlbumStateDb.PINNED -> AlbumState.Pinned(
                download = pin?.let(::offlineDownloadState) ?: OfflineDownloadState.Queued,
                pinnedAt = WireTime.fromEpochMillis(pin?.pinnedAt),
                source = pin?.source?.let(::pinSource) ?: PinSource.MANUAL,
            )
            AlbumStateDb.FAILED -> AlbumState.Failed(
                reason = failureReason(pull),
                message = pull?.error,
                failedAt = WireTime.fromEpochMillis(pull?.updatedAt),
            )
        }

    /**
     * Where this album's artwork comes from.
     *
     * The two lanes have different endpoints and the domain records which applies rather than making
     * each surface infer it: owned content is `getCoverArt`, catalogue-only content is
     * `GET /api/v1/covers/release-group/{mbid}`.
     */
    public fun artworkFor(row: AlbumEntity): ArtworkRef? = when {
        row.inLibrary && row.coverArtId != null -> ArtworkRef.Owned(row.coverArtId)
        row.inLibrary -> ArtworkRef.Owned(SubsonicIds.albumId(row.releaseGroupMbid))
        else -> ArtworkRef.Catalogue(ReleaseGroupMbid(row.releaseGroupMbid))
    }

    // ------------------------------------------------------------------- artists

    public fun artist(row: ArtistEntity, isFavourite: Boolean = false): Artist = Artist(
        mbid = ArtistMbid(row.artistMbid),
        name = row.name,
        sortName = row.sortName,
        ownedAlbumCount = row.albumCount,
        catalogueAlbumCount = null,
        artwork = row.artUrl?.let { ArtworkRef.Remote(it) }
            ?: ArtworkRef.Owned(SubsonicIds.artistId(row.artistMbid)),
        isFavourite = isFavourite,
        isMonitored = row.monitored,
    )

    public fun artist(row: ArtistIndexRow): Artist = Artist(
        mbid = ArtistMbid(row.artistMbid),
        name = row.name,
        sortName = row.sortNameNormalised,
        ownedAlbumCount = row.albumCount,
        artwork = row.artUrl?.let { ArtworkRef.Remote(it) }
            ?: ArtworkRef.Owned(SubsonicIds.artistId(row.artistMbid)),
    )

    // -------------------------------------------------------------------- tracks

    public fun track(row: TrackEntity, isFavourite: Boolean = false, albumTitle: String? = null): Track {
        val key = TrackKey(
            releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
            discNumber = row.discNo,
            trackNumber = row.trackNo,
        )
        return Track(
            key = key,
            title = row.title,
            artistName = row.artistName.orEmpty(),
            albumTitle = albumTitle,
            recordingMbid = row.recordingMbid?.let { RecordingMbid(it) },
            durationMs = row.durationMs,
            fetch = fetchHandle(row),
            artwork = ArtworkRef.Owned(SubsonicIds.albumId(row.releaseGroupMbid)),
            isFavourite = isFavourite,
        )
    }

    /**
     * The fetch handle for a mirror row.
     *
     * A row with no `file_id` has nothing to stream - the usual cause is a part-delivered pull, where
     * the track is listed in its right position and greyed. A synthetic id keeps the type total while
     * making it obvious in a log which case it is.
     */
    public fun fetchHandle(row: TrackEntity): TrackFetchHandle = TrackFetchHandle(
        fileId = FileId(row.fileId ?: MISSING_FILE_ID),
        sizeBytes = row.sizeBytes,
        durationMs = row.durationMs,
        format = row.format?.let { AudioFormat.fromServerToken(it) },
        bitrateKbps = row.bitrateKbps,
    )

    /** True when the server has no file behind this row, so there is nothing to stream or download. */
    public fun hasPlayableFile(row: TrackEntity): Boolean = !row.fileId.isNullOrBlank()

    public fun trackKey(key: TrackKeyDb): TrackKey = TrackKey(
        releaseGroupMbid = ReleaseGroupMbid(key.releaseGroupMbid),
        discNumber = key.discNo,
        trackNumber = key.trackNo,
    )

    public fun trackKeyDb(key: TrackKey): TrackKeyDb = TrackKeyDb(
        releaseGroupMbid = key.releaseGroupMbid.value,
        discNo = key.discNumber,
        trackNo = key.trackNumber,
    )

    // ------------------------------------------------------------------- offline

    public fun cachedAudio(row: AudioCacheEntity): CachedAudio = CachedAudio(
        key = TrackKey(
            releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
            discNumber = row.discNo,
            trackNumber = row.trackNo,
        ),
        filePath = row.filePath,
        sizeOnDiskBytes = row.sizeBytes,
        isComplete = row.complete,
        lastPlayedAt = WireTime.fromEpochMillis(row.lastPlayedAt),
        pinned = row.pinned,
        // The fingerprint as it was at download time. Sync never touches it, which is the only reason
        // the staleness check can see a quality upgrade at all.
        sourceHandle = TrackFetchHandle(
            fileId = FileId(row.sourceFileId ?: MISSING_FILE_ID),
            sizeBytes = row.sourceSizeBytes,
            durationMs = row.sourceDurationMs,
            format = row.sourceFormat?.let { AudioFormat.fromServerToken(it) },
            bitrateKbps = row.sourceBitrateKbps,
        ),
        downloadedAt = WireTime.fromEpochMillis(row.downloadedAt),
    )

    public fun pin(row: PinEntity): Pin = Pin(
        releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
        pinnedAt = Instant.fromEpochMilliseconds(row.pinnedAt),
        source = pinSource(row.source),
        download = offlineDownloadState(row),
    )

    public fun pin(row: PinnedAlbumRow): Pin = Pin(
        releaseGroupMbid = ReleaseGroupMbid(row.album.releaseGroupMbid),
        pinnedAt = Instant.fromEpochMilliseconds(row.pinnedAt),
        source = pinSource(row.source),
        download = offlineDownloadState(
            state = row.downloadState,
            tracksComplete = row.tracksComplete,
            tracksTotal = row.tracksTotal,
            downloadedBytes = row.downloadedBytes,
            totalBytes = row.totalBytes,
            error = row.error,
        ),
    )

    public fun downloadedAlbum(row: DownloadedAlbumRow): DownloadedAlbum = DownloadedAlbum(
        releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
        title = row.title,
        artistName = row.artistName,
        // What is actually on disk, not what the server says the album weighs: a part-downloaded or
        // partly-evicted album must show the bytes a removal would really free.
        sizeBytes = row.sizeBytes,
        pinnedAt = Instant.fromEpochMilliseconds(row.pinnedAt),
    )

    public fun pinSource(source: PinSourceDb): PinSource = when (source) {
        PinSourceDb.MANUAL -> PinSource.MANUAL
        PinSourceDb.AUTO_PULLED -> PinSource.AUTO_PULLED
    }

    public fun pinSourceDb(source: PinSource): PinSourceDb = when (source) {
        PinSource.MANUAL -> PinSourceDb.MANUAL
        PinSource.AUTO_PULLED -> PinSourceDb.AUTO_PULLED
    }

    public fun offlineDownloadState(row: PinEntity): OfflineDownloadState = offlineDownloadState(
        state = row.downloadState,
        tracksComplete = row.tracksComplete,
        tracksTotal = row.tracksTotal,
        downloadedBytes = row.downloadedBytes,
        totalBytes = row.totalBytes,
        error = row.error,
    )

    public fun offlineDownloadState(
        state: DownloadStateDb,
        tracksComplete: Int,
        tracksTotal: Int,
        downloadedBytes: Long?,
        totalBytes: Long?,
        error: String?,
    ): OfflineDownloadState = when (state) {
        DownloadStateDb.QUEUED -> OfflineDownloadState.Queued
        DownloadStateDb.DOWNLOADING -> OfflineDownloadState.Downloading(
            tracksComplete = tracksComplete,
            tracksTotal = tracksTotal,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
        )
        DownloadStateDb.WAITING_FOR_UNMETERED -> OfflineDownloadState.WaitingForUnmeteredNetwork
        DownloadStateDb.COMPLETE -> OfflineDownloadState.Complete
        DownloadStateDb.PARTIAL -> OfflineDownloadState.Partial(
            tracksComplete = tracksComplete,
            tracksTotal = tracksTotal,
        )
        DownloadStateDb.FAILED -> OfflineDownloadState.Failed(
            error = app.needler.core.domain.model.NeedlerError.Unexpected(error),
        )
    }

    // ------------------------------------------------------------------ playlists

    public fun playlist(row: PlaylistEntity, hasPendingLocalEdits: Boolean = false): Playlist = Playlist(
        id = PlaylistId(row.playlistId),
        name = row.name,
        trackCount = row.trackCount,
        durationMs = row.durationMs,
        owner = row.owner,
        isPublic = row.isPublic,
        comment = row.comment,
        createdAt = WireTime.fromEpochMillis(row.createdAt),
        changedAt = WireTime.fromEpochMillis(row.changedAt),
        artwork = row.coverArtId?.let { ArtworkRef.Owned(it) },
        // A playlist created offline has never been seen by the server. Both that and an unreplayed
        // edit read as "pending" to the UI, which is the honest thing to say about either.
        hasPendingLocalEdits = hasPendingLocalEdits || row.localOnly,
    )

    /**
     * One playlist entry.
     *
     * The row left-joins `track`, so a track the mirror has temporarily lost still holds its
     * position - the playlist keeps its shape rather than silently shrinking.
     */
    public fun playlistEntry(row: PlaylistTrackRow): PlaylistEntry = PlaylistEntry(
        position = row.position,
        track = Track(
            key = trackKey(row.key),
            title = row.title ?: UNKNOWN_TRACK_TITLE,
            artistName = row.artistName.orEmpty(),
            durationMs = row.durationMs,
            fetch = TrackFetchHandle(
                fileId = FileId(row.fileId ?: MISSING_FILE_ID),
                sizeBytes = null,
                durationMs = row.durationMs,
                format = row.format?.let { AudioFormat.fromServerToken(it) },
                bitrateKbps = row.bitrateKbps,
            ),
            artwork = ArtworkRef.Owned(SubsonicIds.albumId(row.key.releaseGroupMbid)),
        ),
    )

    // ---------------------------------------------------------------------- pulls

    public fun pull(row: PullRow, isPendingSubmission: Boolean = false): Pull = Pull(
        releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
        albumTitle = row.albumTitle.orEmpty(),
        artistName = row.albumArtistName.orEmpty(),
        taskId = row.taskId?.takeIf { it.isNotBlank() }?.let { PullTaskId(it) },
        status = serverStatus(row.status),
        searchJobId = row.searchJobId,
        candidateIndex = row.candidateIndex,
        progress = PullProgress(
            percent = row.percent.takeIf { it > 0 || row.status == PullStatusDb.DOWNLOADING },
            filesCompleted = row.filesDone,
            filesTotal = row.filesTotal,
            downloadedBytes = row.downloadedBytes,
            totalSizeBytes = row.totalSizeBytes,
        ),
        source = row.source,
        error = row.error,
        failureReason = failureReasonFor(row.status, row.error),
        createdAt = WireTime.fromEpochMillis(row.createdAt),
        awaitingApproval = row.status == PullStatusDb.PENDING_APPROVAL,
        isPendingSubmission = isPendingSubmission,
    )

    public fun pull(row: PullEntity, title: String = "", artist: String = ""): Pull = Pull(
        releaseGroupMbid = ReleaseGroupMbid(row.releaseGroupMbid),
        albumTitle = title,
        artistName = artist,
        taskId = row.taskId?.takeIf { it.isNotBlank() }?.let { PullTaskId(it) },
        status = serverStatus(row.status),
        searchJobId = row.searchJobId,
        candidateIndex = row.candidateIndex,
        progress = pullProgress(row),
        source = row.source,
        error = row.error,
        failureReason = failureReasonFor(row.status, row.error),
        createdAt = WireTime.fromEpochMillis(row.createdAt),
        updatedAt = WireTime.fromEpochMillis(row.updatedAt),
        awaitingApproval = row.status == PullStatusDb.PENDING_APPROVAL,
        isPendingSubmission = row.taskId == null && row.status == PullStatusDb.QUEUED,
    )

    public fun pullProgress(row: PullEntity): PullProgress = PullProgress(
        percent = row.percent.takeIf { it > 0 },
        filesCompleted = row.filesDone,
        filesTotal = row.filesTotal,
        downloadedBytes = row.downloadedBytes,
        totalSizeBytes = row.totalSizeBytes,
    )

    public fun pullState(row: PullEntity): app.needler.core.domain.model.PullState =
        app.needler.core.domain.model.PullState.derive(
            status = serverStatus(row.status),
            searchJobId = row.searchJobId,
            candidateIndex = row.candidateIndex,
            awaitingApproval = row.status == PullStatusDb.PENDING_APPROVAL,
        )

    /**
     * The stored status back to the server's own vocabulary.
     *
     * The `pull` table carries the two client-derived states as rows of their own, because the Pulls
     * screen filters on them. Mapping back loses nothing: the derivation runs again from
     * `search_job_id` and `candidate_index`, which are stored beside them.
     */
    public fun serverStatus(status: PullStatusDb): PullStatus = when (status) {
        PullStatusDb.PENDING_APPROVAL, PullStatusDb.SEARCHING,
        PullStatusDb.NEEDS_ATTENTION, PullStatusDb.QUEUED,
        -> PullStatus.QUEUED
        PullStatusDb.DOWNLOADING -> PullStatus.DOWNLOADING
        PullStatusDb.PROCESSING -> PullStatus.PROCESSING
        PullStatusDb.COMPLETED -> PullStatus.COMPLETED
        PullStatusDb.PARTIAL -> PullStatus.PARTIAL
        PullStatusDb.FAILED -> PullStatus.FAILED
        PullStatusDb.CANCELLED -> PullStatus.CANCELLED
    }

    /**
     * The server's status plus the search-job fields, collapsed onto the stored enum.
     *
     * `queued` alone means three different things and the server does not collapse them, so this is
     * the one place the derivation is applied on the way *in*, mirroring `PullState.derive` on the
     * way out.
     */
    public fun storedStatus(
        status: PullStatus,
        searchJobId: String?,
        candidateIndex: Int?,
        awaitingApproval: Boolean,
    ): PullStatusDb {
        if (awaitingApproval) return PullStatusDb.PENDING_APPROVAL
        return when (status) {
            PullStatus.QUEUED -> when {
                searchJobId == null -> PullStatusDb.SEARCHING
                candidateIndex == null -> PullStatusDb.NEEDS_ATTENTION
                else -> PullStatusDb.QUEUED
            }
            PullStatus.DOWNLOADING -> PullStatusDb.DOWNLOADING
            PullStatus.PROCESSING -> PullStatusDb.PROCESSING
            PullStatus.COMPLETED -> PullStatusDb.COMPLETED
            PullStatus.PARTIAL -> PullStatusDb.PARTIAL
            PullStatus.FAILED -> PullStatusDb.FAILED
            PullStatus.CANCELLED -> PullStatusDb.CANCELLED
        }
    }

    /** The album state a pull implies, so the two tables never disagree about one release group. */
    public fun albumStateFor(status: PullStatusDb): AlbumStateDb = when (status) {
        PullStatusDb.PENDING_APPROVAL -> AlbumStateDb.PENDING_APPROVAL
        PullStatusDb.SEARCHING, PullStatusDb.NEEDS_ATTENTION, PullStatusDb.QUEUED,
        PullStatusDb.DOWNLOADING, PullStatusDb.PROCESSING,
        -> AlbumStateDb.ACQUIRING
        // A partial pull is in the library and plays: the tracks that arrived are real, and the ones
        // that did not are listed in their right positions rather than hidden.
        PullStatusDb.COMPLETED, PullStatusDb.PARTIAL -> AlbumStateDb.OWNED
        PullStatusDb.FAILED -> AlbumStateDb.FAILED
        PullStatusDb.CANCELLED -> AlbumStateDb.NOT_OWNED
    }

    private fun failureReason(pull: PullEntity?): PullFailureReason =
        failureReasonFor(pull?.status, pull?.error) ?: PullFailureReason.UNKNOWN

    private fun failureReasonFor(status: PullStatusDb?, error: String?): PullFailureReason? {
        if (status == PullStatusDb.CANCELLED) return PullFailureReason.CANCELLED
        if (status != PullStatusDb.FAILED && status != PullStatusDb.PARTIAL) return null
        val message: String = error?.lowercase().orEmpty()
        return when {
            message.contains("no source") || message.contains("not found") ->
                PullFailureReason.NO_SOURCE_FOUND
            message.contains("import") || message.contains("tag") -> PullFailureReason.IMPORT_FAILED
            message.contains("held") || message.contains("quarantine") ->
                PullFailureReason.HELD_FOR_REVIEW
            message.contains("reject") -> PullFailureReason.REJECTED
            message.isNotEmpty() -> PullFailureReason.DOWNLOAD_FAILED
            else -> PullFailureReason.UNKNOWN
        }
    }

    /**
     * The placeholder used when a mirror row has no `file_id`.
     *
     * `FileId` refuses to be blank, and a track with no server file is a real state - a
     * part-delivered pull leaves exactly that. Callers gate on [hasPlayableFile]; this value exists
     * so the type stays total and so the cause is obvious in a diagnostics log.
     */
    public const val MISSING_FILE_ID: String = "unavailable"

    private const val UNKNOWN_TRACK_TITLE: String = "Unknown track"
}
