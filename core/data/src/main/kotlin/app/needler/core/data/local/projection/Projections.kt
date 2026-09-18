package app.needler.core.data.local.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinSourceDb
import app.needler.core.data.local.entity.PullStatusDb

/*
 * Read-only query projections.
 *
 * Every one of these exists so a screen can be rendered from a single query. Nothing here is a
 * domain model: the entity -> domain mappers are a later wave and deliberately absent from this
 * module. Column names are matched to the aliases in the DAO queries exactly; changing one means
 * changing both.
 */

/**
 * Cache usage split by tier, as screen 12 requires: "Show real usage ... split into pinned and
 * cached so the user can see what a cleanup would actually free."
 *
 * One query rather than two, so the two halves can never be read from different snapshots.
 */
public data class CacheUsageRow(
    @ColumnInfo(name = "pinned_bytes") val pinnedBytes: Long,
    @ColumnInfo(name = "unpinned_bytes") val unpinnedBytes: Long,
    @ColumnInfo(name = "pinned_tracks") val pinnedTracks: Int,
    @ColumnInfo(name = "unpinned_tracks") val unpinnedTracks: Int,
)

/**
 * One row of the LRU eviction scan: an unpinned cached track, its bytes and its last play.
 *
 * Ordered by `last_played_at` ascending by the query; the running total that decides how far down
 * the list to cut is computed in Kotlin, because SQLite window functions need 3.25+ and Needler
 * supports API 26, where the bundled SQLite is older than that.
 */
public data class EvictionCandidateRow(
    @Embedded val key: TrackKeyDb,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long?,
)

/**
 * The staleness fingerprint of one cached track: what the server said about the file at the moment
 * its bytes were downloaded.
 *
 * Compared against fresh server metadata by
 * [app.needler.core.data.local.staleness.StalenessChecker]. [pinned] is carried because a stale
 * pinned track must be re-downloaded immediately, while a stale unpinned one is simply dropped.
 */
public data class CachedAudioSignatureRow(
    @Embedded val key: TrackKeyDb,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "complete") val complete: Boolean,
    @ColumnInfo(name = "size_bytes") val sizeOnDiskBytes: Long,
    @ColumnInfo(name = "source_file_id") val sourceFileId: String?,
    @ColumnInfo(name = "source_size_bytes") val sourceSizeBytes: Long?,
    @ColumnInfo(name = "source_duration_ms") val sourceDurationMs: Long?,
    @ColumnInfo(name = "source_format") val sourceFormat: String?,
)

/**
 * A pinned album with its download state and how much of it is actually on the device.
 *
 * `cached_track_count` counts complete `audio_cache` rows, which is the honest answer to "is this
 * playable offline" - `pin.tracks_complete` is the downloader's own bookkeeping and can be ahead of
 * the bytes after a staleness eviction.
 */
public data class PinnedAlbumRow(
    @Embedded val album: AlbumEntity,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
    @ColumnInfo(name = "pin_source") val source: PinSourceDb,
    @ColumnInfo(name = "download_state") val downloadState: DownloadStateDb,
    @ColumnInfo(name = "tracks_complete") val tracksComplete: Int,
    @ColumnInfo(name = "tracks_total") val tracksTotal: Int,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long?,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long?,
    @ColumnInfo(name = "pin_error") val error: String?,
    @ColumnInfo(name = "cached_track_count") val cachedTrackCount: Int,
    @ColumnInfo(name = "cached_bytes") val cachedBytes: Long,
)

/**
 * One downloaded album as the Storage screen lists it: identity, what to draw, and the bytes a
 * removal would free.
 *
 * Deliberately narrower than [PinnedAlbumRow], which carries the whole album entity and the
 * downloader's progress counters. This row exists for a list whose job is "what is taking up room,
 * largest first", so it carries only what that list renders and what a removal needs - and
 * [sizeBytes] is measured from `audio_cache`, not from the album's server-reported size, because the
 * user is being shown what they would actually get back.
 */
public data class DownloadedAlbumRow(
    @ColumnInfo(name = "release_group_mbid") val releaseGroupMbid: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long,
)

/**
 * An active pull joined to whatever the mirror knows about the album, so the Pulls screen and the
 * pull widget render from one query even for an album that is not owned yet.
 */
public data class PullRow(
    @ColumnInfo(name = "release_group_mbid") val releaseGroupMbid: String,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "status") val status: PullStatusDb,
    @ColumnInfo(name = "percent") val percent: Int,
    @ColumnInfo(name = "files_done") val filesDone: Int,
    @ColumnInfo(name = "files_total") val filesTotal: Int,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long?,
    @ColumnInfo(name = "total_size_bytes") val totalSizeBytes: Long?,
    @ColumnInfo(name = "source") val source: String?,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "search_job_id") val searchJobId: String?,
    @ColumnInfo(name = "candidate_index") val candidateIndex: Int?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "album_artist_name") val albumArtistName: String?,
    @ColumnInfo(name = "album_year") val albumYear: Int?,
    @ColumnInfo(name = "album_cover_art_id") val albumCoverArtId: String?,
)

/**
 * One entry of a playlist, with the track's display metadata and whether its bytes are on the
 * device.
 *
 * The track fields are nullable because a playlist entry can outlive its album in the mirror: a
 * delta sync that drops an album must leave the user's playlist intact, showing the row as
 * unavailable rather than deleting it.
 */
public data class PlaylistTrackRow(
    @ColumnInfo(name = "position") val position: Int,
    @Embedded val key: TrackKeyDb,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    /** Current fetch handle from the mirror, not the one stored on the playlist row. */
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "format") val format: String?,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int?,
    @ColumnInfo(name = "on_device") val onDevice: Boolean,
)

/**
 * Library totals for the "176 albums - 42 GB" header on screens 02, 09 and 13.
 *
 * This is the offline fallback; `GET /api/v1/library/stats` is authoritative when reachable, and its
 * answer is cached on `sync_state`.
 */
public data class LibraryTotalsRow(
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)

/** An artist row plus its index-jump letter, so the Artists screen needs no second pass. */
public data class ArtistIndexRow(
    @ColumnInfo(name = "artist_mbid") val artistMbid: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_name_normalised") val sortNameNormalised: String,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "art_url") val artUrl: String?,
)

/**
 * Which tracks of an album are on the device, for the album screen's per-row on-device check.
 */
public data class AlbumCacheStateRow(
    @Embedded val key: TrackKeyDb,
    @ColumnInfo(name = "pinned") val pinned: Boolean,
    @ColumnInfo(name = "complete") val complete: Boolean,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
)
