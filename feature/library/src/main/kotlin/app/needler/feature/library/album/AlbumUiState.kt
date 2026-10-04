package app.needler.feature.library.album

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheetState

/**
 * Everything the album screen renders — screens 04 (owned) and 05 (not owned),
 * and 11 on a tablet.
 *
 * **There is one album screen, not two.** REQUIREMENTS.md "Identity model":
 * "an album found by catalogue search and an album already in the library are
 * the same domain object at different states … The UI never has separate
 * 'search result' and 'library album' types, which removes a whole class of
 * duplicate-rendering bugs." So the difference between screens 04 and 05 is
 * entirely [Album.state]: the same header, the same track list, a different
 * action.
 */
data class AlbumUiState(

    /** True until the mirror has answered. */
    val loading: Boolean = true,

    val album: Album? = null,
    val tracks: List<AlbumTrack> = emptyList(),

    val nowPlayingTrackKey: TrackKey? = null,

    /**
     * What the primary control does, which is not always "play from the top".
     *
     * Defaults to [AlbumTransport.START] so that a screen with no player bound - a screenshot, a
     * build with no session - offers the same thing it always did.
     */
    val transport: AlbumTransport = AlbumTransport.START,

    /** How far the download to this device has got, when the album is pinned. */
    val download: OfflineDownloadState? = null,

    /**
     * Whether the server lets this user download to the device at all.
     *
     * REQUIREMENTS.md: "Offline downloads are separately gated by an
     * administrator. Needler must call `GET /api/v1/download/access` and hide
     * every pin and download affordance when `allowed` is false, rather than
     * letting the action fail later."
     */
    val downloadAllowed: Boolean = true,

    val offline: Boolean = false,

    /** An action is in flight, so the buttons are disabled rather than tappable twice. */
    val busy: Boolean = false,

    /** The result of the last action, or an explanation the screen owes the user. */
    val notice: AlbumNotice? = null,

    /**
     * The pull sheet, open, or null when it is closed.
     *
     * Pulling is two steps rather than one now: tapping **Pull this album** opens
     * this, and confirming places the request. REQUIREMENTS.md "Placing a
     * request" requires the `monitor_artist` toggle to live on a request sheet,
     * and there was no sheet, so the flag `RequestAlbumUseCase` has always taken
     * had no caller anywhere in the app.
     */
    val requestSheet: RequestSheetState? = null,

    /**
     * What the server would send for this album's tracks if play were pressed right now, or null
     * before it is known.
     *
     * Resolved by the same pure function playback uses, from this album's own quality, the rung for
     * the current connection and any override on it - so the tag and the fetch cannot disagree. It is
     * live and network-dependent: the same album reads `Server: FLAC` at home and `Server: MP3 192`
     * on mobile data, which is the entire point of showing it.
     *
     * Album-wide rather than per track, because the tag is drawn once in the header. A record whose
     * tracks are not all the same format is possible and rare; `Album.quality` is the denormalised
     * figure every row already badges, so the header agrees with the rows.
     */
    val serverFormat: StreamFormat? = null,

    /** The album's own override, or null when it follows the mode default for the connection. */
    val qualityOverride: StreamRung? = null,

    /**
     * How many rows the crate holds right now, from `PlaybackController.observeQueue`.
     *
     * Here rather than computed when an add happens, because the session is the authority on
     * the crate and the only honest figure is the one it has actually applied - see
     * [crateLine]. Zero means nothing is loaded, which is also what the screen renders with
     * no player bound at all.
     */
    val crateTrackCount: Int = 0,

    /** The crate's total running time in milliseconds, from the same flow. */
    val crateDurationMs: Long = 0L,
) {

    /** The mirror answered and had nothing. A pulled album that was later removed, usually. */
    val notFound: Boolean get() = !loading && album == null

    /**
     * Whether this album is starred.
     *
     * Read off [Album.isFavourite] rather than from a second flow, because
     * `LibraryRepository.observeAlbum` already joins the favourite table - the
     * mirror is the read path for this as much as for the title - so a separate
     * `observeIsFavourite` subscription would be a second answer to one question
     * and the two could disagree for a frame.
     */
    val isFavourite: Boolean get() = album?.isFavourite == true

    /** Tracks the server never delivered. REQUIREMENTS.md "Partial content is a normal state". */
    val missingTracks: List<AlbumTrack> get() = tracks.filter { !it.available }

    /** True when some of this album arrived and some did not. */
    val isPartiallyDelivered: Boolean
        get() = album?.isOwned == true && missingTracks.isNotEmpty() && missingTracks.size < tracks.size

    /** The index playback should start from for "Play". Skips a missing opening track. */
    val firstPlayableIndex: Int get() = tracks.indexOfFirst { it.available }.coerceAtLeast(0)

    /** True when there is at least one track that can actually be played. */
    val hasPlayableTracks: Boolean get() = tracks.any { it.available }

    /**
     * `19 in the crate · 1 hr 14 min`, or null when the crate is empty.
     *
     * Drawn under the "added" notice rather than kept on screen permanently. Adding to a queue
     * with no visible change is indistinguishable from a tap that did not register, and the two
     * figures REQUIREMENTS.md "Queue" asks the crate screen for - "a count and total duration" -
     * are the proof the session took it. Read live from [crateTrackCount], so the line corrects
     * itself the moment the session confirms, instead of reporting what this screen predicted.
     */
    val crateLine: String? get() = LibraryFormat.crateLine(crateTrackCount, crateDurationMs)

    // ---- the quality tag pair -----------------------------------------------
    // Two tags rather than one format badge, because the header answers two different questions and
    // they are frequently not the same: what is already here, and what a play would fetch. A local
    // copy always wins - `ResolvePlayableSourceUseCase` checks local bytes before any streaming
    // decision - so when an album is downloaded, `Device:` is what is in force and `Server:` is not.

    /**
     * True when this record is fully downloaded, and therefore what plays.
     *
     * **Downloaded, not cached.** [Album.isFullyOnDevice] is `AlbumState.Pinned` with a complete
     * download, so a part-downloaded album and one whose tracks merely happen to be in the listening
     * cache both read as not pulled. A cached copy is evictable under disk pressure, and a tag that
     * promised offline availability the app cannot keep would be worse than no tag.
     */
    val isPulled: Boolean get() = album?.isFullyOnDevice == true

    /** `FLAC`, `MP3 192` - what the server would send now, or null when nothing is known yet. */
    val serverTagValue: String? get() = serverTagLabel(serverFormat, album?.quality)

    /** `FLAC` - the downloaded copy's quality, which is always the source's own. */
    val pulledTagValue: String?
        get() = if (isPulled) LibraryFormat.quality(album?.quality) else null

    /** The Server tag spoken, saying whether it is in force - a chip border is nothing to TalkBack. */
    val serverTagDescription: String?
        get() {
            val value: String = serverTagValue ?: return null
            return when {
                isPulled -> "Server, " + value + ", not in use while this is on the device"
                serverFormat is StreamFormat.Transcoded ->
                    "Streaming " + value + ", re-encoded by the server and not kept on this device"
                else -> "Streaming " + value + ", original quality"
            }
        }

    /** The Pulled tag spoken. */
    val pulledTagDescription: String?
        get() {
            val value: String = pulledTagValue ?: return null
            return "Device, " + value + ", playing from here"
        }

    /**
     * A line saying that streaming this record will not build an offline copy of it, or null.
     *
     * The cache cliff, for one album, where it can be stated as a fact rather than as a conditional:
     * this record's quality is known, so the resolver has already decided whether the server will
     * re-encode it, and REQUIREMENTS.md "Why transcoded bytes are never cached" then guarantees those
     * bytes are discarded. Settings can only say it conditionally - the answer varies record by record -
     * and this is the screen where it does not have to.
     *
     * Absent while the album is pulled: there is already a copy on the device, so nothing is being
     * missed and the line would be a warning about a problem the user has solved.
     */
    val serverCacheNotice: String?
        get() = if (isPulled || serverFormat !is StreamFormat.Transcoded) {
            null
        } else {
            "Your setting has the server re-encode this album, and re-encoded audio is never kept " +
                "on this device. Pull it, or choose Original above, to keep it for offline."
        }

    /**
     * What the primary action is, derived from the one place that knows:
     * [AlbumState.offeredActions].
     */
    val primaryAction: AlbumPrimaryAction
        get() = when (val state: AlbumState? = album?.state) {
            null -> AlbumPrimaryAction.NONE
            AlbumState.NotOwned -> AlbumPrimaryAction.PULL
            is AlbumState.PendingApproval -> AlbumPrimaryAction.WAITING
            is AlbumState.Acquiring -> AlbumPrimaryAction.ACQUIRING
            is AlbumState.Failed -> AlbumPrimaryAction.RETRY
            AlbumState.Owned, is AlbumState.Pinned -> AlbumPrimaryAction.PLAY
        }
}

