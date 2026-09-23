package app.needler.widget

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey

/**
 * The track the design pack draws on the widget: Sienna, from The Marías' Submarine, 3:20.
 *
 * `design/html/15-Widget.html` and `design/html/18-TabletWidget.html` both print `Sienna`, `The
 * Marías · Submarine` and `1:16 / 3:20`, so the tests below assert against the pack's own numbers
 * rather than against invented ones - a failure here means the widget stopped matching the drawing.
 *
 * `:feature:player` has a far richer `PlayerFixtures` built on the same record, and this is not it:
 * a test source set is not shared across modules any more than a main one is. Two tracks are enough
 * for everything `:widget` formats.
 */
internal object WidgetFixtures {

    val sienna: QueueItem = queueItem(
        title = "Sienna",
        artist = "The Marías",
        album = "Submarine",
        durationMs = 200_000L,
        mbid = "marias-submarine",
        trackNumber = 1,
    )

    /** A track the server described without an album, which is the case the subtitle has to survive. */
    val untitledAlbum: QueueItem = queueItem(
        title = "Pelota",
        artist = "Khruangbin",
        album = null,
        durationMs = 175_000L,
        mbid = "khruangbin-mordechai",
        trackNumber = 2,
    )

    private fun queueItem(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        mbid: String,
        trackNumber: Int,
    ): QueueItem {
        val releaseGroup = ReleaseGroupMbid(mbid)
        return QueueItem(
            id = releaseGroup.value + "-" + trackNumber,
            track = Track(
                key = TrackKey(
                    releaseGroupMbid = releaseGroup,
                    discNumber = 1,
                    trackNumber = trackNumber,
                ),
                title = title,
                artistName = artist,
                albumTitle = album,
                durationMs = durationMs,
                fetch = TrackFetchHandle(
                    fileId = FileId(releaseGroup.value + "-" + trackNumber),
                    sizeBytes = null,
                    durationMs = durationMs,
                    format = AudioFormat.FLAC,
                    bitrateKbps = null,
                ),
                // Null, as in PlayerFixtures: nothing in a unit test can resolve an ArtworkRef to a
                // URL, and the widget's answer to a cover it cannot fetch is the placeholder tint.
                artwork = null,
            ),
        )
    }
}
