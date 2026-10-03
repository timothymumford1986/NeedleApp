package app.needler.wear

/**
 * The phone's copy of the wire contract with the watch.
 *
 * ## Why this file is a duplicate
 *
 * The canonical version, with the full argument behind every decision, is
 * `wear/src/main/kotlin/app/needler/wear/playback/WearPlaybackProtocol.kt`. **Read that one before
 * changing anything here.** The reasoning is not repeated below; what each constant means, why state
 * is a retained data item while commands are messages, why position is not on the wire, and why the
 * phone publishes only inside a window are all recorded there, once.
 *
 * The duplication is deliberate and there is no way to avoid it in this project's layering.
 * `:core:domain` is the only module both APKs could share, and it is a pure Kotlin/JVM library -
 * `JvmLibraryConventionPlugin` applies the Kotlin JVM plugin precisely so that `android.*` cannot
 * reach it - while these are Android data-layer paths whose only meaning is to Google Play services.
 * `:app` cannot depend on `:wear` either: they are two application modules, two APKs, with separate
 * version codes so they can ship apart.
 *
 * ## What holds the two copies together
 *
 * Nothing at compile time, which is the whole problem. So both sides carry a
 * `WearPlaybackProtocolTest` that asserts the **literal strings**, not the constants. A test that
 * compares a constant to itself passes cheerfully while one copy is renamed and the wire goes quiet;
 * a test that spells `"/needler/now-playing"` out fails on the side that drifted, in the module that
 * drifted, and says which string it expected.
 *
 * A mismatch is silent at runtime, and that is worth being clear about: a watch listening on one path
 * while the phone publishes on another produces no error anywhere. The watch renders
 * `PhoneUnreachable` or "Nothing playing" forever and every button does nothing.
 */
object WearPlaybackProtocol {

    // ---- State: retained DataItems, phone -> watch ----------------------------------------------

    /** Path of the now-playing data item. Everything Needler sends sits under `/needler/`. */
    const val PATH_NOW_PLAYING: String = "/needler/now-playing"

    /** `Boolean` - whether anything is loaded at all. Mirrors `PlaybackState.hasCurrentItem`. */
    const val KEY_HAS_ITEM: String = "hasItem"

    /** `String` - track title. */
    const val KEY_TITLE: String = "title"

    /** `String` - track artist, which on a compilation differs from the album artist. */
    const val KEY_ARTIST: String = "artist"

    /** `String`, optional - album title. */
    const val KEY_ALBUM: String = "album"

    /** `Boolean` - mirrors `PlaybackState.isPlaying`. */
    const val KEY_IS_PLAYING: String = "isPlaying"

    /** `Boolean` - mirrors `PlaybackState.isBuffering`, which is not the opposite of playing. */
    const val KEY_IS_BUFFERING: String = "isBuffering"

    /**
     * `String`, optional - a stable identifier for the artwork in [KEY_ARTWORK].
     *
     * The phone sends the release-group MBID, which is what the canonical file names as the obvious
     * choice: every track on an album shares it, so the watch resolves the asset once per album
     * rather than once per track.
     */
    const val KEY_ARTWORK_ID: String = "artworkId"

    /** `Asset`, optional - a small square cover, fetched out of band by the watch. */
    const val KEY_ARTWORK: String = "artwork"

    /**
     * `Long` - present only to make each publish's bytes differ.
     *
     * The data layer treats a byte-identical put as a no-op and raises no change event, so without
     * this a republish forced after a reconnect would be swallowed. Carried by both items.
     */
    const val KEY_PUBLISHED_AT: String = "publishedAt"

    /** Path of the crate data item - "in the crate" is REQUIREMENTS.md "Vocabulary" for the queue. */
    const val PATH_CRATE: String = "/needler/crate"

    /** `ArrayList<DataMap>` - the published window of the crate, in play order. */
    const val KEY_CRATE_ROWS: String = "rows"

    /** `String` - the row's `QueueItem.id`, which is what [PATH_SKIP_TO_ROW] carries back. */
    const val KEY_ROW_ID: String = "rowId"

    /** `String` - the row's track title. */
    const val KEY_ROW_TITLE: String = "rowTitle"

    /** `String` - the row's track artist. */
    const val KEY_ROW_ARTIST: String = "rowArtist"

    /**
     * `Int` - index into the **published window** of the row that is playing, or [NO_CURRENT_ROW].
     *
     * Window-relative, never crate-relative. See [WearSnapshots.crateWindow].
     */
    const val KEY_CRATE_CURRENT: String = "crateCurrent"

    /** `Int` - how many rows of the crate come before the published window. */
    const val KEY_CRATE_WINDOW_START: String = "crateWindowStart"

    /** `Int` - rows in the whole crate, which may be far more than were published. */
    const val KEY_CRATE_TOTAL: String = "crateTotal"