/**
 * What the **Server** tag shows.
 *
 * [StreamFormat.Original] means the file is sent untouched, so the honest label is the *source's* own
 * quality; the rung is a ceiling and a ceiling nobody reached is not news. A transcode is labelled
 * with what it re-encodes to, which is genuinely different from what the library holds.
 *
 * A private function in this file rather than a member of `LibraryFormat`: that object is shared by
 * every screen in the module and this string belongs to one header.
 */
private fun serverTagLabel(format: StreamFormat?, source: AudioQuality?): String? = when (format) {
    null -> null
    StreamFormat.Original -> LibraryFormat.quality(source)
    is StreamFormat.Transcoded -> codecName(format.codec) + " " + format.maxBitrateKbps
}

/** `MP3`, `Opus` - the codec the resolver names for a URL, spelled the way the project spells it. */
private fun codecName(codec: String): String = when (codec.lowercase()) {
    "mp3" -> "MP3"
    "opus" -> "Opus"
    // A codec this build has no house spelling for. Shown as the server names it rather than dropped:
    // a bitrate with no format beside it is worse than an odd-looking one.
    else -> codec.uppercase()
}

/**
 * A rung as a chip label.
 *
 * `Original` is never labelled FLAC, however much it means FLAC on a lossless library: it means
 * whatever the file already is, and on an MP3 library it yields MP3.
 */
