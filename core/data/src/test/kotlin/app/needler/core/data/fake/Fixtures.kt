package app.needler.core.data.fake

import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PinSourceDb
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.ArtistFileDto
import app.needler.core.network.subsonic.dto.ArtistId3Dto
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.FileIndexDto
import app.needler.core.network.subsonic.dto.IndexesDto

/** Row and DTO builders, so a test says only what it is actually about. */

public const val RG: String = "d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"
public const val ARTIST_MBID: String = "aa11bb22-cc33-dd44-ee55-ff6677889900"

public fun albumRow(
    mbid: String = RG,
    title: String = "Spiderland",
    artist: String = "Slint",
    artistMbid: String? = ARTIST_MBID,
    state: AlbumStateDb = AlbumStateDb.OWNED,
    year: Int? = 1991,
    genres: String? = null,
    addedAt: Long? = 1_000L,
    sizeBytes: Long? = 400_000_000L,
    updatedAt: Long = 0L,
): AlbumEntity = AlbumEntity(
    releaseGroupMbid = mbid,
    artistMbid = artistMbid,
    artistName = artist,
    title = title,
    titleNormalised = SortKeys.normalise(title),
    artistNormalised = SortKeys.normalise(artist),
    year = year,
    trackCount = 6,
    discCount = 1,
    durationMs = 2_400_000L,
    format = "flac",
    bitrateKbps = null,
    state = state,
    inLibrary = state.isInLibrary,
    addedAt = addedAt,
    sizeBytes = sizeBytes,
    coverArtId = "al-" + mbid,
    genres = genres,
    qualityPolicySummary = null,
    updatedAt = updatedAt,
)

/**
 * A track row.
 *
 * [artist] is a parameter and nullable because the Songs tab has two things to say about it: it
 * orders by the *album's* artist, and it falls back to that artist when the server sent none for the
 * track - which is the normal case, since DroppedNeedle only reports a per-track artist where it
 * differs from the album's. A fixture that always carried one could not exercise either.
 */
public fun trackRow(
    mbid: String = RG,
    disc: Int = 1,
    track: Int = 1,
    title: String = "Breadcrumb Trail",
    artist: String? = "Slint",
    fileId: String? = "8801",
    sizeBytes: Long? = 40_000_000L,
    durationMs: Long? = 353_000L,
    format: String? = "flac",
    bitrateKbps: Int? = null,
    updatedAt: Long = 0L,
): TrackEntity = TrackEntity(
    releaseGroupMbid = mbid,
    discNo = disc,
    trackNo = track,
    title = title,
    titleNormalised = SortKeys.normalise(title),
    artistName = artist,
    durationMs = durationMs,
    recordingMbid = null,
    fileId = fileId,
    sizeBytes = sizeBytes,
    format = format,
    bitrateKbps = bitrateKbps,
    updatedAt = updatedAt,
)

public fun cacheRow(
    mbid: String = RG,
    disc: Int = 1,
    track: Int = 1,
    path: String = "/data/audio/1.audio",
    sizeBytes: Long = 40_000_000L,
    complete: Boolean = true,
    pinned: Boolean = false,
    lastPlayedAt: Long = 0L,
    sourceFileId: String? = "8801",
    sourceSizeBytes: Long? = 40_000_000L,
    sourceDurationMs: Long? = 353_000L,
    sourceFormat: String? = "flac",
    sourceBitrateKbps: Int? = null,
): AudioCacheEntity = AudioCacheEntity(
    releaseGroupMbid = mbid,
    discNo = disc,
    trackNo = track,
    recordingMbid = null,
    filePath = path,
    sizeBytes = sizeBytes,
    complete = complete,
    pinned = pinned,
    lastPlayedAt = lastPlayedAt,
    playCount = 0,
    downloadedAt = 1_000L,
    sourceFileId = sourceFileId,
    sourceSizeBytes = sourceSizeBytes,
    sourceDurationMs = sourceDurationMs,
    sourceFormat = sourceFormat,
    sourceBitrateKbps = sourceBitrateKbps,
)

