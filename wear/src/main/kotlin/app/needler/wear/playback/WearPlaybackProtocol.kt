package app.needler.wear.playback

/**
 * The wire contract between the phone app and the watch, over the Wearable data layer.
 *
 * ## Why the data layer at all
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" puts the v1 scope at "transport controls, the
 * crate, and playback of on-device audio synced from the phone **over the data layer**", and the
 * architecture diagram in "Architecture" draws the Wear companion as one more client of the single
 * Media3 `MediaLibraryService`. Those two statements need reconciling, because a `MediaController`
 * cannot reach a `MediaSession` that lives in another process on another device: a `SessionToken` is
 * resolved through the local `MediaSessionManager`, and Wear OS 3 removed the automatic
 * phone-to-watch media session bridging that Wear 2 had. So "one more client of the session" is true
 * of the *phone side* of this bridge, which owns a `MediaController` like every other surface; the
 * watch is a client of that bridge rather than of the session directly.
 *
 * The third option - a standalone watch client talking to DroppedNeedle itself - is ruled out by
 * `wear/build.gradle.kts`, which deliberately depends on neither `:core:data` nor `:core:network`,
 * and by REQUIREMENTS.md's Wear scope, which excludes search and pulling. It would also mean putting
 * the server credentials, the Subsonic envelope parsing and the whole offline write queue on a watch
 * to render four lines of text.
 *
 * ## Two channels, deliberately, and they are not interchangeable
 *
 * **State travels as a `DataItem`** at [PATH_NOW_PLAYING]. A `DataItem` is *retained and replicated*:
 * Google Play services keeps the last published copy on both nodes, so a watch app launched an hour
 * after the phone last published still reads the current track on its first frame, with no request
 * and no round trip. That property is the whole reason a data item is right for state.
 *
 * **Commands travel as messages** at [PATH_PLAY_PAUSE], [PATH_NEXT] and [PATH_PREVIOUS]. A message is
 * fire-and-forget and is only delivered while the two nodes are connected. That is exactly right for
 * a command and exactly wrong for state - and the converse is worse. A command sent as a data item
 * would be *retained*, so the next time the watch and phone re-synchronised, the phone would replay
 * a pause the user pressed on the train this morning. Do not "simplify" this into one channel.
 *
 * ## The republish gotcha
 *
 * The data layer only raises a change event when a data item's bytes actually differ from the copy
 * it already holds. Publishing a byte-identical map is silently a no-op. [KEY_PUBLISHED_AT] exists
 * solely to make every publish distinct, so a phone that republishes a snapshot to force a redelivery
 * - after a reconnect, say - is not swallowed. It is not a clock the watch reads.
 *
 * ## What is deliberately not on the wire
 *
 * **Position.** There is no `positionMs`, and the watch draws no scrubber. `PlaybackController`
 * splits [app.needler.core.domain.playback.PlaybackProgress] from `PlaybackState` precisely because
 * position ticks several times a second; pushing that rate across a Bluetooth link would flatten the
 * watch battery to animate a bar nobody is looking at. Replaying it locally instead needs a shared
 * clock, and the two devices' `elapsedRealtime` clocks are unrelated - so the cheap version is also
 * the wrong version. If a scrubber is ever wanted, send position plus the wall-clock instant it was
 * measured at, and extrapolate; do not send ticks.
 *
 * **The crate.** REQUIREMENTS.md puts it in Wear's v1 scope and it is not built here. It wants a
 * second data item with its own path, a `ScalingLazyColumn`, and a `skipToQueueItem` command; that is
 * a separate piece of work and bolting a partial one onto this pass would leave both half-done.
 *
 * **On-device audio.** Also v1 scope per REQUIREMENTS.md, and much the largest of the three: it needs
 * an `Asset` transfer per track, a store on the watch and the watch's own `MediaLibraryService`. This
 * module controls the phone's playback; it does not yet play anything itself.
 *
 * ## The phone side of this contract does not exist yet
 *
 * Nothing in `:app` or `:player:service` publishes [PATH_NOW_PLAYING] or listens for the command
 * paths - a repository-wide search for `DataClient`, `MessageClient` and `WearableListenerService`
 * finds only this module. Until that lands, the watch app compiles, runs, and correctly renders
 * [WearPlaybackState.PhoneUnreachable] or [WearPlaybackState.Idle] forever. The phone side is a
 * `WearableListenerService` plus a `PlaybackController` observer, and it belongs in `:app`, which is
 * where the `MediaController` binding already lives. It must mirror every constant in this file
 * verbatim; there is no shared module to put them in, because `:core:domain` is a pure Kotlin/JVM
 * library and these are Android data-layer paths.
 */