internal fun rungLabel(rung: StreamRung): String = when (rung) {
    StreamRung.ORIGINAL -> "Original"
    StreamRung.OPUS_192 -> "Opus 192"
    StreamRung.OPUS_128 -> "Opus 128"
    StreamRung.OPUS_96 -> "Opus 96"
    StreamRung.MP3_320 -> "MP3 320"
    StreamRung.MP3_256 -> "MP3 256"
    StreamRung.MP3_192 -> "MP3 192"
    StreamRung.MP3_128 -> "MP3 128"
}

/**
 * One row of the track list.
 *
 * [available] is the whole reason this wraps [Track] rather than being one.
 * REQUIREMENTS.md: a part-delivered pull leaves an album that is *in library*
 * with tracks that exist nowhere — "not on the server and not on any device" —
 * so there is no fallback to offer and a list that quietly omitted them would
 * misrepresent what the user owns. They are listed in their right positions,
 * greyed, each with its own retry.
 */
data class AlbumTrack(
    /** Position in the list, 1-based. Used for the number the row draws. */
    val position: Int,
    val track: Track,
    val available: Boolean,
) {
    val key: TrackKey get() = track.key
}

/** The shape of the action area, which is the only thing that differs between screens 04 and 05. */
enum class AlbumPrimaryAction {
    /** Play, Shuffle and Pull to device (screens 04, 11). */
    PLAY,

    /** Pull this album, with the explanatory line (screen 05). */
    PULL,

    /** Waiting for an administrator. No action; the request has already been made. */
    WAITING,

    /** Pulling, with progress and a Cancel. */
    ACQUIRING,

    /** Failed, with an explanation and a Retry. */
    RETRY,

    /** Nothing loaded. */
    NONE,
}

/**
 * What the action block's two playback controls actually do, given what is loaded in the crate.
 *
 * ## Why this exists
 *
 * The primary control used to be a button labelled "Play" that called `playAlbum` unconditionally.
 * Opening the record you were already listening to and pressing it threw the position away and
 * re-buffered from zero - measured on a device at `position=32975` before the tap and `position=0`
 * after it, with the buffer down from 76355 ms to 1906 ms. The label was the symptom; the lost
 * position and the re-fetched bytes were the defect. A control that looks like a transport control
 * has to be one.
 *
 * ## How "the loaded crate is this album" is decided
 *
 * Not by the current track. `PlaybackState.currentItem` belonging to this record answers a
 * different and much weaker question: a crate assembled from a playlist, a genre or a search
 * result can be playing one track of this album while holding nineteen others, and if that counted
 * then every album with a track in the crate would offer Pause and none of them could be played.
 *
 * The test is on the crate as a whole - see `AlbumViewModel.transportFor`: **every**
 * `QueueItem.track.key.releaseGroupMbid` in `PlaybackController.observeQueue` is this release
 * group, and the current item is one of them. Only a crate loaded by this record's own Play or
 * Shuffle satisfies that, which is exactly the case where restarting would be destructive. The
 * crate is deliberately not required to hold *all* of the album: a part-delivered pull queues only
 * the tracks that arrived, and REQUIREMENTS.md "Partial content is a normal state" makes that the
 * ordinary case rather than a corner.
 *
 * The track-row highlight is a separate question and keeps its separate answer: a row is marked as
 * playing whenever the current track is that row, mixed crate or not, because "this is the track
 * you are hearing" is true there regardless of what else is queued.
 *
 * ## Why Shuffle is relabelled rather than disabled or made a toggle
 *
 * Shuffle on an album you are already inside restarts the record in a new order. That is a
 * legitimate thing to want and there is no non-destructive version of it, so the honest fix is to
 * say what it will do before the tap - "Shuffle again" and a spoken label that names the restart -
 * rather than to hide the action.
 *
 * Two alternatives were rejected. Turning it into a `setShuffleEnabled` toggle on the live crate
 * was rejected because `PlaybackController.playAlbum` documents album shuffle as shuffling "the
 * album's own tracks rather than turning on the global shuffle mode for everything that follows":
 * flipping the global mode from an album header would silently change what happens to every crate
 * after this one. Disabling it while this album plays was rejected because it removes a capability
 * the user still has a reason to reach for, and a greyed control with no explanation reads as a
 * bug.
 *
 * ## The labels are part of the contract
 *
 * The words and the spoken descriptions live here rather than in the composable because
 * REQUIREMENTS.md "Accessibility" - "Every control carries a content description" - makes them the
 * thing a TalkBack user acts on, and the device audit found the labels are what people read in
 * preference to the glyph. Holding them beside the state they describe lets a unit test assert the
 * label and the effect together; the pair going out of step is the whole bug this type exists for.
 */
