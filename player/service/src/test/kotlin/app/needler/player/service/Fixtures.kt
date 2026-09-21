package app.needler.player.service

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey

/**
 * Builders for the domain types these tests need.
 *
 * Kept in one place so a test reads as the rule it is checking rather than as six lines of constructor.
 */
internal object Fixtures {

    const val ALBUM_A: String = "11111111-1111-1111-1111-111111111111"
    const val ALBUM_B: String = "22222222-2222-2222-2222-222222222222"

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
}
