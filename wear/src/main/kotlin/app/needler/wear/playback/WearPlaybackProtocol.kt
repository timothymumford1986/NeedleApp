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
 * **State travels as `DataItem`s** at [PATH_NOW_PLAYING] and [PATH_CRATE]. A `DataItem` is *retained
 * and replicated*: Google Play services keeps the last published copy on both nodes, so a watch app
 * launched an hour after the phone last published still reads the current track on its first frame,
 * with no request and no round trip. That property is the whole reason a data item is right for
 * state.
 *
 * **Commands travel as messages** at the paths in [COMMAND_PATHS], as does [PATH_REQUEST_STATE]. A
 * message is fire-and-forget and is only delivered while the two nodes are connected. That is exactly
 * right for a command and exactly wrong for state - and the converse is worse. A command sent as a
 * data item would be *retained*, so the next time the watch and phone re-synchronised, the phone would
 * replay a pause the user pressed on the train this morning. Do not "simplify" this into one channel.
 *
 * ## Two data items, not one
 *
 * The transport and the crate are separate items for the same reason `PlaybackController` splits
 * `observeState` from `observeQueue`: "a queue edit must not invalidate the transport and a transport
 * change must not re-diff a 300-row list". On a Bluetooth link the argument is stronger than on the
 * phone, because a combined item would push the whole crate across the air on every play, pause and
 * track change. One item changes several times a song; the other changes when somebody edits the
 * crate.
 *
 * ## The republish gotcha
 *
 * The data layer only raises a change event when a data item's bytes actually differ from the copy
 * it already holds. Publishing a byte-identical map is silently a no-op. [KEY_PUBLISHED_AT] exists
 * solely to make every publish distinct, so a phone that republishes a snapshot to force a redelivery
 * - after a reconnect, say - is not swallowed. It is not a clock the watch reads.
 *
 * ## The phone does not publish all day
 *
 * REQUIREMENTS.md "Battery and data" rules out "long-lived connections while backgrounded", and a
 * phone that observed the session and published on every track change from the moment it booted would
 * be exactly that: a Bluetooth write per song, all day, for a watch whose screen is off and whose app
 * is not running.
 *
 * So publishing is *pulled*, not pushed. [PATH_REQUEST_STATE] means "publish a fresh snapshot now, and
 * keep publishing for the next [STATE_WINDOW_MS]". The watch sends it the moment something starts
 * collecting and resends it comfortably inside the window while that is still true; the phone stops
 * observing when the window lapses. Both halves of the trade are deliberate:
 *
 *  * The watch's **first frame** comes from the retained item, which may be minutes old. It is
 *    corrected within one round trip by the answer to the request, which is the one thing a retained
 *    item cannot do for itself.
 *  * A window that lapses while the user is still looking at the watch would freeze the screen, so it
 *    is renewed rather than left to expire. A window that lapses a minute after the wrist drops costs
 *    one minute of publishing nobody reads, which is the bounded price of never needing a teardown
 *    message that can be lost.
 *
 * An explicit "the watch has gone away" message was considered and rejected: it is the half of a
 * handshake that goes missing when a watch walks out of range or its process is killed, and the
 * failure mode is a phone that publishes for the rest of the day believing somebody is watching.
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
 * **The whole crate.** [PATH_CRATE] carries a window of it, not all of it - see [MAX_CRATE_ROWS].
 *
 * **Crate editing.** The watch can jump to a row ([PATH_SKIP_TO_ROW]) and cannot reorder, remove or
 * clear. `PlaybackController` offers all three, and none of them is a thing to do by dragging on a
 * screen an inch across; REQUIREMENTS.md "Accessibility" already notes that reordering needs an
 * accessible action rather than a drag on the *phone*. Jumping to a row is the one crate action a
 * watch can offer honestly.
 *
 * **On-device audio.** No longer absent, and deliberately not folded into the two items above. It is
 * the third item in Wear's v1 scope and much the largest, and it has its own paths, its own channel
 * discipline and its own lifecycle - see "The sync contract" below. Nothing in it changed a single
 * constant of the transport contract, which was the point: the remote has to keep working while an
 * album is crossing the link.
 *
 * ## The sync contract
 *
 * REQUIREMENTS.md "Surfaces beyond the app > Wear OS" ends its v1 scope with "playback of on-device
 * audio synced from the phone over the data layer". Everything under [SYNC_NAMESPACE] is that, and it
 * is a *different shape of problem* from the transport: megabytes rather than bytes, minutes rather
 * than milliseconds, and a thing the user asked for rather than a thing they are looking at.
 *
 * **Audio travels as an `Asset` on a data item per track**, at [PATH_SYNC_TRACK_PREFIX] plus the
 * track key. Three alternatives were considered and rejected:
 *
 *  * **One item for a whole album.** A data item's map is capped at 100 KB and an album is hundreds
 *    of megabytes of assets hanging off one map. Worse, the unit of restart would be the album: one
 *    dropped link and twelve tracks start again.
 *  * **`ChannelClient` with `sendFile` and `receiveFile`.** Genuinely resumable - `sendFile` takes a
 *    start offset and `receiveFile` takes an append flag, which is `Range` resume for the data layer
 *    - and it needs a live channel held open by Needler's own code at both ends. REQUIREMENTS.md
 *    "Battery and data" forbids "long-lived connections while backgrounded", and a stalled album
 *    transfer holding a channel open is exactly the failure that would break the remote the user is
 *    actually using. A retained data item is the opposite: the phone publishes and returns, and
 *    Google Play services delivers when it can, across reconnects, with no process of ours awake.
 *  * **The watch fetching from DroppedNeedle itself.** Ruled out by `wear/build.gradle.kts`, which
 *    depends on neither `:core:data` nor `:core:network`, and by the whole of "Why the data layer at
 *    all" above.
 *
 * The cost of choosing the `Asset` is that a transfer is **restartable rather than resumable**: an
 * interrupted track starts again from zero. That is the bar REQUIREMENTS.md sets for the phone's own
 * downloads only because a phone can issue a `Range` GET; here the unit of loss is one track out of
 * an album, which is the reason the item is per track and not per album.
 *
 * **The watch's own state travels the other way, also as a data item**, at [PATH_SYNC_SELECTION]. It
 * says which albums the user wants on the wrist, which tracks of the one being transferred are
 * already there, and how much room the watch has. It is retained for the same reason the phone's
 * snapshots are: the phone must be able to read it when *it* wakes, not only while the watch is
 * awake.
 *
 * **The nudge is a message**, at [PATH_SYNC_NUDGE]. Same rule as the transport commands, same
 * reason. A "sync now, even off charger" expressed as a retained flag would still be there
 * tomorrow, and the phone would honour a tap the user made on a charger last night. Carried as a
 * message payload it cannot outlive the moment; see [NUDGE_NOW].
 *
 * ## Why the sync pipeline is self-clocking, and never stamps a publish counter
 *
 * The phone publishes at most [MAX_TRACKS_IN_FLIGHT] track items. It picks them by reading the
 * watch's selection and taking the first tracks that the watch has not reported holding, so it needs
 * to remember nothing between passes: the same input produces the same set, and a re-put of an
 * identical item is a *silent no-op* in the data layer.
 *
 * That no-op is the feature here, and it is the exact inverse of "The republish gotcha" above.
 * [KEY_PUBLISHED_AT] is deliberately **absent** from a track item: stamping one would perturb the
 * bytes of every pass and hand Google Play services a changed item to redeliver, which on this
 * channel means putting a thirty-megabyte cover charge on a pass whose answer was "nothing new".
 *
 * The clock is the watch. It ingests a track, records it, republishes its selection and nudges again,
 * and the phone's next pass therefore finds the next tracks and deletes the items the watch has
 * finished with - which is what releases the asset storage on both nodes.
 *
 * ## Where each half of this contract lives
 *
 * The watch half is this module: [DataLayerPlaybackClient] reads both items, sends the commands and
 * asks for the window. For the sync half,
 * [app.needler.wear.sync.WearSyncCoordinator] publishes the selection and nudges,
 * [app.needler.wear.sync.NeedlerWearSyncService] ingests arriving tracks even with the app closed,
 * and [app.needler.wear.store.WearAudioStore] is where the bytes end up.
 *
 * The phone half is `:app`, in `app.needler.wear`, because that is where the `MediaController`
 * binding already lives:
 *
 *  * `NeedlerWearListenerService` - a `WearableListenerService`, declared in `:app`'s manifest with
 *    an intent filter scoped to `wear://` and this namespace. It runs on demand, which is right for
 *    a command and wrong for a publisher, so it does one thing per message and returns. The nudge
 *    lands here too, and needs no manifest change: the filter's path prefix already covers it.
 *  * `WearStatePublisher` - a `@Singleton` that owns the publishing window, observes
 *    `PlaybackController` while the window is open, and encodes the two items.
 *  * `WearAudioSync` - a `@Singleton` that answers a nudge with one bounded pass: read the selection,
 *    publish the next tracks, delete the ones the watch has finished with, return.
 *
 * Both mirror every constant in this file verbatim. There is no shared module to put them in, because
 * `:core:domain` is a pure Kotlin/JVM library and these are Android data-layer paths, and nothing at
 * compile time connects the two copies. `WearPlaybackProtocolTest` on each side therefore asserts the
 * literal strings rather than only comparing constants to each other: a test that says
 * `assertEquals(PATH_NEXT, PATH_NEXT)` passes happily while one copy is renamed.
 */
