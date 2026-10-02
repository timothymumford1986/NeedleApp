package app.needler.feature.player.fake

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey

/**
 * The design pack's own placeholder music, typed in.
 *
 * Screens 07, 08 and 09 are all drawn around the same crate - Sienna, Hamptons and Paranoia from The
 * Marias' Submarine, two from Khruangbin's Mordechai, one each from Alvvays and Beach House - so the
 * screenshots here render exactly what the pack renders and the two can be put side by side.
 *
 * Durations are chosen to add up: Up next comes to 21 minutes, which is what screen 08 prints.
 */
object PlayerFixtures {

    fun track(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mbid: String,
        trackNumber: Int,
        format: AudioFormat = AudioFormat.FLAC,
        bitrateKbps: Int? = null,
        artwork: ArtworkRef? = null,
    ): Track = Track(
        key = TrackKey(
            releaseGroupMbid = ReleaseGroupMbid(mbid),
            discNumber = 1,
            trackNumber = trackNumber,
        ),
        title = title,
        artistName = artist,
        albumTitle = album,
        durationMs = durationMs,
        fetch = TrackFetchHandle(
            fileId = FileId(mbid + "-" + trackNumber),
            sizeBytes = null,
            durationMs = durationMs,
            format = format,
            bitrateKbps = bitrateKbps,
        ),
        // Null by default: `:feature:player` cannot resolve an ArtworkRef to a URL - that needs the
        // server address and the session, which live in :core:data - so a render shows the
        // placeholder, which is what the screen shows before a cover arrives anyway. Pass a ref to
        // render the case where the album *has* a cover the renderer cannot fetch; see [illinois].
        artwork = artwork,
    )

    private const val SUBMARINE = "marias-submarine"
    private const val MORDECHAI = "khruangbin-mordechai"
    private const val BLUE_REV = "alvvays-blue-rev"
    private const val MELODY = "beach-house-melody"

    val sienna: Track = track("Sienna", "The Marias", "Submarine", 200_000L, SUBMARINE, 1)
    val hamptons: Track = track("Hamptons", "The Marias", "Submarine", 205_000L, SUBMARINE, 2)
    val paranoia: Track = track("Paranoia", "The Marias", "Submarine", 190_000L, SUBMARINE, 3)
    val timeYouAndI: Track =
        track("Time (You and I)", "Khruangbin", "Mordechai", 325_000L, MORDECHAI, 4)
    val pelota: Track = track("Pelota", "Khruangbin", "Mordechai", 175_000L, MORDECHAI, 2)
    val afterTheEarthquake: Track = track(
        title = "After the Earthquake",
        artist = "Alvvays",
        album = "Blue Rev",
        durationMs = 190_000L,
        mbid = BLUE_REV,
        trackNumber = 3,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )
    val superstar: Track =
        track("Superstar", "Beach House", "Once Twice Melody", 175_000L, MELODY, 7)

    private const val ILLINOIS = "sufjan-illinois"

    /**
     * A record the reference library has no cover art for at all.
     *
     * Named rather than reusing the pack's crate because the absence is the point. Every fixture
     * above happens to carry no [ArtworkRef] - a JVM render cannot resolve one anyway - so the
     * player's own fallback was in every committed screenshot and nobody read it as wrong: a flat
     * tinted square looks exactly like a cover that has not arrived. Illinois is one of the two
     * albums the device audit found, and a fixture that says so is what makes the next person
     * looking at the render know which of those two things they are looking at.
     *
     * Its title also carries the letter the placeholder draws, `I`, which the tests assert on.
     */
    val illinois: Track = track(
        title = "Chicago",
        artist = "Sufjan Stevens",
        album = "Illinois",
        durationMs = 366_000L,
        mbid = ILLINOIS,
        trackNumber = 9,
    )