    /**
     * The value of [KEY_CRATE_CURRENT] when no row is playing.
     *
     * A sentinel rather than an absent key, because `DataMap.getInt` answers 0 for a missing key and
     * 0 is an ordinary row index.
     */
    const val NO_CURRENT_ROW: Int = -1

    /** The most rows the phone puts on the wire, counted from the playing row. */
    const val MAX_CRATE_ROWS: Int = 40

    // ---- Commands: messages, watch -> phone ----------------------------------------------------

    /** Maps to `PlaybackController.playPause()`. One path, because the phone owns the truth. */
    const val PATH_PLAY_PAUSE: String = "/needler/command/play-pause"

    /** Maps to `PlaybackController.skipToNext()`. */
    const val PATH_NEXT: String = "/needler/command/next"

    /** Maps to `PlaybackController.skipToPrevious()`, threshold and all. */
    const val PATH_PREVIOUS: String = "/needler/command/previous"

    /**
     * Maps to `PlaybackController.skipToQueueItem(itemId)`. Payload is the row id in UTF-8.
     *
     * An id rather than an index: the row the watch drew may have moved by the time it is tapped.
     */
    const val PATH_SKIP_TO_ROW: String = "/needler/command/skip-to-row"

    /** Every command path that drives the session. [NeedlerWearListenerService] switches over these. */
    val COMMAND_PATHS: Set<String> = setOf(
        PATH_PLAY_PAUSE,
        PATH_NEXT,
        PATH_PREVIOUS,
        PATH_SKIP_TO_ROW,
    )

    /**
     * "Publish a fresh snapshot now, and keep publishing for the next [STATE_WINDOW_MS]."
     *
     * Deliberately not in [COMMAND_PATHS]: it changes nothing a user can hear and is safe to repeat.
     */
    const val PATH_REQUEST_STATE: String = "/needler/request-state"

    /** How long one [PATH_REQUEST_STATE] keeps the phone observing and publishing. */
    const val STATE_WINDOW_MS: Long = 60_000L

    /**
     * The namespace every path in this contract sits under.
     *
     * Used as a cheap first filter on an inbound message, before the node check: the service is
     * exported for Google Play services to call, and a path outside this prefix is not addressed to
     * this contract whatever sent it.
     */
    const val NAMESPACE: String = "/needler/"

    // ---- On-device audio: the sync contract -----------------------------------------------------
    //
    // The canonical argument for every constant below is in the watch's copy of this file, under "The
    // sync contract" and on each declaration. In particular: why audio is an `Asset` on a data item
    // per track rather than a `ChannelClient` transfer, why the offer is the Downloaded tier and
    // nothing else, why a track item deliberately carries no KEY_PUBLISHED_AT, and what the
    // fingerprint may and may not be used for. Read that before changing anything here.

    /**
     * The namespace every sync path sits under, inside [NAMESPACE].
     *
     * The watch's manifest scopes its one `WearableListenerService` to this family, so a transport
     * snapshot never wakes the watch.
     */
    const val SYNC_NAMESPACE: String = "/needler/sync/"

    /**
     * Path of the offer: the albums the phone can put on the watch.
     *
     * The phone's **Device** tier and nothing else - never the **Temporary** one. That is the
     * whole sync policy; the canonical file records the three reasons.
     */
    const val PATH_SYNC_OFFER: String = "/needler/sync/offer"

    /** `ArrayList<DataMap>` - the offered albums. */
    const val KEY_OFFER_ALBUMS: String = "offerAlbums"

    /** `String` - the album's release-group MBID, bare, with no `al-` prefix. */
    const val KEY_ALBUM_KEY: String = "albumKey"

    /** `String` - album title. */
    const val KEY_ALBUM_TITLE: String = "albumTitle"

    /** `String` - album artist. */
    const val KEY_ALBUM_ARTIST: String = "albumArtist"

    /** `Int` - tracks of this album **downloaded on the phone**, not the album's true length. */
    const val KEY_ALBUM_TRACK_COUNT: String = "albumTrackCount"

    /** `Long` - bytes on the phone's disk for this album, as the cache index accounts for them. */
    const val KEY_ALBUM_BYTES: String = "albumBytes"

    /** `Int` - downloaded albums the phone did not list. */
    const val KEY_OFFER_NOT_SHOWN: String = "offerNotShown"

    /** The most albums the offer carries, most recently downloaded first. */
    const val MAX_OFFER_ALBUMS: Int = 60

    /**
     * Prefix of a track item's path; `TrackKey.canonicalString` follows it.
     *
     * Three parts - release group, disc, track - exactly as `audio_cache` is keyed. Never a `file_id`:
     * REQUIREMENTS.md "Track identity is not stable".
     */
    const val PATH_SYNC_TRACK_PREFIX: String = "/needler/sync/track/"

    /** `String` - the track key, repeated inside the map so the watch never parses a path. */
    const val KEY_TRACK_KEY: String = "trackKey"