enum class AlbumTransport {

    /**
     * Nothing of this album is loaded: no crate, or a crate holding some other record.
     *
     * Play starts this album from its first playable track, which is what it has always done.
     */
    START,

    /** This album is the loaded crate and it is playing. The primary control pauses it. */
    PAUSE,

    /**
     * This album is the loaded crate and it is paused or stopped.
     *
     * The primary control resumes **where it stopped**. It must not re-issue `playAlbum`, which
     * replaces the crate and seeks to zero.
     */
    RESUME,
    ;

    /** True when this album is the loaded crate, in either transport state. */
    val isLoaded: Boolean get() = this != START

    /** The word on the primary button. */
    val primaryLabel: String
        get() = when (this) {
            START -> "Play"
            PAUSE -> "Pause"
            RESUME -> "Resume"
        }

    /**
     * The primary button spoken, naming the record.
     *
     * [albumLabel] comes from `LibraryFormat.albumLabel`, because `Album.title` can be blank.
     */
    fun primaryDescription(albumLabel: String): String = when (this) {
        START -> "Play " + albumLabel
        PAUSE -> "Pause " + albumLabel
        RESUME -> "Resume " + albumLabel + " where it stopped"
    }

    /** The word on the shuffle button. */
    val shuffleLabel: String get() = if (isLoaded) "Shuffle again" else "Shuffle"

    /**
     * Shuffle spoken.
     *
     * The restart is stated when there is something to restart, because that is the one fact a
     * listener cannot recover from after the tap.
     */
    fun shuffleDescription(albumLabel: String): String = if (isLoaded) {
        "Shuffle " + albumLabel + " again. This starts the album over in a new order"
    } else {
        "Shuffle " + albumLabel
    }
}

/**
 * Something the screen has to tell the user after an action, or instead of one.
 *
 * Each of these is a different outcome with a different next step, which is why
 * they are modelled rather than collapsed into one string: a queued-offline
 * pull will happen by itself, a pending approval needs an administrator, and a
 * download the server forbids cannot be retried at all.
 */
sealed interface AlbumNotice {

    val message: String

    /** True for the ones that are bad news, which the screen tints differently. */
    val isProblem: Boolean get() = false

    /** The pull was accepted and the server is acting on it. */
    data object PullAccepted : AlbumNotice {
        override val message: String
            get() = "Pulling. Track it on the Pulls tab; you will be told when it lands."
    }

    /**
     * The pull needs approval first.
     *
     * REQUIREMENTS.md: "Needler must render the status the server returned
     * rather than inferring it from the cached role, because the role may have
     * changed moments earlier."
     */
    data object PullPendingApproval : AlbumNotice {
        override val message: String
            get() = "Requested. An administrator has to approve this before it is acquired."
    }

    /** Placed with no connection; the write queue will replay it. */
    data object PullQueuedOffline : AlbumNotice {
        override val message: String
            get() = "No connection, so this pull is queued. It is sent as soon as you are back online."
    }

    /** The server already has it. */
    data object AlreadyInLibrary : AlbumNotice {
        override val message: String get() = "This album is already in your library."
    }

