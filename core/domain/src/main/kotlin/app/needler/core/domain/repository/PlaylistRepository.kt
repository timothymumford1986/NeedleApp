package app.needler.core.domain.repository

import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.TrackKey
import kotlinx.coroutines.flow.Flow

/**
 * Playlists, read and written through Subsonic only.
 *
 * Reads come from the mirror. Writes apply locally first and then go to the server, or into the write
 * queue when offline: the protocol offers no revision or conflict signal, so replay is last-write-wins
 * and [Playlist.hasPendingLocalEdits] is how the UI admits that.
 */
public interface PlaylistRepository {

    /** All playlists, alphabetical. */
    public fun observePlaylists(): Flow<List<Playlist>>

    public fun observePlaylist(id: PlaylistId): Flow<Playlist?>

    /** The playlist's entries in order. */
    public fun observePlaylistEntries(id: PlaylistId): Flow<List<PlaylistEntry>>

    /**
     * Applies one edit: locally at once, then to the server or to the write queue.
     *
     * Returns the playlist id the edit landed on, which matters for
     * [PlaylistEdit.Create] where the id is assigned by the server - a playlist created offline gets a
     * provisional local id that is rewritten on replay.
     */
    public suspend fun applyEdit(edit: PlaylistEdit): Outcome<PlaylistId>

    /** Convenience wrapper over [applyEdit] for the common "add to playlist" action. */
    public suspend fun addTracks(id: PlaylistId, keys: List<TrackKey>): Outcome<Unit>

    public suspend fun removeTracks(id: PlaylistId, positions: List<Int>): Outcome<Unit>

    public suspend fun createPlaylist(name: String, keys: List<TrackKey> = emptyList()): Outcome<PlaylistId>

    public suspend fun deletePlaylist(id: PlaylistId): Outcome<Unit>

    /** Re-reads playlists from the server into the mirror. Called by sync, not by screens. */
    public suspend fun refreshPlaylists(): Outcome<Unit>

    public suspend fun refreshPlaylist(id: PlaylistId): Outcome<Unit>
}
