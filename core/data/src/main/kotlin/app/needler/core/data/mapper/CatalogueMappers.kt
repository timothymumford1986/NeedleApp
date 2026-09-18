package app.needler.core.data.mapper

import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
import app.needler.core.domain.model.PullTaskId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.SuggestionKind
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.network.v1.dto.ActiveRequestItemDto
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import app.needler.core.network.v1.dto.DownloadTaskDto
import app.needler.core.network.v1.dto.LibraryStatsDto
import app.needler.core.network.v1.dto.ReleaseItemDto
import app.needler.core.network.v1.dto.RequestAcceptedDto
import app.needler.core.network.v1.dto.SearchResultDto
import app.needler.core.network.v1.dto.SuggestResultDto
import app.needler.core.network.v1.dto.UserDto

/**
 * The catalogue lane: `/api/v1` DTOs to domain objects and mirror rows.
 *
 * A catalogue album is **the same `Album`** as a library album, at a different state - the join key
 * is the release-group MBID, which `al-<mbid>` carries on the other lane and
 * `POST /api/v1/requests/new` takes bare. That is why there is no search-result type to map to here:
 * a hit that the mirror also knows about is replaced by the mirror's copy on merge, and the rest
 * land as [AlbumState.NotOwned].
 */
public object CatalogueMappers {

    // -------------------------------------------------------------------- search

    /** An album hit. `musicbrainz_id` is the release-group MBID when `type` is `album`. */
    public fun album(dto: SearchResultDto): Album? {
        val mbid: String = dto.musicbrainzId.trim()
        if (mbid.isEmpty() || !dto.type.equals("album", ignoreCase = true)) return null
        return Album(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            title = dto.title,
            artistName = dto.artist.orEmpty(),
            artistMbid = null,
            // `in_library` is the server's own answer and is trusted over anything inferred. The
            // merge still prefers the mirror's row when it has one, because that row knows whether
            // the album is merely owned or already on the device.
            state = if (dto.inLibrary) AlbumState.Owned else AlbumState.NotOwned,
            year = dto.year,
            artwork = ArtworkRef.Catalogue(ReleaseGroupMbid(mbid)),
        )
    }

    /** An artist hit. `musicbrainz_id` is the artist MBID when `type` is `artist`. */
    public fun artist(dto: SearchResultDto): Artist? {
        val mbid: String = dto.musicbrainzId.trim()
        if (mbid.isEmpty() || !dto.type.equals("artist", ignoreCase = true)) return null
        return Artist(
            mbid = ArtistMbid(mbid),
            name = dto.title,
            sortName = dto.title,
            artwork = (dto.thumbUrl ?: dto.fanartUrl ?: dto.bannerUrl)?.let { ArtworkRef.Remote(it) },
        )
    }

    public fun suggestion(dto: SuggestResultDto): SearchSuggestion? {
        val mbid: String = dto.musicbrainzId.trim()
        val isAlbum: Boolean = dto.type.equals("album", ignoreCase = true)
        val isArtist: Boolean = dto.type.equals("artist", ignoreCase = true)
        if (dto.title.isBlank()) return null
        return SearchSuggestion(
            text = dto.title,
            kind = when {
                isAlbum -> SuggestionKind.ALBUM
                isArtist -> SuggestionKind.ARTIST
                else -> SuggestionKind.QUERY
            },
            releaseGroupMbid = if (isAlbum && mbid.isNotEmpty()) ReleaseGroupMbid(mbid) else null,
            artistMbid = if (isArtist && mbid.isNotEmpty()) ArtistMbid(mbid) else null,
        )
    }

    /**
     * `service_status`, which reports that MusicBrainz upstream is struggling rather than that the
     * request failed. It must surface as a quiet inline note; the results beside it are still good.
     */
    public fun serviceStatus(raw: Map<String, String>?): ServiceStatus? {
        if (raw.isNullOrEmpty()) return null
        val degraded: List<String> = raw.entries
            .filter { !it.value.equals("ok", ignoreCase = true) }
            .map { it.key + ": " + it.value }
        if (degraded.isEmpty()) return null
        return ServiceStatus(
            isDegraded = true,
            message = degraded.joinToString(", "),
            raw = raw.toString(),
        )
    }

    // ------------------------------------------------------------- discographies

