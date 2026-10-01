package app.needler.feature.library.playlists

import app.needler.core.domain.model.Playlist

/**
 * How much of a playlist the server has actually seen.
 *
 * REQUIREMENTS.md "Playlists": "Edits made offline queue locally and replay on
 * reconnect, last-write-wins, since the protocol offers no revision or conflict
 * signal." And "Local persistence": "`playlist.local_only` exists because
 * offline playlist creation needs an id before the server has assigned one …
 * Without the flag the UI cannot tell a real playlist from one the server has
 * never seen."
 *
 * So there are three states, not two, and they need different words:
 *
 *  * [ON_SERVER] — the server's copy and this device's copy agree.
 *  * [EDITS_QUEUED] — the playlist exists on the server, and a rename, an
 *    addition, a removal or a reorder is sitting in the write queue.
 *  * [NEVER_SENT] — the playlist was created with no connection. It is a real
 *    row under a locally minted id and it plays, but no server knows about it
 *    yet, and it is re-keyed to the server's id when the queue replays
 *    `createPlaylist`.
 *
 * **None of them is an error**, which is why this is not modelled as a problem
 * notice. REQUIREMENTS.md: "Offline is a first-class state, not an error." A
 * playlist made on a train is a working playlist; what the screen owes the user
 * is the fact that the server has not got it yet, not an apology.
 *
 * ## Why the local id is matched as a string
 *
 * `Playlist` carries no `local_only`. The data layer folds it into
 * [Playlist.hasPendingLocalEdits] — "both that and an unreplayed edit read as
 * pending to the UI" — which is honest but loses the distinction this screen
 * needs: "waiting to be sent" and "the server has never heard of this" are two
 * different sentences, and only one of them survives a failed replay as a row
 * the user could otherwise never account for.
 *
 * The provisional id is minted as [LOCAL_ID_PREFIX] plus a UUID, so the
 * distinction is recoverable from the id alone. Matching a data-layer convention
 * across a module boundary is not a good way to model this, and it is the same
 * compromise `Track.hasPlayableFile` already makes against its own sentinel;
 * both want one field in `:core:domain` instead. The alternative — presenting
 * every pending playlist with the same wording — was rejected because it makes a
 * stranded local-only row indistinguishable from a rename that is one
 * reconnection away from landing. The wanted fix is `Playlist.isLocalOnly`, and
 * it is in the handover notes.
 */
enum class PlaylistSyncState {

    /** The server has this playlist and nothing local is outstanding. */
    ON_SERVER,

    /** On the server, with a local edit still in the write queue. */
    EDITS_QUEUED,

    /** Created on this device with no connection. The server has never seen it. */
    NEVER_SENT,
    ;

    /** True for the two states that owe the user a line of explanation. */
    val isPending: Boolean get() = this != ON_SERVER

    /** The badge label, in the pack's two-or-three-word register. */
    val label: String?
        get() = when (this) {
            ON_SERVER -> null
            EDITS_QUEUED -> "Edit waiting"
            NEVER_SENT -> "Not sent yet"
        }

    /** The sentence under the header, which says what happens next rather than what went wrong. */
    val explanation: String?
        get() = when (this) {
            ON_SERVER -> null
            EDITS_QUEUED ->
                "Changed on this device. The change is sent to the server on your next " +
                    "connection; until then the server's copy has not moved."
            NEVER_SENT ->
                "Created on this device with no connection. It plays now, and it is created on " +
                    "the server on your next connection."
        }

    /** What TalkBack announces for the badge, which has to stand on its own. */
    val spokenLabel: String?
        get() = when (this) {
            ON_SERVER -> null
            EDITS_QUEUED -> "An edit to this playlist is waiting to be sent to the server"
            NEVER_SENT -> "This playlist is on this device only and has not reached the server yet"
        }
}

/**
 * Which of the three states this playlist is in.
 *
 * See [PlaylistSyncState] for why the local-only case is read off the id.
 */
val Playlist.syncState: PlaylistSyncState
    get() = when {
        id.value.startsWith(LOCAL_ID_PREFIX) -> PlaylistSyncState.NEVER_SENT
        hasPendingLocalEdits -> PlaylistSyncState.EDITS_QUEUED
        else -> PlaylistSyncState.ON_SERVER
    }

/**
 * The prefix `:core:data` mints a provisional playlist id with.
 *
 * Duplicated here rather than imported: a feature module cannot see
 * `:core:data`, which is the layering rule REQUIREMENTS.md "Modules" states, and
 * copying four characters is a smaller wrong than breaking it. The constant it
 * mirrors is `DefaultPlaylistRepository.LOCAL_ID_PREFIX`.
 */
internal const val LOCAL_ID_PREFIX: String = "local-"
