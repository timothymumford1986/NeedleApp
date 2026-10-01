package app.needler.widget

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullStatus
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
 * a test source set is not shared across modules any more than a main one is. Two tracks, two albums
 * and a handful of pulls are enough for everything the three widgets fold and format.
 *
 * The albums and pulls are the pack's own too: `design/html/15-Widget.html` draws `Mordechai` by
 * `Khruangbin` on the recently added card and `Black Classical Music` at 62 percent on the pull card,
 * so a failure below means a widget stopped matching the drawing rather than that a number changed.
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

    /** The album the pack draws on the recently added card: Mordechai, by Khruangbin. */
    val mordechai: Album = album(
        mbid = "khruangbin-mordechai",
        title = "Mordechai",
        artist = "Khruangbin",
    )

    /** A second owned album, for asserting that the card follows the newest rather than any album. */
    val submarine: Album = album(
        mbid = "marias-submarine",
        title = "Submarine",
        artist = "The Marías",
    )

    /**
     * The pull the pack draws: Black Classical Music, downloading, 62 percent.
     *
     * @param percent null for a pull the server has reported no progress for, which is what a pull
     *   still searching for sources looks like.
     */
    fun downloading(
        title: String = "Black Classical Music",
        percent: Int? = 62,
    ): Pull = pull(title = title, status = PullStatus.DOWNLOADING, percent = percent)

    /** A pull the server is still finding sources for: `queued` with no search job, and no percentage. */
    fun searching(title: String = "Bruise"): Pull =
        pull(title = title, status = PullStatus.QUEUED, percent = null, searchJobId = null)

    /** A pull that has finished. In the Completed bucket, and never drawn by this card. */
    fun completed(title: String = "Blue Rev"): Pull =
        pull(title = title, status = PullStatus.COMPLETED, percent = 100)

    /**
     * A pull the server sent no album title for.
     *
     * This fixture exists because its absence is what let the bug ship. Every other fixture here
     * supplies a title, so nothing in the suite could see that a blank one reached a text slot - and on
     * a real device 34 of 35 pulls arrived blank, because the downloads lane's `album_title` had no
     * reader in main source at all. The data path is fixed; this stays, so the render guard cannot be
     * removed without a test failing.
     *
     * Whitespace rather than an empty string on purpose: it is the case where `isEmpty` and `isBlank`
     * disagree, and the one a naive guard misses.
     */
    fun untitled(percent: Int? = 62): Pull =
        pull(title = "   ", status = PullStatus.DOWNLOADING, percent = percent)

    /** A title that arrived with whitespace around it, which must not reach the card as drawn. */
    fun padded(percent: Int? = 62): Pull =
        pull(title = "  Black Classical Music  ", status = PullStatus.DOWNLOADING, percent = percent)

    /** An album the mirror holds with no title, for the same reason [untitled] exists. */
    val untitledRecord: Album = album(
        mbid = "unnamed-release-group",
        title = "  ",
        artist = "Yussef Dayes",
    )

    private fun album(mbid: String, title: String, artist: String): Album = Album(
        releaseGroupMbid = ReleaseGroupMbid(mbid),
        title = title,
        artistName = artist,
        artistMbid = ArtistMbid(mbid + "-artist"),
        // Owned, because every album AlbumListKind.NEWEST can return is one the server holds: a
        // catalogue-only album has no arrival time to order by.
        state = AlbumState.Owned,
        // Null, as in the track fixtures: nothing in a unit test can resolve an ArtworkRef to a URL,
        // and the widget's answer to a cover it cannot fetch is the placeholder tint.
        artwork = null,
    )

    private fun pull(
        title: String,
        status: PullStatus,
        percent: Int?,
        searchJobId: String? = "search-" + title.hashCode().toString(),
    ): Pull {
        // The MBID is derived from the title for readability, but a pull with no title still has one:
        // ReleaseGroupMbid refuses a blank value, and an untitled pull is the case these fixtures exist
        // to cover.
        val trimmed: String = title.trim().lowercase().replace(" ", "-")
        val slug: String = if (trimmed.isEmpty()) "untitled" else trimmed
        return Pull(
            releaseGroupMbid = ReleaseGroupMbid("pull-" + slug),
            albumTitle = title,
            artistName = "Yussef Dayes",
            status = status,
            searchJobId = searchJobId,
            // A chosen candidate is what separates a queued pull from one awaiting a source review, and
            // the pull card names that difference; see PullCardModel.
            candidateIndex = if (searchJobId == null) null else 0,
            progress = PullProgress(percent = percent),
        )
    }

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