public fun pinRow(
    mbid: String = RG,
    state: DownloadStateDb = DownloadStateDb.COMPLETE,
    tracksComplete: Int = 6,
    tracksTotal: Int = 6,
): PinEntity = PinEntity(
    releaseGroupMbid = mbid,
    pinnedAt = 500L,
    source = PinSourceDb.MANUAL,
    downloadState = state,
    tracksComplete = tracksComplete,
    tracksTotal = tracksTotal,
    downloadedBytes = null,
    totalBytes = null,
    error = null,
    updatedAt = 500L,
)

public fun pullRow(
    mbid: String = RG,
    taskId: String? = "task-1",
    status: PullStatusDb = PullStatusDb.DOWNLOADING,
    percent: Int = 40,
    searchJobId: String? = "job-1",
    candidateIndex: Int? = 0,
    createdAt: Long = 100L,
): PullEntity = PullEntity(
    releaseGroupMbid = mbid,
    taskId = taskId,
    status = status,
    percent = percent,
    filesDone = 2,
    filesTotal = 6,
    downloadedBytes = 10_000L,
    totalSizeBytes = 100_000L,
    source = "soulseek",
    error = null,
    searchJobId = searchJobId,
    candidateIndex = candidateIndex,
    requestedByThisDevice = true,
    createdAt = createdAt,
    updatedAt = createdAt,
)

public fun artistRow(
    mbid: String = ARTIST_MBID,
    name: String = "Slint",
    monitored: Boolean = false,
): ArtistEntity = ArtistEntity(
    artistMbid = mbid,
    name = name,
    sortName = name,
    sortNameNormalised = SortKeys.normalise(name),
    albumCount = 2,
    artUrl = null,
    monitored = monitored,
    updatedAt = 0L,
)

// -------------------------------------------------------------------------- DTOs

public fun songDto(
    fileId: String = "8801",
    track: Int? = 1,
    disc: Int? = 1,
    title: String = "Breadcrumb Trail",
    size: Long? = 40_000_000L,
    durationSeconds: Int? = 353,
    suffix: String? = "flac",
    bitRate: Int? = null,
    albumId: String? = "al-" + RG,
): ChildDto = ChildDto(
    id = "tr-" + fileId,
    title = title,
    track = track,
    discNumber = disc,
    size = size,
    duration = durationSeconds,
    suffix = suffix,
    bitRate = bitRate,
    albumId = albumId,
    artist = "Slint",
)

public fun albumDto(
    mbid: String = RG,
    name: String = "Spiderland",
    songs: List<ChildDto> = listOf(songDto()),
    artistId: String? = "ar-" + ARTIST_MBID,
): AlbumId3Dto = AlbumId3Dto(
    id = "al-" + mbid,
    name = name,
    artist = "Slint",
    artistId = artistId,
    coverArt = "al-" + mbid,
    songCount = songs.size,
    duration = 2_400,
    created = "1991-03-27T00:00:00Z",
    year = 1991,
    musicBrainzId = mbid,
    song = songs,
)

public fun artistDto(
    mbid: String = ARTIST_MBID,
    name: String = "Slint",
    albums: List<AlbumId3Dto> = listOf(albumDto()),
): ArtistId3Dto = ArtistId3Dto(
    id = "ar-" + mbid,
    name = name,
    albumCount = albums.size,
    musicBrainzId = mbid,
    album = albums,
)

public fun indexesDto(
    lastModified: Long,
    artistIds: List<String> = emptyList(),
): IndexesDto = IndexesDto(
    lastModified = lastModified,
    index = if (artistIds.isEmpty()) {
        emptyList()
    } else {
        listOf(
            FileIndexDto(
                name = "S",
                artist = artistIds.map { ArtistFileDto(id = it, name = "Slint") },
            ),
        )
    },
)