    /** One release group from `GET /api/v1/artists/{mbid}/releases`. */
    public fun album(dto: ReleaseItemDto, artistName: String, artistMbid: ArtistMbid?): Album? {
        val mbid: String = dto.id?.trim().orEmpty()
        if (mbid.isEmpty()) return null
        return Album(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            title = dto.title.orEmpty(),
            artistName = artistName,
            artistMbid = artistMbid,
            state = if (dto.inLibrary) AlbumState.Owned else AlbumState.NotOwned,
            year = dto.year ?: dto.firstReleaseDate?.take(4)?.toIntOrNull(),
            artwork = ArtworkRef.Catalogue(ReleaseGroupMbid(mbid)),
        )
    }

    /**
     * A catalogue album as a mirror row, so artist detail and search still render offline.
     *
     * Such a row is un-owned and prunable: the mirror drops un-owned rows nothing references and
     * nothing has touched recently, so a long session browsing MusicBrainz cannot grow it without
     * bound.
     */
    public fun albumEntity(album: Album, now: Long): AlbumEntity = AlbumEntity(
        releaseGroupMbid = album.releaseGroupMbid.value,
        artistMbid = album.artistMbid?.value,
        artistName = album.artistName,
        title = album.title,
        titleNormalised = SortKeys.normalise(album.title),
        artistNormalised = SortKeys.normalise(album.artistName),
        year = album.year,
        trackCount = album.trackCount,
        discCount = album.discCount,
        durationMs = album.durationMs,
        format = null,
        bitrateKbps = null,
        state = AlbumStateDb.NOT_OWNED,
        inLibrary = false,
        addedAt = null,
        sizeBytes = null,
        coverArtId = null,
        genres = GenreCodec.encode(album.genres),
        qualityPolicySummary = album.qualityPolicySummary,
        updatedAt = now,
    )

    // --------------------------------------------------------------------- pulls

    /**
     * A download task as a `pull` row.
     *
     * `search_job_id` and `candidate_index` are stored because the two client-derived states -
     * Searching, and "needs attention on the server" - cannot be derived without them, and the
     * screen requires both.
     */
    public fun pullEntity(dto: DownloadTaskDto, now: Long, requestedByThisDevice: Boolean = true): PullEntity? {
        val mbid: String = dto.releaseGroupMbid.trim()
        if (mbid.isEmpty()) return null
        val status: PullStatus = PullStatus.fromServerToken(dto.status)
        return PullEntity(
            releaseGroupMbid = mbid,
            taskId = dto.id.takeIf { it.isNotBlank() },
            status = EntityMappers.storedStatus(
                status = status,
                searchJobId = dto.searchJobId,
                candidateIndex = dto.candidateIndex,
                awaitingApproval = false,
            ),
            percent = dto.progressPercent.coerceIn(0, 100),
            filesDone = dto.filesCompleted,
            filesTotal = dto.filesTotal,
            downloadedBytes = dto.downloadedBytes.takeIf { it > 0L },
            totalSizeBytes = dto.totalSizeBytes,
            source = dto.source,
            error = dto.errorMessage ?: heldNotice(dto),
            searchJobId = dto.searchJobId,
            candidateIndex = dto.candidateIndex,
            requestedByThisDevice = requestedByThisDevice,
            createdAt = WireTime.toEpochMillis(WireTime.fromEpochSeconds(dto.createdAt)) ?: now,
            updatedAt = WireTime.toEpochMillis(WireTime.fromEpochSeconds(dto.updatedAt)) ?: now,
        )
    }