object WearPlaybackProtocol {

    // ---- State: retained DataItems, phone -> watch ----------------------------------------------

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
     * choice on the phone side, and is what `WearStatePublisher` sends.
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
     * The watch never reads this value; it exists to perturb the bytes. Carried by both items.
     */
    const val KEY_PUBLISHED_AT: String = "publishedAt"

    /**
     * Path of the crate data item - "in the crate" is REQUIREMENTS.md "Vocabulary" for the play
     * queue, and the word "queue" appears nowhere a user can see it.
     *
     * Separate from [PATH_NOW_PLAYING]; see "Two data items, not one" above.
     */
    const val PATH_CRATE: String = "/needler/crate"

    /**
     * `ArrayList<DataMap>` - the published window of the crate, in play order, each entry carrying
     * [KEY_ROW_ID], [KEY_ROW_TITLE] and [KEY_ROW_ARTIST].
     *
     * A list of maps rather than three parallel string arrays: parallel arrays are one off-by-one
     * away from a crate that shows every title against the wrong artist, and the failure would look
     * like bad metadata rather than like a bug.
     */
    const val KEY_CRATE_ROWS: String = "rows"

    /**
     * `String` - the row's `QueueItem.id`, which is what [PATH_SKIP_TO_ROW] carries back.
     *
     * A row id, not a track id. The crate can legitimately hold the same track twice, which is the
     * whole reason `PlaybackController` addresses rows by `QueueItem.id`.
     */
    const val KEY_ROW_ID: String = "rowId"