    /**
     * The same record as one the server *does* hold a cover for, which this renderer cannot fetch.
     *
     * The pair is the regression test for the fix: a coverless album and an album whose cover is
     * still in flight have to draw the same thing from the player's point of view, because nothing
     * at that layer can tell them apart. [illinois] alone would pass against a branch on
     * `artwork == null`, which is exactly the branch that shipped the defect.
     */
    val illinoisWithCover: Track = illinois.copy(artwork = ArtworkRef.Owned("al-illinois"))

    /** A queue row. The id is queue-local, not a track identity: the same track may appear twice. */
    fun item(id: String, track: Track): QueueItem = QueueItem(id = id, track = track)

    /** The MBID the player resolves Submarine's artist to, and therefore what the byline links to. */
    val mariasMbid: ArtistMbid = ArtistMbid("f4a31f0a-0000-4000-8000-000000000001")

    /**
     * Submarine as the mirror holds it.
     *
     * The player has no artist MBID on a `Track` - only a name - so the byline's destination is resolved
     * through the album. This is the row that resolution reads.
     */
    val submarine: Album = Album(
        releaseGroupMbid = ReleaseGroupMbid(SUBMARINE),
        title = "Submarine",
        artistName = "The Marias",
        artistMbid = mariasMbid,
        state = AlbumState.Owned,
    )

    /** The same album as a server that never matched the artist to MusicBrainz reports it. */
    val submarineWithoutArtistMbid: Album = submarine.copy(artistMbid = null)

    /** The crate exactly as screen 08 draws it: Sienna playing, six up next. */
    val crate: PlayQueue = PlayQueue(
        items = listOf(
            item("q1", sienna),
            item("q2", hamptons),
            item("q3", paranoia),
            item("q4", timeYouAndI),
            item("q5", pelota),
            item("q6", afterTheEarthquake),
            item("q7", superstar),
        ),
        currentIndex = 0,
    )

    /** The Playing row of [crate]. */
    val playingItem: QueueItem = crate.items.first()

    /**
     * One record from end to end, which is the commonest way a crate is actually filled.
     *
     * The device audit found an eleven-track crate of one album printing the same artist, the same album
     * and the same thumbnail on every row. [crate] above cannot catch that - it is the pack's own mixed
     * queue, drawn from four records - so a one-record crate is its own fixture, and the pack's does not
     * move.
     */
    val albumCrate: PlayQueue = PlayQueue(
        items = listOf(
            item("a1", sienna),
            item("a2", hamptons),
            item("a3", paranoia),
            item("a4", track("Hush", "The Marias", "Submarine", 170_000L, SUBMARINE, 4)),
        ),
        currentIndex = 0,
    )

    /** The same record pressed across two discs, where "Track 1" would otherwise appear twice. */
    val doubleAlbumCrate: PlayQueue = PlayQueue(
        items = listOf(
            item("d1", sienna),
            item("d2", onDiscTwo(hamptons, trackNumber = 1)),
        ),
        currentIndex = 0,
    )

    private fun onDiscTwo(from: Track, trackNumber: Int): Track =
        from.copy(key = from.key.copy(discNumber = 2, trackNumber = trackNumber))

    val livingRoomSpeaker: OutputTarget = OutputTarget.Bluetooth(
        id = "bt-living-room",
        displayName = "Living room speaker",
        isConnected = true,
    )

    val pixelBuds: OutputTarget = OutputTarget.Bluetooth(
        id = "bt-pixel-buds",
        displayName = "Pixel Buds",
        isConnected = false,
    )

    val thisPhone: OutputTarget = OutputTarget.ThisDevice(displayName = "This phone")

    val thisTablet: OutputTarget = OutputTarget.ThisDevice(displayName = "This tablet")

    /** A Cast receiver that can fetch from the server. */
    val kitchen: OutputTarget = OutputTarget.Cast(
        id = "cast-kitchen",
        displayName = "Kitchen",
        availability = CastAvailability.REACHABLE,
    )

    /** One of the three setups REQUIREMENTS.md says defeats Cast, so the picker has to explain it. */
    fun unreachableCast(availability: CastAvailability): OutputTarget = OutputTarget.Cast(
        id = "cast-study",
        displayName = "Study speaker",
        availability = availability,
    )
}