    /**
     * A request still waiting for an admin, from `GET /api/v1/requests/active`.
     *
     * These carry no task id - there is no download yet - so they are stored with
     * [PullStatusDb.PENDING_APPROVAL] and render with the waiting-for-approval state made explicit,
     * which is the only way such a pull is distinguishable from one making no progress.
     */
    public fun pendingApprovalEntity(dto: ActiveRequestItemDto, now: Long): PullEntity? {
        val mbid: String = (dto.trackReleaseGroupMbid ?: dto.musicbrainzId).trim()
        if (mbid.isEmpty()) return null
        val awaiting: Boolean = dto.status.equals("awaiting_approval", ignoreCase = true) ||
            dto.status.equals("pending", ignoreCase = true)
        val status: PullStatus = PullStatus.fromServerToken(dto.downloadStatus ?: dto.status)
        val createdAt: Long = WireTime.toEpochMillis(WireTime.fromIso(dto.requestedAt)) ?: now
        return PullEntity(
            releaseGroupMbid = mbid,
            taskId = null,
            status = EntityMappers.storedStatus(
                status = status,
                searchJobId = null,
                candidateIndex = null,
                awaitingApproval = awaiting,
            ),
            percent = dto.progress?.toInt()?.coerceIn(0, 100) ?: 0,
            filesDone = 0,
            filesTotal = 0,
            downloadedBytes = null,
            totalSizeBytes = dto.size?.toLong(),
            source = dto.protocol ?: dto.downloadClient,
            error = dto.errorMessage,
            searchJobId = null,
            candidateIndex = null,
            requestedByThisDevice = true,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    /** The title and artist hints an active-request row carries, for a mirror row that has none. */
    public fun placeholderAlbumEntity(
        releaseGroupMbid: String,
        title: String,
        artistName: String,
        artistMbid: String?,
        year: Int?,
        now: Long,
    ): AlbumEntity = AlbumEntity(
        releaseGroupMbid = releaseGroupMbid,
        artistMbid = artistMbid,
        artistName = artistName,
        title = title,
        titleNormalised = SortKeys.normalise(title),
        artistNormalised = SortKeys.normalise(artistName),
        year = year,
        trackCount = null,
        discCount = null,
        durationMs = null,
        format = null,
        bitrateKbps = null,
        state = AlbumStateDb.NOT_OWNED,
        inLibrary = false,
        addedAt = null,
        sizeBytes = null,
        coverArtId = null,
        genres = null,
        qualityPolicySummary = null,
        updatedAt = now,
    )

    public fun activitySummary(dto: DownloadActivitySummaryDto): PullActivitySummary = PullActivitySummary(
        revision = dto.revision,
        activeCount = dto.activeCount,
        heldCount = dto.heldCount,
        failedCount = dto.failedCount,
        landedReleaseGroupMbids = dto.landedReleaseGroupMbids
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .map { ReleaseGroupMbid(it) },
    )

    public fun progress(dto: DownloadTaskDto): PullProgress = PullProgress(
        percent = dto.progressPercent.takeIf { it > 0 },
        filesCompleted = dto.filesCompleted,
        filesTotal = dto.filesTotal,
        downloadedBytes = dto.downloadedBytes.takeIf { it > 0L },
        totalSizeBytes = dto.totalSizeBytes,
    )

    /**
     * What the server said when a request was placed.
     *
     * The status is rendered as returned and never inferred from the cached role: an admin may have
     * changed the role moments earlier, and the server is the only authority on whether this
     * particular request needs approval.
     */
    public fun receipt(dto: RequestAcceptedDto): RequestReceipt = RequestReceipt(
        releaseGroupMbid = dto.musicbrainzId.trim().takeIf { it.isNotEmpty() }?.let { ReleaseGroupMbid(it) },
        status = if (!dto.success) RequestStatus.REJECTED else RequestStatus.fromServerToken(dto.status),
        taskId = null,
        qualityPolicySummary = dto.qualitySnapshotSummary,
        message = dto.message.takeIf { it.isNotBlank() },
    )

    public fun trackReceipt(status: String?, taskId: String?): RequestReceipt = RequestReceipt(
        releaseGroupMbid = null,
        status = RequestStatus.fromServerToken(status),
        taskId = taskId?.takeIf { it.isNotBlank() }?.let { PullTaskId(it) },
    )

    // ------------------------------------------------------------- stats and user

    /**
     * `GET /api/v1/library/stats`.
     *
     * The live route returns `total_albums`, `total_size_bytes` and `last_scan_at` - the last of
     * which is epoch seconds as a **float**. There is no `album_count` and no `db_size_bytes`.
     */
    public fun libraryStats(dto: LibraryStatsDto): LibraryStats = LibraryStats(
        albumCount = dto.totalAlbums,
        artistCount = dto.totalArtists,
        trackCount = dto.totalTracks,
        totalSizeBytes = dto.totalSizeBytes.takeIf { it > 0L },
        lastScanAt = WireTime.fromEpochSeconds(dto.lastScanAt),
        source = StatsSource.SERVER,
    )

    public fun user(dto: UserDto): User = User(
        id = dto.id,
        username = dto.username ?: dto.usernameDisplay ?: dto.displayName,
        displayName = dto.displayName.takeIf { it.isNotBlank() },
        role = UserRole.fromServerToken(dto.role),
    )

    private fun heldNotice(dto: DownloadTaskDto): String? =
        if (dto.heldForReview) "Held for review on the server" else null
}
