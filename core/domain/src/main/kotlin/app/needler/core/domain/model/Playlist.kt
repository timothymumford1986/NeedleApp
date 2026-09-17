package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * A Subsonic playlist.
 *
 * Playlists are read and written through Subsonic only. The protocol offers no revision or conflict
 * signal, so offline edits queue locally and replay on reconnect as last-write-wins;
 * [hasPendingLocalEdits] lets the UI say so rather than pretending the server already agrees.
 */
public data class Playlist(
    val id: PlaylistId,
    val name: String,
    val trackCount: Int,
    val durationMs: Long? = null,
    val owner: String? = null,
    val isPublic: Boolean = false,
    val comment: String? = null,
    val createdAt: Instant? = null,
    val changedAt: Instant? = null,
    val artwork: ArtworkRef? = null,
    /** True while local, not-yet-replayed mutations exist for this playlist in the write queue. */
    val hasPendingLocalEdits: Boolean = false,
)

/**
 * A single entry of a playlist, in playback order.
 *
 * The entry references a [Track] by its stable [TrackKey]; a playlist row must never be stored against
 * a `file_id`, which changes under quality upgrades.
 */
public data class PlaylistEntry(
    val position: Int,
    val track: Track,
)

/**
 * A pending change to a playlist.
 *
 * Modelled explicitly because these are the operations journalled in the offline write queue, and the
 * queue must replay them in order.
 */
public sealed interface PlaylistEdit {
    /** `createPlaylist` with an initial track list. */
    public data class Create(
        val name: String,
        val trackKeys: List<TrackKey>,
    ) : PlaylistEdit

    /** `updatePlaylist`: rename, re-comment or change visibility. */
    public data class Rename(
        val id: PlaylistId,
        val name: String,
    ) : PlaylistEdit

    /** `updatePlaylist` with songIdToAdd. */
    public data class AddTracks(
        val id: PlaylistId,
        val trackKeys: List<TrackKey>,
    ) : PlaylistEdit

    /** `updatePlaylist` with songIndexToRemove. */
    public data class RemoveTracks(
        val id: PlaylistId,
        val positions: List<Int>,
    ) : PlaylistEdit

    /** A full reorder, expressed as the complete desired order. */
    public data class Reorder(
        val id: PlaylistId,
        val trackKeys: List<TrackKey>,
    ) : PlaylistEdit

    /** `deletePlaylist`. */
    public data class Delete(
        val id: PlaylistId,
    ) : PlaylistEdit
}