    /** `String` - the row's track title. */
    const val KEY_ROW_TITLE: String = "rowTitle"

    /** `String` - the row's track artist. */
    const val KEY_ROW_ARTIST: String = "rowArtist"

    /**
     * `Int` - index into the *published window* of the row that is playing, or [NO_CURRENT_ROW].
     *
     * Window-relative, not crate-relative, and that is the one thing to get right here: the phone
     * publishes a window that usually starts partway down a long crate, and a watch that used this as
     * an index into the whole crate would highlight the wrong row and skip to the wrong track. The
     * position of the window in the crate is [KEY_CRATE_WINDOW_START], sent separately and used only
     * for counting.
     */
    const val KEY_CRATE_CURRENT: String = "crateCurrent"

    /**
     * `Int` - how many rows of the crate come before the published window.
     *
     * With [KEY_CRATE_TOTAL] this is what lets the watch say how much it is not showing without
     * carrying rows it will not draw.
     */
    const val KEY_CRATE_WINDOW_START: String = "crateWindowStart"

    /** `Int` - rows in the whole crate, which may be far more than were published. */
    const val KEY_CRATE_TOTAL: String = "crateTotal"

    /**
     * The value of [KEY_CRATE_CURRENT] when no row is playing.
     *
     * A sentinel rather than an absent key, because `DataMap.getInt` answers 0 for a missing key and 0
     * is a perfectly ordinary row index - so "the key was not sent" and "the first row is playing"
     * would decode identically.
     */
    const val NO_CURRENT_ROW: Int = -1

    /**
     * The most rows the phone puts on the wire, counted from the playing row.
     *
     * A data item's map has a hard 100 KB ceiling in Google Play services, and a row costs roughly 200
     * bytes once the id, the two strings and the map overhead are counted - so this cap is not really
     * about the wire: 40 rows is 8 KB. It is about what a person will scroll with a rotary bezel, and
     * about making sure a 2,000-row crate publishes a useful window rather than silently failing to
     * publish at all.
     *
     * The window starts at the playing row rather than at the top of the crate: the watch's crate
     * screen exists to answer "what is coming next", and the rows already played are the ones nobody
     * reaches for on a watch.
     */
    const val MAX_CRATE_ROWS: Int = 40

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