object WearPlaybackProtocol {

    // ---- State: a retained DataItem, phone -> watch ------------------------------------------

    /**
     * Path of the now-playing data item.
     *
     * Data layer paths are absolute and slash-separated. Everything Needler puts on the wire sits
     * under `/needler/` so that a listener can filter on a prefix and never see another app's items.
     */
    const val PATH_NOW_PLAYING: String = "/needler/now-playing"

    /**
     * `Boolean` - whether anything is loaded at all.
     *
     * This mirrors `PlaybackState.hasCurrentItem`. It is sent explicitly rather than inferred from an
     * empty title, because a track legitimately can have a blank artist and an unhelpful title, and
     * "the session has nothing" and "the session has something badly tagged" must not render the
     * same.
     */
    const val KEY_HAS_ITEM: String = "hasItem"

    /** `String` - track title. */
    const val KEY_TITLE: String = "title"

    /** `String` - track artist, which on a compilation differs from the album artist. */
    const val KEY_ARTIST: String = "artist"

    /** `String`, optional - album title, shown only when there is room for a third line. */
    const val KEY_ALBUM: String = "album"

    /** `Boolean` - mirrors `PlaybackState.isPlaying`; decides which glyph the primary button shows. */
    const val KEY_IS_PLAYING: String = "isPlaying"

    /**
     * `Boolean` - mirrors `PlaybackState.isBuffering`.
     *
     * Buffering is not the opposite of playing, for the same reason it is not on the phone: flipping
     * the button to a play icon on every mid-stream stall is how a player looks broken.
     */
    const val KEY_IS_BUFFERING: String = "isBuffering"

    /**
     * `String`, optional - a stable identifier for the artwork carried in [KEY_ARTWORK].
     *
     * The bytes are an `Asset`, which the watch must resolve over a second, asynchronous hop. The id
     * is what lets the watch skip that hop when the artwork has not changed - every track on an album
     * shares one id - and it is what the decoded-bitmap cache is keyed on. Any stable string will do
     * as long as it changes when and only when the image does; the release group MBID is the obvious
     * choice on the phone side.
     */
    const val KEY_ARTWORK_ID: String = "artworkId"

    /**
     * `Asset`, optional - the artwork bitmap itself.
     *
     * An `Asset` is the data layer's out-of-band channel for anything large: the map carries a digest
     * and the bytes are transferred separately and lazily. This is the only way the watch can show
     * artwork at all, because it has no credentials for the server and, on a VPN-only or self-signed
     * DroppedNeedle, no route to it either.
     *
     * The phone should send a small square - 200 px or so is ample for any watch - not the full-size
     * cover. The transfer is over Bluetooth.
     */
    const val KEY_ARTWORK: String = "artwork"

    /**
     * `Long` - a monotonic publish counter or timestamp. See "The republish gotcha" above.
     *
     * The watch never reads this value; it exists to perturb the bytes.
     */
    const val KEY_PUBLISHED_AT: String = "publishedAt"

    // ---- Commands: messages, watch -> phone ----------------------------------------------------

    /**
     * The transport's primary button. Maps to `PlaybackController.playPause()`.
     *
     * Deliberately one path rather than separate play and pause paths: the phone is the authority on
     * what is playing, and a watch that has been asleep may be acting on a stale snapshot. Sending
     * the *intent* ("toggle") rather than the *target state* ("play") means a stale watch cannot
     * fight the session.
     */
    const val PATH_PLAY_PAUSE: String = "/needler/command/play-pause"

    /** Maps to `PlaybackController.skipToNext()`. */
    const val PATH_NEXT: String = "/needler/command/next"

    /**
     * Maps to `PlaybackController.skipToPrevious()`.
     *
     * Note what that contract says: past a short threshold into a track, previous restarts the track
     * rather than leaving it, and "callers must not implement it themselves, or two surfaces disagree
     * about what the same button does". The watch therefore sends the intent and lets the session
     * decide - it does not read the position, which it does not have anyway.
     */
    const val PATH_PREVIOUS: String = "/needler/command/previous"

    /** Every command path, for the phone-side listener to switch over. */
    val COMMAND_PATHS: Set<String> = setOf(PATH_PLAY_PAUSE, PATH_NEXT, PATH_PREVIOUS)
}
