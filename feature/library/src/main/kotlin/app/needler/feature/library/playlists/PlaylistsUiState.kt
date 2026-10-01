package app.needler.feature.library.playlists

import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistId
import app.needler.feature.library.common.LibraryFormat

/**
 * Everything the playlists list renders.
 *
 * The design pack draws no playlists screen, so the layout is assembled from the
 * pack's own parts — the screen title from 02, the album row from 13, the
 * section header from 03 and 08, the inline card from 05 — rather than invented.
 *
 * ## Why there is no error field
 *
 * REQUIREMENTS.md "Library browse": "All library browsing reads the local
 * metadata mirror, so it works identically online and offline." Playlists are
 * mirrored like everything else, so there is no request behind this screen to
 * fail: [offline] is a fact the screen states, not a failure it reports, and a
 * write that could not reach the server is journalled rather than lost. What the
 * screen owes the user in that case is [PlaylistSyncState], not an error.
 *
 * @property draft the open create form, or null. It lives in state rather than
 *   in the composable so that the half-typed name survives a rotation, and so a
 *   screenshot can be taken of the form mid-entry.
 * @property pendingDeletion the playlist the user has asked to delete and not
 *   yet confirmed. Deleting is the one action here that destroys something the
 *   user made, and the confirmation is inline rather than a dialog for the
 *   reason given on [PlaylistDraft].
 */
data class PlaylistsUiState(

    /** True until the mirror has answered once. Not "until the server answers"; there is no server call. */
    val loading: Boolean = true,

    /** Alphabetical, as REQUIREMENTS.md "Library browse" orders them. The mirror sorts; this does not. */
    val playlists: List<Playlist> = emptyList(),

    /** The server is unreachable. Browsing and editing are unaffected; the screen says so. */
    val offline: Boolean = false,

    /** A write is in flight, so the controls are disabled rather than tappable twice. */
    val busy: Boolean = false,

    val draft: PlaylistDraft? = null,

    val pendingDeletion: PlaylistId? = null,

    val notice: PlaylistNotice? = null,
) {

    /** The mirror answered and there are no playlists. */
    val showEmptyState: Boolean get() = !loading && playlists.isEmpty()

    /** How many playlists are waiting on the server for anything at all. */
    val pendingCount: Int get() = playlists.count { it.syncState.isPending }

    /** The playlist the delete confirmation is about, resolved for its name. */
    val playlistPendingDeletion: Playlist?
        get() = pendingDeletion?.let { id -> playlists.firstOrNull { it.id == id } }

    /** `6 playlists`, the count beside the screen title. */
    val countLine: String get() = LibraryFormat.plural(playlists.size.toLong(), "playlist")
}

/**
 * A playlist name being typed, for either creating one or renaming one.
 *
 * ## Why this is not a dialog
 *
 * Both forms are drawn inline, as a card in the content, and the text lives in
 * the ViewModel. Three reasons, in order of how much they matter:
 *
 *  1. A Compose `Dialog` is a separate platform window, and a window is not part
 *     of the composition the screenshot harness captures — so every create and
 *     rename state would be invisible to the regression images that are this
 *     module's only test of layout. REQUIREMENTS.md asks that text scale to 200%
 *     without clipping, and a form that cannot be rendered cannot be checked.
 *  2. The design pack has no dialog anywhere in 21 screens. An inline card on
 *     the surface colour is the treatment it does use for something the user has
 *     to read and answer (screens 05, 06).
 *  3. State in the ViewModel survives rotation and process death, which a
 *     `remember`ed field in the composable does not.
 *
 * @property submitting the write has been sent and has not come back. The button
 *   is disabled rather than hidden, so the form does not move under the finger.
 */
data class PlaylistDraft(
    val name: String = "",
    val submitting: Boolean = false,
) {
    /** The name as it would be saved: a playlist called "   " is not a playlist. */
    val trimmedName: String get() = name.trim()

    val canSubmit: Boolean get() = trimmedName.isNotEmpty() && !submitting
}

/**
 * Something the screen has to tell the user after an edit.
 *
 * Each case carries [queued] rather than each having a queued twin, because the
 * two readings of every one of these is the same fact in two conditions and the
 * wording differs by one clause.
 *
 * ## Why the ViewModel decides `queued` from connectivity
 *
 * [app.needler.core.domain.repository.PlaylistRepository] applies every edit
 * locally first and returns `Success` whether the write reached the server or
 * went into the queue — deliberately, because "the user's list must respond to
 * their finger". That means the outcome cannot tell the screen which happened,
 * so the ViewModel reads connectivity at the moment of the edit instead.
 *
 * That is a fair inference and not a guarantee: a write can also be journalled
 * while nominally online, when the call fails with a retryable error. The
 * consequence is the mild one — "sent" said about an edit that was in fact
 * queued, which the pending badge on the row then corrects within one emission —
 * rather than the alarming one. The clean fix is for the repository to report
 * whether it queued, and it is in the handover notes.
 */
sealed interface PlaylistNotice {

    val message: String

    /** True for the ones that are bad news, which the screen tints differently. */
    val isProblem: Boolean get() = false

    /** A playlist was created. */
    data class Created(val name: String, val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = if (queued) {
                name + " is on this device. It is created on the server on your next connection."
            } else {
                name + " created."
            }
    }

    /** A playlist was renamed. */
    data class Renamed(val name: String, val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = if (queued) {
                "Renamed to " + name + " on this device. The server is told on your next connection."
            } else {
                "Renamed to " + name + "."
            }
    }

    /** A playlist was deleted. */
    data class Deleted(val name: String, val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = if (queued) {
                "Deleted " + name + " on this device. The server is told on your next connection."
            } else {
                "Deleted " + name + "."
            }
    }

    /** Tracks were put into a playlist. */
    data class TracksAdded(val count: Int, val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = LibraryFormat.plural(count.toLong(), "track") + " added." +
                if (queued) " The server is told on your next connection." else ""
    }

    /** Tracks were taken out of a playlist. */
    data class TracksRemoved(val count: Int, val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = LibraryFormat.plural(count.toLong(), "track") + " removed." +
                if (queued) " The server is told on your next connection." else ""
    }

    /** The order changed. */
    data class Reordered(val queued: Boolean) : PlaylistNotice {
        override val message: String
            get() = "New order saved." +
                if (queued) " The server is told on your next connection." else ""
    }

    /**
     * Tracks went into the crate.
     *
     * "In the crate" is fixed by REQUIREMENTS.md "Vocabulary" and is the only
     * name this product gives the play queue.
     */
    data class AddedToCrate(val count: Int) : PlaylistNotice {
        override val message: String
            get() = LibraryFormat.plural(count.toLong(), "track") + " added to the crate."
    }

    /** Something went wrong, in whatever words the domain error justified. */
    data class Problem(override val message: String) : PlaylistNotice {
        override val isProblem: Boolean get() = true
    }
}