    /**
     * Tapping a row of the crate. Maps to `PlaybackController.skipToQueueItem(itemId)`.
     *
     * The message payload is the row's [KEY_ROW_ID] in UTF-8, and an empty payload is dropped rather
     * than guessed at.
     *
     * An id rather than an index, because the index the watch is looking at may already be wrong. A
     * skip on the phone, a track ending, or a drag on the phone's crate screen all move rows between
     * the watch drawing the list and the user tapping it - and `PlayQueue.withItemMoved` exists
     * because that class of bug silently plays the wrong track. An id that has since left the crate
     * simply matches nothing, which is the correct outcome.
     */
    const val PATH_SKIP_TO_ROW: String = "/needler/command/skip-to-row"

    /** Every command path that drives the session, for the phone-side listener to switch over. */
    val COMMAND_PATHS: Set<String> = setOf(
        PATH_PLAY_PAUSE,
        PATH_NEXT,
        PATH_PREVIOUS,
        PATH_SKIP_TO_ROW,
    )

    /**
     * "Publish a fresh snapshot now, and keep publishing for the next [STATE_WINDOW_MS]."
     *
     * Not in [COMMAND_PATHS] deliberately: it touches nothing a user can hear, it is safe to repeat,
     * and it is the one message the phone answers by *writing* rather than by acting. Keeping it out
     * of that set is what stops a future "drop every command while the session is busy" rule from
     * also dropping the request that keeps the watch's screen honest.
     *
     * Carries no payload. See "The phone does not publish all day" above.
     */
    const val PATH_REQUEST_STATE: String = "/needler/request-state"

    /**
     * How long one [PATH_REQUEST_STATE] keeps the phone observing and publishing.
     *
     * One minute is long enough that a watch screen staying awake through a couple of tracks needs
     * one renewal, and short enough that a watch which vanishes mid-window costs a minute of
     * publishing rather than an afternoon of it. A watch that is still showing a transport resends
     * the request well inside this window; [DataLayerPlaybackClient] uses a third of it, which
     * survives one lost message before anything on screen goes stale.
     */
    const val STATE_WINDOW_MS: Long = 60_000L

    /**
     * The namespace every path in this contract sits under.
     *
     * It is what lets the phone's manifest scope its listener with one `pathPrefix` and never be
     * woken by another app's data layer traffic, and it is what the phone checks first on an inbound
     * message - before the node check - because a path outside this prefix is not addressed to this
     * contract whatever sent it.
     */
    const val NAMESPACE: String = "/needler/"

    // ---- On-device audio: the sync contract -----------------------------------------------------

    /**
     * The namespace every sync path sits under, inside [NAMESPACE].
     *
     * It exists so the *watch's* manifest can scope its one `WearableListenerService` to the sync
     * traffic and nothing else. That matters more here than the outer namespace does on the phone:
     * `wear/src/main/AndroidManifest.xml` used to declare no listener service at all, on the argument
     * that "the watch has nothing to do with a playback snapshot while its screen is off". That
     * argument still holds, and a service filtered on this prefix does not break it - a track item
     * arriving *is* something to do with the screen off, because nobody is going to hold the watch
     * app open for the twenty minutes an album takes over Bluetooth.
     */
    const val SYNC_NAMESPACE: String = "/needler/sync/"