    /** `String` - track title. */
    const val KEY_TRACK_TITLE: String = "trackTitle"

    /** `String` - track artist. */
    const val KEY_TRACK_ARTIST: String = "trackArtist"

    /** `String` - album title. */
    const val KEY_TRACK_ALBUM: String = "trackAlbum"

    /** `Long` - duration in milliseconds, or 0 when the server never said. */
    const val KEY_TRACK_DURATION_MS: String = "trackDurationMs"

    /** `String` - the `AudioFormat` name. A badge and a file extension, never a decoder hint. */
    const val KEY_TRACK_FORMAT: String = "trackFormat"

    /** `Long` - the audio's length in bytes. */
    const val KEY_TRACK_BYTES: String = "trackBytes"

    /**
     * `String` - the opaque staleness token these bytes were fetched with.
     *
     * Built and compared only here, by [WearSyncFingerprint]. The comparison is **not** string
     * equality: REQUIREMENTS.md "Invalidating upgraded files" requires that "a field counts as changed
     * only when both sides carry a value", or a server that stopped reporting durations would
     * re-transfer the user's whole watch library over Bluetooth.
     */
    const val KEY_TRACK_FINGERPRINT: String = "trackFingerprint"

    /** Separator between the fields of [KEY_TRACK_FINGERPRINT]. */
    const val FINGERPRINT_SEPARATOR: String = "|"

    /** A [KEY_TRACK_FINGERPRINT] field the server did not report. Means unknown, never "changed". */
    const val FINGERPRINT_ABSENT: String = "-"

    /** `Asset` - the audio itself, in the server's original format. Never a transcode. */
    const val KEY_TRACK_AUDIO: String = "trackAudio"

    /** The largest track the phone will put on the wire: 48 MB. */
    const val MAX_TRACK_BYTES: Long = 48L * 1024L * 1024L

    /** How many track items the phone keeps published at once. */
    const val MAX_TRACKS_IN_FLIGHT: Int = 2

    /** Prefix of an album cover item's path; the release-group MBID follows it. */
    const val PATH_SYNC_COVER_PREFIX: String = "/needler/sync/cover/"

    /** `String` - the release-group MBID this cover belongs to. */
    const val KEY_COVER_ALBUM_KEY: String = "coverAlbumKey"

    /** `Asset` - the cover bitmap, at the same 200 px the transport's artwork uses. */
    const val KEY_COVER_IMAGE: String = "coverImage"

    /**
     * Path of the watch's selection. The one item on this wire published by the **watch**.
     *
     * Retained so the phone can read it when the phone wakes, rather than only while the watch is awake.
     */
    const val PATH_SYNC_SELECTION: String = "/needler/sync/selection"

    /** `ArrayList<String>` - release-group MBIDs the user wants on the watch, in priority order. */
    const val KEY_WANTED_ALBUMS: String = "wantedAlbums"

    /** `String` - the album the watch wants bytes for now, or empty for none. */
    const val KEY_ACTIVE_ALBUM: String = "activeAlbum"

    /**
     * `ArrayList<String>` - what the watch holds for [KEY_ACTIVE_ALBUM]: the track key, then
     * [HELD_TRACK_SEPARATOR], then the [KEY_TRACK_FINGERPRINT] it arrived with. Complete tracks only.
     */
    const val KEY_HELD_TRACKS: String = "heldTracks"

    /** Separates the track key from the fingerprint in a [KEY_HELD_TRACKS] entry. */
    const val HELD_TRACK_SEPARATOR: String = "\t"

    /** `Boolean` - whether the watch is on its charger. The phone waits for it unless [NUDGE_NOW]. */
    const val KEY_WATCH_CHARGING: String = "watchCharging"

    /** `Long` - usable bytes on the watch's volume. Advisory; the watch checks again at ingest. */
    const val KEY_WATCH_FREE_BYTES: String = "watchFreeBytes"

    /** The most albums a watch may want at once. A bound on the pipeline, not on bytes. */
    const val MAX_WANTED_ALBUMS: Int = 12

    /** The most held-track rows the selection reports, for one album. */
    const val MAX_HELD_TRACKS: Int = 60

    /**
     * "Read my selection and publish what it asks for."
     *
     * A message, not a data item: a retained request is a request the phone answers again tomorrow.
     * Reaches [NeedlerWearListenerService] through the manifest filter that already covers `/needler`,
     * so it needed no manifest change. Deliberately not in [COMMAND_PATHS].
     */
    const val PATH_SYNC_NUDGE: String = "/needler/sync/nudge"

    /** Nudge payload: transfer only if the watch says it is charging. */
    const val NUDGE_WHEN_CHARGING: Byte = 0

    /** Nudge payload: the user asked for this now, so transfer off the charger too. */
    const val NUDGE_NOW: Byte = 1
}
