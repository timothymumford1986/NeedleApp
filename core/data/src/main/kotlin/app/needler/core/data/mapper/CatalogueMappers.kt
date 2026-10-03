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
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.RequestOutcome
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.RequestTarget
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.SuggestionKind
import app.needler.core.domain.model.User
import app.needler.core.domain.model.UserRole
import app.needler.core.domain.model.WantedGap
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.model.WantedRetry
import app.needler.core.domain.model.WantedWatch
import app.needler.core.domain.model.WantedWatchState
import app.needler.core.network.v1.dto.ActiveRequestItemDto
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import app.needler.core.network.v1.dto.DownloadTaskDto
import app.needler.core.network.v1.dto.LibraryStatsDto
import app.needler.core.network.v1.dto.ReleaseItemDto
import app.needler.core.network.v1.dto.RequestAcceptedDto
import app.needler.core.network.v1.dto.RequestHistoryDto
import app.needler.core.network.v1.dto.RequestHistoryItemDto
import app.needler.core.network.v1.dto.SearchResultDto
import app.needler.core.network.v1.dto.SuggestResultDto
import app.needler.core.network.v1.dto.UserDto
import app.needler.core.network.v1.dto.WantedRetryingItemDto
import app.needler.core.network.v1.dto.WantedWatchItemDto
import app.needler.core.network.v1.dto.WantedWatchesDto

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

    /**
     * An artist hit. `musicbrainz_id` is the artist MBID when `type` is `artist`.
     *
     * ## Where a catalogue artist's picture comes from, and when there is none
     *
     * There is no artist-image *route*: REQUIREMENTS.md "Endpoints Needler consumes" lists one
     * catalogue artwork endpoint, `GET /api/v1/covers/release-group/{mbid}`, and it is keyed on
     * release groups. The picture instead rides on **this response**: `thumb_url`, `fanart_url` and
     * `banner_url` are fields of the search result itself, so an artist the server does not own can
     * be drawn with a real photograph at no extra request at all. First non-null wins, thumbnail
     * first, because this is a 44dp avatar and a fanart backdrop scaled into one is a waste of the
     * bytes it costs.
     *
     * When all three are null there is no photograph to be had, and `NeedlerArtwork`'s letter over a
     * tint derived from the MBID is the answer. Observed on a device: a search for `dido` drew the
     * owned Dido with a photograph - hers is an [ArtworkRef.Owned] through Subsonic `getCoverArt`,
     * which only exists because the server holds her - and Dido Rowley, Dido Wilson and Dido Brown
     * with placeholders, because upstream has no image for any of the three. That is the honest
     * answer rather than a gap.
     *
     * **Rejected: the artist's best-known release-group cover as a stand-in portrait.** It would fill
     * every one of those placeholders, and it would put an album sleeve where a face belongs - on a
     * round avatar, in a row captioned with a person's name, which reads as a photograph of them. An
     * artist with one record would be represented by its sleeve for ever, and the same sleeve would
     * then appear twice on one screen, once as the artist and once as the album. A placeholder that
     * is obviously a placeholder is worth more than an image that is wrong about what it depicts.
     */
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
     *
     * `request_kind` and `recording_mbid` come off `download_type` and `recording_mbid`, which this
     * lane reports and which are the only way a later cancel can name the right thing: `DELETE
     * /api/v1/requests/active/{mbid}` takes the recording MBID for a track request, while this row
     * is keyed on the release group. See [PullEntity.requestKind].
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
            requestKind = requestKind(dto.downloadType),
            recordingMbid = dto.recordingMbid?.trim()?.takeIf { it.isNotEmpty() },
            requestedByThisDevice = requestedByThisDevice,
            createdAt = WireTime.toEpochMillis(WireTime.fromEpochSeconds(dto.createdAt)) ?: now,
            updatedAt = WireTime.toEpochMillis(WireTime.fromEpochSeconds(dto.updatedAt)) ?: now,
        )
    }

    /**
     * A `request_kind` or `download_type` token as the column stores it.
     *
     * Only `track` is recognised; everything else, including a blank and an absent field, is an
     * album. That is the same rule [app.needler.core.domain.model.RequestTarget.fromServerToken]
     * applies on the way out, and it fails in the safe direction: an unknown kind read as an album
     * cancels the release group, which is the id the row is keyed on and the one the user named.
     */
    private fun requestKind(token: String?): String =
        if (token?.trim().equals(PullEntity.REQUEST_KIND_TRACK, ignoreCase = true)) {
            PullEntity.REQUEST_KIND_TRACK
        } else {
            PullEntity.REQUEST_KIND_ALBUM
        }

    /**
     * Whether a `status` token says an administrator has to act.
     *
     * The same vocabulary [app.needler.core.domain.model.RequestStatus.fromServerToken] matches, kept
     * here as the one test this module applies on the way *in*: a token this list does not hold is
     * some other state of the server's, and deriving "waiting for an administrator" from it would be
     * the inference REQUIREMENTS.md "Placing a request" forbids. `pending` is deliberately absent -
     * it is the server's word for a request it has and has not finished with, which is every request
     * for its first moments.
     */
    private fun isApprovalToken(token: String?): Boolean =
        when (token?.trim()?.lowercase()) {
            "awaiting_approval", "awaiting-approval", "awaiting approval",
            "pending_approval", "needs_approval",
            -> true

            else -> false
        }

    /**
     * An active request, from `GET /api/v1/requests/active`: the lane that carries the approvals.
     *
     * These carry no task id - there is no download yet - so an approval is stored with
     * [PullStatusDb.PENDING_APPROVAL] and renders with the waiting-for-approval state made explicit,
     * which REQUIREMENTS.md "Queue screen requirements" item 6 asks for and which is the only way
     * such a pull is distinguishable from one making no progress.
     *
     * ## Only an approval token is an approval
     *
     * This lane returns every active request, not only the parked ones, and the server distinguishes
     * `pending` from `awaiting_approval`: the first says it has the request, the second says a person
     * has to act. Both used to be read as [PullStatusDb.PENDING_APPROVAL], so a request the server
     * had merely not started yet was badged "Waiting" and explained as waiting for an administrator -
     * a claim about someone else's system that the user cannot check. `pending` now derives like any
     * other status, which for a request with no search job is "Searching", and the administrator is
     * named only when the server named one.
     *
     * The second half of the same rule is that the approval test is skipped entirely once the server
     * has reported a download state. `download_status` and `download_state` describe a task, and a
     * task exists because the request is past approval; a row that said `pending` beside
     * `download_status: downloading` used to store as waiting for an approval that had plainly
     * already happened.
     *
     * ## `musicbrainz_id` means two different things on this lane
     *
     * On an album row it is the release group. On a **track** row it is the *recording*, and the
     * release group arrives separately as `track_release_group_mbid` - which is why the key below
     * prefers it. That makes this the one place both ids are in hand at once, so it is where the
     * recording MBID is captured: `DELETE /api/v1/requests/active/{mbid}` wants it back for a
     * `request_kind=track` cancel, and nothing downstream could reconstruct it from a row keyed on
     * the release group. A track row with no release group at all falls back to the recording as its
     * key, in which case the two columns hold the same value and the cancel still names the
     * recording, which is the id that endpoint takes.
     */
    public fun pendingApprovalEntity(dto: ActiveRequestItemDto, now: Long): PullEntity? {
        val mbid: String = (dto.trackReleaseGroupMbid ?: dto.musicbrainzId).trim()
        if (mbid.isEmpty()) return null
        val kind: String = requestKind(dto.requestKind)
        val recording: String? = dto.musicbrainzId.trim()
            .takeIf { it.isNotEmpty() && kind == PullEntity.REQUEST_KIND_TRACK }
        val taskStatus: String? = dto.downloadStatus?.trim()?.takeIf { it.isNotEmpty() }
            ?: dto.downloadState?.trim()?.takeIf { it.isNotEmpty() }
        val awaiting: Boolean = taskStatus == null && isApprovalToken(dto.status)
        val status: PullStatus = PullStatus.fromServerToken(taskStatus ?: dto.status)
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
            requestKind = kind,
            recordingMbid = recording,
            requestedByThisDevice = true,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    /**
     * The title and artist hints an active-request row carries, for a mirror row that has none.
     *
     * [qualityPolicySummary] is among them because `quality_snapshot_summary` is reported by the
     * downloads lane as well as by the request receipt, and REQUIREMENTS.md "Design pack
     * discrepancies" settles that the figure is shown on the album and on its pull. A pull for an
     * album this device never requested has no receipt to have carried it, so the task's own copy is
     * the only one there will ever be.
     */
    public fun placeholderAlbumEntity(
        releaseGroupMbid: String,
        title: String,
        artistName: String,
        artistMbid: String?,
        year: Int?,
        now: Long,
        qualityPolicySummary: String? = null,
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
        qualityPolicySummary = qualityPolicySummary,
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
     *
     * ## Only an explicit `false` is a rejection
     *
     * `POST /api/v1/requests/new` answers **202**, and 202 means the server has accepted the
     * request. REQUIREMENTS.md "Placing a request" puts the in-band refusal — 200 with
     * `success=false` — on cancel and retry of a *request* and nowhere else, and warns in the same
     * breath that a client reading an acceptance as a failure "reports every successful pull as an
     * error". `dto.success` is therefore consulted only when the server actually said `false`: an
     * absent or null flag on an accepted 202 leaves [RequestAcceptedDto.status] as the answer, which
     * is the field the requirement says to render. See [RequestAcceptedDto.success] for why absent
     * and `false` used to be the same value here, and what that cost.
     */
    public fun receipt(dto: RequestAcceptedDto): RequestReceipt = RequestReceipt(
        releaseGroupMbid = dto.musicbrainzId.trim().takeIf { it.isNotEmpty() }?.let { ReleaseGroupMbid(it) },
        status = if (dto.success == false) RequestStatus.REJECTED else RequestStatus.fromServerToken(dto.status),
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

    // --------------------------------------------------- history and the wanted list

    /**
     * One entry of `GET /api/v1/requests/history`.
     *
     * Three details of this lane are not shared with the one next to it.
     *
     * 1. **The timestamps are ISO-8601 strings.** `requested_at`, `completed_at` and `reviewed_at`
     *    all go through [WireTime.fromIso]; the wanted list, in the same `/api/v1/requests`
     *    namespace, sends epoch seconds as floats and goes through [WireTime.fromEpochSeconds]
     *    instead. Neither convention can be inferred from the path.
     * 2. **The status is kept raw as well as mapped.** REQUIREMENTS.md, "Placing a request", requires
     *    the server's own status to be rendered rather than inferred, so an unmodelled token is
     *    carried through to the screen rather than rounded to the nearest member.
     * 3. **The key is the release group, even for a track request.** A track request reports
     *    `track_release_group_mbid`; that is the join key REQUIREMENTS.md "Identity model" mandates,
     *    and `musicbrainz_id` on such a row is the recording. [pendingApprovalEntity] resolves it the
     *    same way for the same reason.
     *
     * Titles are trimmed and a blank one is kept as blank rather than turned into a placeholder here:
     * the domain's contract is that a title may be empty and that every *render* site guards on it
     * (`PullsFormat.albumTitle`). Inventing "Untitled album" in a mapper would put an untranslatable
     * English string into the data layer and make a real title of a missing one.
     */
    public fun requestHistoryEntry(dto: RequestHistoryItemDto): RequestHistoryEntry? {
        val mbid: String = (dto.trackReleaseGroupMbid ?: dto.musicbrainzId).trim()
        if (mbid.isEmpty()) return null
        return RequestHistoryEntry(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            albumTitle = dto.albumTitle?.trim().orEmpty(),
            artistName = dto.artistName?.trim().orEmpty(),
            status = RequestOutcome.fromServerToken(dto.status),
            statusToken = dto.status.trim(),
            target = RequestTarget.fromServerToken(dto.requestKind),
            trackTitle = dto.trackTitle?.trim()?.takeIf { it.isNotEmpty() },
            requestedAt = WireTime.fromIso(dto.requestedAt),
            completedAt = WireTime.fromIso(dto.completedAt),
            inLibrary = dto.inLibrary,
            reviewedByName = dto.reviewedByName?.trim()?.takeIf { it.isNotEmpty() },
            reviewedAt = WireTime.fromIso(dto.reviewedAt),
            year = dto.year,
        )
    }

    /**
     * A page of request history, with the totals the endpoint actually reports.
     *
     * `page`, `page_size`, `total` and `total_pages` are all echoed rather than recomputed, because
     * this is the one list in the lane where the server knows them: `GET /api/v1/downloads` reports
     * no total at all and its paging is blind. The one thing not taken on trust is `page_size` - a
     * zero there would make [RequestHistoryPage.hasMore] undecidable, so the number of items
     * returned stands in for it.
     */
    public fun requestHistoryPage(dto: RequestHistoryDto): RequestHistoryPage {
        val entries: List<RequestHistoryEntry> = dto.items.mapNotNull { requestHistoryEntry(it) }
        return RequestHistoryPage(
            entries = entries,
            page = dto.page.coerceAtLeast(1),
            pageSize = if (dto.pageSize > 0) dto.pageSize else dto.items.size,
            total = dto.total.coerceAtLeast(0),
            totalPages = dto.totalPages.coerceAtLeast(0),
        )
    }

    /**
     * One standing watch from `GET /api/v1/requests/wanted`.
     *
     * Every `*_at` on this endpoint is **epoch seconds as a float**, which is the `/downloads`
     * convention rather than the one its `/requests` neighbours use. That is the whole reason this
     * mapper and [requestHistoryEntry] cannot share a timestamp helper.
     */
    public fun wantedWatch(dto: WantedWatchItemDto): WantedWatch? {
        val mbid: String = dto.releaseGroupMbid.trim()
        if (mbid.isEmpty()) return null
        return WantedWatch(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            albumTitle = dto.albumTitle?.trim().orEmpty(),
            artistName = dto.artistName?.trim().orEmpty(),
            gap = WantedGap.fromServerToken(dto.kind),
            state = WantedWatchState.fromServerToken(dto.state),
            stateToken = dto.state.trim(),
            checkCount = dto.checkCount.coerceAtLeast(0),
            newCandidateCount = dto.newCandidateCount.coerceAtLeast(0),
            lastCheckedAt = WireTime.fromEpochSeconds(dto.lastCheckedAt),
            nextCheckAt = WireTime.fromEpochSeconds(dto.nextCheckAt),
            lastOutcome = dto.lastOutcome?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = WireTime.fromEpochSeconds(dto.createdAt),
            year = dto.year ?: dto.firstReleaseDate?.take(4)?.toIntOrNull(),
        )
    }

    /** One actively retrying item. `next_retry_at` is epoch seconds, like the rest of this lane. */
    public fun wantedRetry(dto: WantedRetryingItemDto): WantedRetry? {
        val mbid: String = dto.releaseGroupMbid.trim()
        if (mbid.isEmpty()) return null
        return WantedRetry(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            albumTitle = dto.albumTitle?.trim().orEmpty(),
            artistName = dto.artistName?.trim().orEmpty(),
            retryCount = dto.retryCount.coerceAtLeast(0),
            maxAttempts = dto.maxAttempts.coerceAtLeast(0),
            nextRetryAt = WireTime.fromEpochSeconds(dto.nextRetryAt),
            year = dto.year,
        )
    }

    /**
     * The whole wanted list.
     *
     * `count` on the response is deliberately ignored: it counts `items` only and not `retrying`, so
     * a caller that trusted it would under-report the list it is about to draw. The sizes of the two
     * lists are the only totals this endpoint can be held to.
     */
    public fun wantedList(dto: WantedWatchesDto): WantedList = WantedList(
        watches = dto.items.mapNotNull { wantedWatch(it) },
        retrying = dto.retrying.mapNotNull { wantedRetry(it) },
    )

    private fun heldNotice(dto: DownloadTaskDto): String? =
        if (dto.heldForReview) "Held for review on the server" else null
}