    /**
     * Path of the offer: the albums the phone can put on the watch.
     *
     * Retained, and republished when the phone's downloaded set changes or a watch asks. A retained
     * item is right for the same reason it is right for the transport - the watch's picker draws on
     * its first frame from the copy Google Play services already holds.
     *
     * ## Why the offer is the Device tier and nothing else
     *
     * REQUIREMENTS.md "Vocabulary" separates the **Device** tier - "kept because the user asked for
     * it; never evicted automatically" - from **Temporary**, which is "kept as a side effect of
     * streaming; evicted to hold the device's free-space floor". Only the first is ever offered to the
     * watch, and that is the whole sync policy in one sentence.
     *
     * Three reasons, and the second is the one that decides it:
     *
     *  * **Nothing on a watch is a side effect.** A byte reaches the wrist through a deliberate,
     *    slow, battery-expensive Bluetooth transfer. Filling a watch with whatever the phone happened
     *    to stream in the car is the opposite of what the transfer cost was paid for.
     *  * **The phone's eviction rule cannot be borrowed, so the tier cannot be evictable.** The
     *    listening cache is bounded by device free space and evicted by LRU precisely because a
     *    re-fetch costs 800 ms over Wi-Fi. A re-fetch to the watch costs minutes and may be
     *    impossible - the phone may be out of range, off, or no longer holding the album. Eviction is
     *    therefore close to permanent, which means it must never happen behind the user's back. So
     *    the watch tier is Device-only by construction, and the free-space bound *refuses* an
     *    incoming album instead of evicting one. REQUIREMENTS.md already takes that exact decision
     *    for the phone when the floor cannot be met: "the incoming bytes are **skipped, not forced
     *    in**, and nothing extra is evicted for them".
     *  * **It keeps the offer honest.** A cached-while-listening track can vanish from the phone
     *    between the offer and the transfer, which would make the picker lie.
     *
     * The transcode rule comes free from the same choice, and REQUIREMENTS.md "Why transcoded bytes
     * are never cached" applies here with more force, not less: a lossy copy that became the watch's
     * permanent version of a track is the same silent, permanent downgrade, and harder to notice. The
     * Device tier is fetched with `download?id=`, which serves original bytes only, so an offered
     * album cannot be a transcode. The phone checks the flag anyway - see `WearSyncPlan`.
     */
    const val PATH_SYNC_OFFER: String = "/needler/sync/offer"

    /**
     * `ArrayList<DataMap>` - the offered albums, each carrying [KEY_ALBUM_KEY], [KEY_ALBUM_TITLE],
     * [KEY_ALBUM_ARTIST], [KEY_ALBUM_TRACK_COUNT] and [KEY_ALBUM_BYTES].
     *
     * A list of maps rather than parallel arrays, for the reason [KEY_CRATE_ROWS] gives: an
     * off-by-one in parallel arrays shows every album's title against another album's size, and looks
     * like bad metadata rather than a bug.
     */
    const val KEY_OFFER_ALBUMS: String = "offerAlbums"

    /**
     * `String` - the album's release-group MBID, bare, with no `al-` prefix.
     *
     * The one key both server lanes agree on, per REQUIREMENTS.md "Identity model", and the prefix of
     * every track key in the album.
     */
    const val KEY_ALBUM_KEY: String = "albumKey"

    /** `String` - album title, as the watch's picker lists it. */
    const val KEY_ALBUM_TITLE: String = "albumTitle"

    /** `String` - album artist. On a compilation this differs from a track's own artist. */
    const val KEY_ALBUM_ARTIST: String = "albumArtist"

    /**
     * `Int` - how many tracks of this album are actually downloaded on the phone.
     *
     * Not how many the album has. REQUIREMENTS.md "Partial content is a normal state" describes both
     * halves of that difference, and the watch needs the downloadable figure rather than the true
     * one: it is what tells the watch when an album is as complete on the wrist as it can be, and a
     * count the phone cannot deliver would leave the pipeline asking for a track that does not exist
     * for ever.
     */
    const val KEY_ALBUM_TRACK_COUNT: String = "albumTrackCount"

    /**
     * `Long` - bytes on the phone's disk for this album.
     *
     * Real bytes, as the phone's cache index accounts for them, not what the server says the album
     * weighs - the same rule REQUIREMENTS.md sets for the Storage screen. It is what the watch checks
     * against its own free space *before* asking for an album, so that an album which cannot fit is
     * refused at the tap rather than halfway through.
     */
    const val KEY_ALBUM_BYTES: String = "albumBytes"

    /** `Int` - downloaded albums the phone did not list, so the picker can say so. */
    const val KEY_OFFER_NOT_SHOWN: String = "offerNotShown"

    /**
     * The most albums the offer carries, most recently downloaded first.
     *
     * A data item's map is capped at 100 KB and an album row costs roughly 150 bytes, so sixty rows
     * is nine kilobytes - the cap is about the picker, not the wire. Sixty is already a long scroll
     * on a rotary bezel, and the newest downloads are the ones somebody is choosing between.
     *
     * A user whose album is off the end of this list cannot reach it from the watch, which is a real
     * limitation and the honest place to fix it is the phone: Screen 12's Storage section already
     * lists every downloaded album by size, and a "keep on watch" control belongs beside the remove
     * control there rather than as a paging protocol across Bluetooth.
     */
    const val MAX_OFFER_ALBUMS: Int = 60

