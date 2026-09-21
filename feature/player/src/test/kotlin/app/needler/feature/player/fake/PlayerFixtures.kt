package app.needler.feature.player.fake

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
        // Artwork is deliberately null: `:feature:player` cannot resolve an ArtworkRef to a URL -
        // that needs the server address and the session, which live in :core:data - so a render
        // shows the placeholder tint, which is what the screen shows before a cover arrives anyway.
        artwork = null,
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

    /** A queue row. The id is queue-local, not a track identity: the same track may appear twice. */
    fun item(id: String, track: Track): QueueItem = QueueItem(id = id, track = track)

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
