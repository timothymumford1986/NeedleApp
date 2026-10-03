package app.needler.feature.player

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.feature.player.ui.PlayerFormat

/**
 * Everything the transport surfaces draw, and nothing that ticks.
 *
 * This is [PlaybackState] with the strings already made: the same fields, plus the title, subtitle
 * and format badge the pack prints, so the composables hold no formatting logic and the formatting
 * can be tested without rendering anything.
 *
 * **Position is not here, deliberately.** `PlaybackController` splits the ticking half of playback
 * into its own flow precisely so that a screen bound to this one does not recompose several times a
 * second for an entire album, and folding it back in "for tidiness" would undo that. The scrubber
 * and the two time labels take [app.needler.core.domain.playback.PlaybackProgress] directly, as a
 * deferred read, and they are the only things that do.
 *
 * The queue is absent for the mirror-image reason: the crate is a long list that changes rarely, and
 * a queue edit must not re-run the transport's layout. It has its own state, [CrateUiState].
 */
data class PlayerUiState(
    /** The crate row that is playing or paused, or null when nothing is loaded. */
    val item: QueueItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** Length of the current item, or null while unknown. The scrubber needs it; the tick does not. */
    val durationMs: Long? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Where the session is actually routing sound, which is not always where the user pointed it. */
    val output: OutputTarget? = null,
    /** The last playback failure, shown as a line above the transport until a command succeeds. */
    val error: NeedlerError? = null,
    /** How many rows follow the playing one. Drawn as a count only, so it costs no list diff. */
    val upNextCount: Int = 0,
    /**
     * Whether the playing track is starred.
     *
     * Binary, because binary is all this server supports: REQUIREMENTS.md records `setRating` as "a
     * deliberate no-op on this server... Needler must not offer star ratings", and the domain has no
     * rating concept to hold one anyway. Read from `FavouriteRepository.observeIsFavourite`, which serves
     * it from the mirror, so it flips under the finger whether or not the server can be reached.
     */
    val isFavourite: Boolean = false,
    /**
     * A star that the server permanently refused, or null.
     *
     * Separate from [error], which is a *playback* failure. They land in the same line on screen but they
     * are not the same thing, and folding them together would mean a failed star clearing itself the next
     * time a track played.
     */
    val favouriteError: NeedlerError? = null,
    /**
     * The playing track's artist, when the artist has been resolved to an MBID.
     *
     * Null is the ordinary state for the first frames of a track, and permanent for an artist the mirror
     * holds no MBID for. `Track` carries an artist *name* only - see
     * [app.needler.feature.player.ui.TrackByline] for why the byline follows this field rather than
     * drawing a link that might not work.
     */
    val artistMbid: ArtistMbid? = null,
    /** The armed sleep timer. Off is by far its commonest value. */
    val sleepTimer: SleepTimer = SleepTimer.Off,

    /**
     * What the server would send if this track were fetched right now, or null before it is known.
     *
     * Live and network-dependent: the same track is `Original` at home and `Transcoded(mp3, 192)` on
     * mobile data, because the resolver picks the rung from the connection and then caps it against
     * the track's own quality. Held as the resolved [StreamFormat] rather than as a string so the
     * `Transcoded` case can be told from the `Original` one - which is exactly the distinction the
     * Server tag exists to draw.
     */
    val serverFormat: StreamFormat? = null,

    /**
     * The quality of the copy on this device, or null when there is not one.
     *
     * **Non-null means the Device tier**, never merely the Temporary one. A temporarily cached copy is
     * evictable under disk pressure, so showing it as `Device:` would promise offline availability the
     * app cannot keep; `CachedAudio.pinned` carries that distinction and the ViewModel applies it. It also
     * goes null when the bytes are stale - the server replaced the file on a quality upgrade - because
     * the resolver will discard them on the next play, and a tag that outlived the copy it describes
     * would be worse than no tag.
     */
    val pulledQuality: AudioQuality? = null,
) {
    /** True when there is something to draw a title and a transport for. */
    val hasTrack: Boolean get() = item != null

    /** The big line: the track title, or the pack's empty-state heading. */
    val title: String get() = item?.track?.title ?: "Nothing playing"

    /** The small line: `The Marias - Submarine`, or what to do about it. */
    val subtitle: String
        get() = if (item == null) "Play an album and it lands in the crate" else PlayerFormat.artistAndAlbum(item)

    /** The quality badge beside the title, or null when the server reported no format. */
    val formatBadge: String? get() = PlayerFormat.formatBadge(quality)

    // ---- the quality tag pair ------------------------------------------------------------------
    // Two tags rather than one badge, because there are two facts and they are not always the same
    // one. `Server:` is what pressing play would fetch; `Device:` is what is already here. A local
    // copy always wins, so when both exist only one of them is what you are hearing - and drawing
    // them as equals would imply the server rate is, which it is not.
    //
    // Which one applies is carried by the chip and the weight, never by the hue: the hue names the
    // state, so `Server:` is always accent and `Device:` always positive. See `NeedlerAlbumSource`.

    /** True when a downloaded copy exists, which is therefore what plays. */
    val isPulled: Boolean get() = pulledQuality != null

    /**
     * True when what would play right now is a server-side re-encode.
     *
     * The one state in which REQUIREMENTS.md "Why transcoded bytes are never cached" is a fact about
     * *this* track: a transcode's bytes are played and discarded, so nothing accumulates on the
     * device however often it is played. [app.needler.feature.player.ui.QualityTags] draws the caveat
     * on this and on nothing else, which is what keeps it off the screen in the common case - the
     * default Wi-Fi rung is `Original`.
     *
     * False while a download exists, even on a transcoding rung. The local copy is what plays, so the
     * server rung is dormant and a line about bytes not being kept would describe bytes nobody is
     * fetching - and the copy that *is* on the device is being kept.
     */
    val isStreamingTranscoded: Boolean
        get() = !isPulled && serverFormat is StreamFormat.Transcoded

    /** `MP3 192`, `FLAC` - what the server would send now, or null when it is not known yet. */
    val serverTagValue: String? get() = PlayerFormat.serverBadge(format = serverFormat, source = quality)

    /** `FLAC` - the on-device copy's own quality, or null when there is no download. */
    val pulledTagValue: String? get() = PlayerFormat.pulledBadge(pulledQuality)

    /** The Server tag's spoken form, which says whether it is in use. */
    val serverTagDescription: String?
        get() = PlayerFormat.spokenServerBadge(
            format = serverFormat,
            source = quality,
            inUse = !isPulled,
        )

    /** The Device tag's spoken form. */
    val pulledTagDescription: String? get() = PlayerFormat.spokenPulledBadge(pulledQuality)

    /** The current item's audio quality, or null when nothing is loaded. */
    val quality: AudioQuality? get() = item?.track?.quality

    /**
     * The artist's name on its own, for the half of the byline that is a link.
     *
     * Empty when nothing is loaded, which no surface draws: the idle state prints [subtitle] instead.
     */
    val artistName: String get() = item?.track?.artistName.orEmpty()

    /** The album's title on its own, or null when the server reported none. */
    val albumTitle: String? get() = item?.track?.albumTitle?.takeIf { it.isNotBlank() }

    /** True when the artist line has somewhere to go, and therefore when it is drawn as a link. */
    val canOpenArtist: Boolean get() = artistMbid != null && item != null

    /** The favourite failure line, or null. */
    val favouriteErrorMessage: String? get() = favouriteError?.let(PlayerFormat::favouriteErrorMessage)

    /** Where sound is going, named plainly, whether or not anything is playing. */
    val outputName: String get() = PlayerFormat.outputName(output)

    /** The failure line, or null. */
    val errorMessage: String? get() = error?.let(PlayerFormat::errorMessage)

    /**
     * How the whole player reads aloud, for surfaces that merge into one node - the mini player.
     */
    val spokenSummary: String
        get() = if (item == null) {
            "Nothing playing"
        } else {
            title + ", " + subtitle + ", " + (if (isPlaying) "playing" else "paused") +
                ", on " + outputName
        }

    companion object {
        /** Nothing loaded: what every player surface draws before the session connects. */
        val Idle: PlayerUiState = PlayerUiState()

        /**
         * Folds a [PlaybackState] and the crate's size into the UI's own shape.
         *
         * [upNextCount] comes from the queue flow rather than from the state, so it is passed in:
         * the two arrive separately and a transport update must not wait on a queue update. The same is
         * true of [isFavourite] and [artistMbid], which come from two other repositories and resolve on
         * their own schedule - a transport update must not wait on a Room query either.
         *
         * The sleep timer is *not* passed in. It rides on [PlaybackState] because every surface that
         * shows a transport already holds a `PlaybackController` and nothing else.
         */
        fun from(
            state: PlaybackState,
            upNextCount: Int = 0,
            isFavourite: Boolean = false,
            favouriteError: NeedlerError? = null,
            artistMbid: ArtistMbid? = null,
            serverFormat: StreamFormat? = null,
            pulledQuality: AudioQuality? = null,
        ): PlayerUiState = PlayerUiState(
            item = state.currentItem,
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            durationMs = state.durationMs,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
            output = state.output,
            error = state.error,
            upNextCount = upNextCount,
            isFavourite = isFavourite,
            favouriteError = favouriteError,
            artistMbid = artistMbid,
            sleepTimer = state.sleepTimer,
            serverFormat = serverFormat,
            pulledQuality = pulledQuality,
        )
    }
}