    /**
     * Prefix of a track item's path; the track key follows it.
     *
     * The key is `<release-group-mbid>/<disc>/<track>`, so a path is that appended to this prefix.
     * Data-layer paths are slash-separated, which means the key needs no encoding at all and the
     * whole family still matches one `pathPrefix` in the watch's manifest.
     *
     * ## What the key is, and what it is emphatically not
     *
     * It is `TrackKey.canonicalString` from `:core:domain` - release-group MBID, disc number, track
     * number - and it is identical to what the phone's own `audio_cache` is keyed on. REQUIREMENTS.md
     * "Track identity is not stable" is the reason: DroppedNeedle replaces files in place on a quality
     * upgrade, so a `file_id` "identifies whatever bytes currently sit behind a track, not the track
     * itself", and keying on one means the user keeps the worse copy for ever with nothing reporting
     * it. On a wrist that is worse again, because the bytes are expensive to replace.
     *
     * The watch key has exactly the phone's three parts and not a fourth. REQUIREMENTS.md names the
     * recording MBID as part of the identity tuple "where the server provides one", and `TrackKey`
     * does not carry it - the mirror keys on three parts, so a watch keying on four could not be
     * diffed against the phone's store at all, and the first sync after a track gained a recording
     * MBID would re-transfer the album. Same rule as the phone store, which is what was asked for.
     */
    const val PATH_SYNC_TRACK_PREFIX: String = "/needler/sync/track/"

    /**
     * `String` - the track key, repeated inside the map.
     *
     * Repeated on purpose. The watch could parse it off the path, and every piece of code that did so
     * would be one more place that has to agree about the prefix. Reading it from the map means the
     * ingest path handles a `DataItem` the same way whether it arrived as a change event or was read
     * out of the retained set.
     */
    const val KEY_TRACK_KEY: String = "trackKey"

    /** `String` - track title. */
    const val KEY_TRACK_TITLE: String = "trackTitle"

    /** `String` - track artist, which on a compilation differs from the album artist. */
    const val KEY_TRACK_ARTIST: String = "trackArtist"

    /** `String` - album title, so the watch can group its store without consulting the offer. */
    const val KEY_TRACK_ALBUM: String = "trackAlbum"

    /** `Long` - duration in milliseconds, or 0 when the server never said. */
    const val KEY_TRACK_DURATION_MS: String = "trackDurationMs"

    /**
     * `String` - the `AudioFormat` name the bytes are in, e.g. `FLAC`.
     *
     * Two uses, and neither is decoding: it names the file's extension in the watch's store, and it
     * is the quality badge the watch shows against a track. Media3 sniffs the container itself, so a
     * wrong or `UNKNOWN` value costs a badge and nothing else.
     */
    const val KEY_TRACK_FORMAT: String = "trackFormat"

    /** `Long` - the audio's length in bytes, so the watch can check its room before ingesting. */
    const val KEY_TRACK_BYTES: String = "trackBytes"

    /**
     * `String` - an opaque token identifying *these* bytes, for the staleness check.
     *
     * The format is the phone's, written and read only by the phone: the fields of
     * `TrackFetchHandle` - file id, size, duration, format, bitrate - joined by
     * [FINGERPRINT_SEPARATOR], with [FINGERPRINT_ABSENT] for a field the server did not report.
     *
     * The watch stores it beside the audio and echoes it back in [KEY_HELD_TRACKS]. **It never parses
     * it and never keys anything on it.** That division is what implements REQUIREMENTS.md
     * "Invalidating upgraded files" across two devices: the fingerprint "lives on `audio_cache`... a
     * record of the file *as it was at download time*, which sync never touches", and on the watch it
     * lives beside the bytes for the same reason and with the same meaning.
     *
     * The comparison is the phone's job because only the phone can see the server. It is
     * deliberately **not** a string equality: REQUIREMENTS.md is explicit that "a field counts as
     * changed only when both sides carry a value", or "a server release that stopped reporting
     * durations... would declare every cached track stale and re-download the user's entire offline
     * library". Over Bluetooth that would be a day of transfers for nothing. `WearSyncPlan` on the
     * phone parses this token back into its fields and applies exactly the rule
     * `TrackFetchHandle.isStaleComparedTo` applies.
     */
    const val KEY_TRACK_FINGERPRINT: String = "trackFingerprint"