    /**
     * Tracks went into the crate.
     *
     * Three outcomes from two controls, and they are modelled rather than collapsed into one
     * sentence because the user cannot see which happened. REQUIREMENTS.md "Queue" has the crate
     * persist and keep playing, so appending to a crate that is playing changes nothing visible on
     * this screen; saying what was added, and where, is the whole feedback.
     *
     * [started] is the case `PlaybackController.enqueue` cannot cover: it "adds tracks to the crate
     * without disturbing what is playing", and with nothing playing that leaves silence and a
     * loaded crate, which reads as a dead button. The ViewModels send `playTracks` instead and say
     * so here. It outranks [playNext]: there is nothing to play next of.
     *
     * The wording is chosen to agree for one track as well as for twenty, so there is one sentence
     * per outcome rather than a singular and a plural of each.
     */
    data class AddedToCrate(
        val trackCount: Int,
        val playNext: Boolean = false,
        val started: Boolean = false,
    ) : AlbumNotice {
        override val message: String
            get() {
                val tracks: String = LibraryFormat.plural(trackCount.toLong(), "track")
                return when {
                    started -> "The crate was empty, so " + tracks + " started playing."
                    playNext -> "Added " + tracks + " to the crate, to play next."
                    else -> "Added " + tracks + " to the crate."
                }
            }
    }

    /** The download to this device started. */
    data class DownloadStarted(val title: String) : AlbumNotice {
        override val message: String get() = "Pulling " + title + " to this device."
    }

    /**
     * Downloading will wait for Wi-Fi.
     *
     * REQUIREMENTS.md "The Wi-Fi-only setting is mislabelled": the setting that
     * actually costs mobile data is downloading to the device, and it defaults
     * to on, so this is the common case on a phone away from home.
     */
    data object DownloadWaitingForWifi : AlbumNotice {
        override val message: String
            get() = "Queued. This album downloads when you are next on Wi-Fi — you can change " +
                "that under Settings, Storage."
    }

    /** The album fits, but only just. */
    data object DownloadWillFillDevice : AlbumNotice {
        override val isProblem: Boolean get() = true
        override val message: String
            get() = "Downloading this album leaves your device low on free space."
    }

    /**
     * The download is gone and the device has the bytes back.
     *
     * The figure is in the sentence rather than carried beside it. REQUIREMENTS.md "Offline and
     * caching" has removal "delete the bytes there and then" and "report how many were freed", and
     * a removal that reports nothing is the line it calls out as making the Storage screen
     * untrustworthy. It is also how the user tells this apart from [DownloadStopped], which is one
     * tap away in the same place and keeps its bytes.
     *
     * A removal that freed nothing says so by saying nothing: a pin whose download never landed is a
     * normal thing to remove, and "freeing 0 B" would be worse than silence.
     */
    data class RemovedFromDevice(val freedBytes: Long?) : AlbumNotice {
        override val message: String
            get() {
                val freed: String? = LibraryFormat.bytes(freedBytes?.takeIf { it > 0L })
                return if (freed == null) {
                    "Removed from this device."
                } else {
                    "Removed from this device, freeing " + freed + "."
                }
            }
    }

    /**
     * The download was stopped and what had arrived is still here.
     *
     * The count is the point of the sentence. REQUIREMENTS.md "The download in flight is a badge,
     * not a banner" settled Stop as a different action from Remove rather than a politer name for
     * it - "stopping leaves what has landed as a part-downloaded pin, which plays" - and on this
     * screen the two occupy the same slot in the action row. A bare "Stopped." would leave the user
     * checking whether they had just deleted the record; saying how much of it still plays answers
     * that before they have to look.
     *
     * The wording agrees for one track as well as for twenty, as [AddedToCrate]'s does, so there is
     * one sentence per outcome rather than a singular and a plural of each.
     */
    data class DownloadStopped(val tracksOnDevice: Int) : AlbumNotice {
        override val message: String
            get() = if (tracksOnDevice > 0) {
                "Stopped. " + LibraryFormat.plural(tracksOnDevice.toLong(), "track") +
                    " kept on this device and still playable."
            } else {
                "Stopped. Nothing had arrived yet, so nothing is kept on this device."
            }
    }

    /** Something went wrong, in whatever words the domain error justified. */
    data class Problem(override val message: String) : AlbumNotice {
        override val isProblem: Boolean get() = true
    }

    companion object {
        /** Maps a request receipt onto what to say about it. */
        fun forRequest(status: RequestStatus): AlbumNotice = when (status) {
            RequestStatus.ACCEPTED -> PullAccepted
            RequestStatus.PENDING_APPROVAL -> PullPendingApproval
            RequestStatus.QUEUED_OFFLINE -> PullQueuedOffline
            RequestStatus.ALREADY_PRESENT -> AlreadyInLibrary
            RequestStatus.REJECTED -> Problem("The server rejected this request.")
        }
    }
}
