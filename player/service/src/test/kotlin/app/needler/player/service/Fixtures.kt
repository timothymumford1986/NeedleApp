package app.needler.player.service

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlinx.datetime.Instant

/**
 * Builders for the domain types these tests need.
 *
 * Kept in one place so a test reads as the rule it is checking rather than as six lines of constructor.
 */
internal object Fixtures {

    const val ALBUM_A: String = "11111111-1111-1111-1111-111111111111"
    const val ALBUM_B: String = "22222222-2222-2222-2222-222222222222"
    const val ARTIST_A: String = "33333333-3333-3333-3333-333333333333"

    fun key(
        album: String = ALBUM_A,
        disc: Int = 1,
        track: Int = 1,
    ): TrackKey = TrackKey(ReleaseGroupMbid(album), discNumber = disc, trackNumber = track)

    fun handle(
        fileId: String = "file-1",
        sizeBytes: Long? = 8_000_000L,
        durationMs: Long? = 240_000L,
        format: AudioFormat? = AudioFormat.FLAC,
        bitrateKbps: Int? = 1_000,
    ): TrackFetchHandle = TrackFetchHandle(
        fileId = FileId(fileId),
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        format = format,
        bitrateKbps = bitrateKbps,
    )

    fun track(
        album: String = ALBUM_A,
        disc: Int = 1,
        number: Int = 1,
        title: String = "Track " + number,
        durationMs: Long? = 240_000L,
        fetch: TrackFetchHandle = handle(fileId = album + "-" + disc + "-" + number),
    ): Track = Track(
        key = key(album, disc, number),
        title = title,
        artistName = "An Artist",
        albumTitle = "An Album",
        durationMs = durationMs,
        fetch = fetch,
    )

    /** An owned album by default, because everything Android Auto is allowed to show is owned. */
    fun album(
        mbid: String = ALBUM_A,
        title: String = "An Album",
        artistName: String = "An Artist",
        state: AlbumState = AlbumState.Owned,
    ): Album = Album(
        releaseGroupMbid = ReleaseGroupMbid(mbid),
        title = title,
        artistName = artistName,
        artistMbid = ArtistMbid(ARTIST_A),
        state = state,
    )

    fun artist(
        mbid: String = ARTIST_A,
        name: String = "An Artist",
        ownedAlbumCount: Int = 1,
    ): Artist = Artist(mbid = ArtistMbid(mbid), name = name, ownedAlbumCount = ownedAlbumCount)

    fun playlist(
        id: String = "7",
        name: String = "Long drive",
        trackCount: Int = 2,
    ): Playlist = Playlist(id = PlaylistId(id), name = name, trackCount = trackCount)

    fun entry(position: Int, track: Track): PlaylistEntry = PlaylistEntry(position = position, track = track)

    fun download(
        mbid: String = ALBUM_A,
        title: String = "An Album",
        artistName: String = "An Artist",
        sizeBytes: Long = 400_000_000L,
    ): DownloadedAlbum = DownloadedAlbum(
        releaseGroupMbid = ReleaseGroupMbid(mbid),
        title = title,
        artistName = artistName,
        sizeBytes = sizeBytes,
        pinnedAt = Instant.fromEpochMilliseconds(1_000L),
    )
}