    /** Separator between the fields of [KEY_TRACK_FINGERPRINT]. */
    const val FINGERPRINT_SEPARATOR: String = "|"

    /** A [KEY_TRACK_FINGERPRINT] field the server did not report. Means unknown, never "changed". */
    const val FINGERPRINT_ABSENT: String = "-"

    /**
     * `Asset` - the audio itself, in the server's original format.
     *
     * The only large thing on this wire, and the reason this is a data item per track rather than one
     * map for an album. See "The sync contract" above for why an `Asset` beat a `ChannelClient`.
     */
    const val KEY_TRACK_AUDIO: String = "trackAudio"

    /**
     * The largest track the phone will put on the wire: 48 MB.
     *
     * Not a data-layer limit - a data item's *map* is capped at 100 KB and an asset is transferred
     * out of band - but a limit worth having anyway. 48 MB is a generous 24-bit FLAC album side, and
     * a track past it would sit on the link for the better part of an hour for one song.
     *
     * A refused track is reported to the watch as missing rather than silently skipped, so the "on
     * watch" screen can say the album is incomplete and why. The honest fix for those tracks is a
     * `ChannelClient` transfer with its resume offsets, which is the alternative recorded above.
     */
    const val MAX_TRACK_BYTES: Long = 48L * 1024L * 1024L

    /**
     * How many track items the phone keeps published at once.
     *
     * One would serialise the watch's write against the next transfer, so the link idles for as long
     * as it takes to copy thirty megabytes out of Google Play services' store. Three or more hands
     * Play services several large assets to interleave, which is how a twelve-track album becomes
     * twelve simultaneously half-finished transfers, none of them playable and all of them lost
     * together when the link drops. Two keeps the link busy while the watch writes, and keeps the
     * unit of loss at one track.
     */
    const val MAX_TRACKS_IN_FLIGHT: Int = 2

    /**
     * Prefix of an album cover item's path; the release-group MBID follows it.
     *
     * One cover per selected album, not per track, exactly as [KEY_ARTWORK_ID] does for the
     * transport: every track on an album shares it. A 200 px JPEG is 10 to 20 KB against an album's
     * several hundred megabytes, so the cost is a rounding error, and without it the watch's own
     * now-playing screen would draw a record placeholder while the phone remote next to it shows a
     * cover - the same screen, contradicting itself depending on which source is playing.
     */
    const val PATH_SYNC_COVER_PREFIX: String = "/needler/sync/cover/"

    /** `String` - the release-group MBID this cover belongs to, repeated inside the map. */
    const val KEY_COVER_ALBUM_KEY: String = "coverAlbumKey"

    /** `Asset` - the cover bitmap, at the same 200 px the transport's artwork uses. */
    const val KEY_COVER_IMAGE: String = "coverImage"

    /**
     * Path of the watch's selection: what the user wants on the wrist, and what is already there.
     *
     * Published by the **watch**, which is the only item on this wire that travels that way, and the
     * reason is the same property that makes the phone's snapshots data items: the phone has to be
     * able to read it when *the phone* wakes. A nudge arrives, the listener service starts, and the
     * selection is already on the device - no request, no round trip, and no requirement that the
     * watch still be awake by the time the phone gets to it.
     */
    const val PATH_SYNC_SELECTION: String = "/needler/sync/selection"

    /**
     * `ArrayList<String>` - release-group MBIDs the user wants on the watch, in priority order.
     *
     * This list *is* the sync policy's user-facing half. REQUIREMENTS.md gives the watch no drawn
     * screen and no settings of its own, and the requirement that "the user must be able to see and
     * change what is on it" has to be met somewhere; it is met on the watch, because the person
     * deciding what to take on a run is the one wearing it. Order is the order they added albums,
     * and it decides which album the pipeline finishes first.
     */
    const val KEY_WANTED_ALBUMS: String = "wantedAlbums"

    /**
     * `String` - the release-group MBID the watch wants bytes for right now, or empty for none.
     *
     * One album at a time, and [KEY_HELD_TRACKS] describes that album alone. The alternative - the
     * watch reporting its entire holdings so the phone could plan over the whole selection - was
     * rejected on the item cap: a full manifest of a dozen albums is tens of kilobytes against a
     * 100 KB ceiling, and an item that grows with the user's library is an item that one day silently
     * fails to publish. Bounding the report to one album bounds it to about sixty rows for ever.
     *
     * The watch picks the first wanted album it has not filled. When every wanted album is full it
     * rotates through them, one per sync session, so the staleness check reaches all of them
     * eventually without a timer anywhere.
     */
    const val KEY_ACTIVE_ALBUM: String = "activeAlbum"

    /**
     * `ArrayList<String>` - what the watch holds for [KEY_ACTIVE_ALBUM], one entry per track:
     * the track key, then [HELD_TRACK_SEPARATOR], then the [KEY_TRACK_FINGERPRINT] it arrived with.
     *
     * Complete tracks only. A half-written file is not held, or a dropped link would leave a truncated
     * track on the wrist that nothing ever replaces - the same failure REQUIREMENTS.md records for the
     * phone, where "a truncated file published as complete... plays, and stops halfway through with
     * nothing reporting an error".
     */
    const val KEY_HELD_TRACKS: String = "heldTracks"

    /**
     * Separates the track key from the fingerprint in a [KEY_HELD_TRACKS] entry.
     *
     * A tab, because neither field can contain one: a track key is hex, digits and slashes, and a
     * fingerprint is built by the phone from the same. The phone drops an entry that does not split
     * into exactly two parts rather than guessing which half it has.
     */
    const val HELD_TRACK_SEPARATOR: String = "\t"

    /**
     * `Boolean` - whether the watch is on its charger.
     *
     * The phone reads it and, by default, transfers only when it is true. A watch off the charger
     * pushing an album across Bluetooth is the most expensive thing this app can do to its battery,
     * and REQUIREMENTS.md "Battery and data" is the general form of the rule. [NUDGE_NOW] is how the
     * user overrides it, because a user who has just asked for an album and is about to leave the
     * house is entitled to spend their own battery.
     */
    const val KEY_WATCH_CHARGING: String = "watchCharging"

    /**
     * `Long` - usable bytes on the volume the watch's store sits on, as the watch reads them now.
     *
     * Sent so the phone can refuse to publish bytes that will not fit rather than transferring them
     * and having the watch throw them away. It is advisory: the watch checks again at ingest, because
     * free space moves while a transfer is in flight, and the watch's reading is the one that
     * decides.
     */
    const val KEY_WATCH_FREE_BYTES: String = "watchFreeBytes"

    /**
     * The most albums a watch may want at once.
     *
     * Not a byte bound - that is free space, see `WearStoreSpace` - but a bound on the *pipeline*. A
     * dozen albums is more music than a watch has room for on any device shipping today, and it keeps
     * [KEY_WANTED_ALBUMS] and the picker's selected set small enough to reason about.
     */
    const val MAX_WANTED_ALBUMS: Int = 12

    /**
     * The most held-track rows the selection reports, for one album.
     *
     * Sixty rows covers a double album with room to spare, and caps the item at a few kilobytes. An
     * album with more tracks than this reports the first sixty, which makes the pipeline fetch the
     * tail of it more slowly rather than not at all.
     */
    const val MAX_HELD_TRACKS: Int = 60

    /**
     * "Read my selection and publish what it asks for."
     *
     * A message, not a data item, for the reason every command on this wire is: a retained request is
     * a request the phone answers again tomorrow. Carries one byte, [NUDGE_WHEN_CHARGING] or
     * [NUDGE_NOW].
     *
     * Deliberately not in [COMMAND_PATHS]: it touches nothing a user can hear, exactly like
     * [PATH_REQUEST_STATE], and it must not be caught by a future rule that drops commands.
     */
    const val PATH_SYNC_NUDGE: String = "/needler/sync/nudge"

    /**
     * Nudge payload: transfer only if the watch says it is charging.
     *
     * The default, and what the watch sends when its own state changed rather than when the user
     * asked for something - a track finished ingesting, say.
     */
    const val NUDGE_WHEN_CHARGING: Byte = 0

    /**
     * Nudge payload: the user asked for this album now, so transfer off the charger too.
     *
     * The override to [KEY_WATCH_CHARGING], and the reason it rides on a message. It expresses a tap
     * that happened a moment ago and cannot be replayed tomorrow.
     */
    const val NUDGE_NOW: Byte = 1
}
