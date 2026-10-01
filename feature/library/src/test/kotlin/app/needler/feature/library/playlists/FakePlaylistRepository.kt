package app.needler.feature.library.playlists

import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A scriptable [PlaylistRepository].
 *
 * Hand-written rather than mocked, which is the convention `Fakes.kt` set for this
 * module and for `:app` before it. The reason is the same: these screens are driven
 * by flows, and a test is worth far more when it can push a second value down one —
 * a create landing, an edit being journalled, a reorder coming back — than when it
 * can only assert on the first.
 *
 * It is not in `Fakes.kt` because that file is shared with the other screens in the
 * module and this work is not allowed to edit it. When the two meet, this belongs
 * beside the others.
 *
 * **Every edit is recorded, including the ones the interface offers as
 * convenience wrappers.** `addTracks`, `removeTracks`, `createPlaylist` and
 * `deletePlaylist` all funnel into [edits] as the [PlaylistEdit] they stand for,
 * exactly as `DefaultPlaylistRepository` routes them through `applyEdit`. A test
 * can therefore assert on one list whichever door the ViewModel came through.
 */
internal class FakePlaylistRepository : PlaylistRepository {

    val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
    val entriesById = MutableStateFlow<Map<String, List<PlaylistEntry>>>(emptyMap())

    /** Every edit that reached the repository, in order. */
    val edits: MutableList<PlaylistEdit> = mutableListOf()

    val refreshedPlaylists: MutableList<PlaylistId> = mutableListOf()
    var refreshAllCalls: Int = 0

    /** The id a create returns. A create made offline returns a provisional one. */
    var createdId: PlaylistId = PlaylistId("7")
    var createOutcome: Outcome<PlaylistId>? = null
    var editOutcome: Outcome<PlaylistId>? = null
    var deleteOutcome: Outcome<Unit>? = null
    var removeOutcome: Outcome<Unit>? = null

    override fun observePlaylists(limit: Int, offset: Int): Flow<List<Playlist>> =
        playlistsFlow.map { it.drop(offset).take(limit) }

    override fun observePlaylist(id: PlaylistId): Flow<Playlist?> =
        playlistsFlow.map { list -> list.firstOrNull { it.id == id } }

    override fun observePlaylistEntries(id: PlaylistId): Flow<List<PlaylistEntry>> =
        entriesById.map { it[id.value].orEmpty() }

    override suspend fun applyEdit(edit: PlaylistEdit): Outcome<PlaylistId> {
        edits += edit
        editOutcome?.let { return it }
        return when (edit) {
            is PlaylistEdit.Create -> Outcome.Success(createdId)
            is PlaylistEdit.Rename -> Outcome.Success(edit.id)
            is PlaylistEdit.AddTracks -> Outcome.Success(edit.id)
            is PlaylistEdit.RemoveTracks -> Outcome.Success(edit.id)
            is PlaylistEdit.Reorder -> Outcome.Success(edit.id)
            is PlaylistEdit.Delete -> Outcome.Success(edit.id)
        }
    }

    override suspend fun addTracks(id: PlaylistId, keys: List<TrackKey>): Outcome<Unit> {
        edits += PlaylistEdit.AddTracks(id, keys)
        return Outcome.Ok
    }

    override suspend fun removeTracks(id: PlaylistId, positions: List<Int>): Outcome<Unit> {
        edits += PlaylistEdit.RemoveTracks(id, positions)
        return removeOutcome ?: Outcome.Ok
    }

    override suspend fun createPlaylist(name: String, keys: List<TrackKey>): Outcome<PlaylistId> {
        edits += PlaylistEdit.Create(name, keys)
        return createOutcome ?: Outcome.Success(createdId)
    }

    override suspend fun deletePlaylist(id: PlaylistId): Outcome<Unit> {
        edits += PlaylistEdit.Delete(id)
        return deleteOutcome ?: Outcome.Ok
    }

    override suspend fun refreshPlaylists(): Outcome<Unit> {
        refreshAllCalls++
        return Outcome.Ok
    }

    override suspend fun refreshPlaylist(id: PlaylistId): Outcome<Unit> {
        refreshedPlaylists += id
        return Outcome.Ok
    }

}

/**
 * The playlists these tests use.
 *
 * Named after nothing in the design pack, because the pack draws no playlists —
 * unlike `SampleLibrary`, whose albums are typed in from it so a rendered PNG can
 * be compared with `design/png`. What matters here is the three sync states, so
 * there is one playlist in each.
 */
internal object SamplePlaylists {

    fun playlist(
        id: String,
        name: String,
        trackCount: Int = 8,
        durationMs: Long? = 1_681_000L,
        hasPendingLocalEdits: Boolean = false,
    ): Playlist = Playlist(
        id = PlaylistId(id),
        name = name,
        trackCount = trackCount,
        durationMs = durationMs,
        owner = "yourname",
        artwork = ArtworkRef.Owned("pl-" + id),
        hasPendingLocalEdits = hasPendingLocalEdits,
    )

    /** On the server, nothing outstanding. */
    val onServer: Playlist = playlist(id = "7", name = "Sunday morning")

    /** On the server with an edit in the write queue. */
    val withQueuedEdit: Playlist = playlist(
        id = "9",
        name = "Long drive",
        trackCount = 24,
        hasPendingLocalEdits = true,
    )

    /**
     * Created offline: a real row under a locally minted id.
     *
     * `hasPendingLocalEdits` is true as well, because the data layer folds
     * `local_only` into it — which is exactly the conflation `PlaylistSyncState`
     * has to see past.
     */
    val neverSent: Playlist = playlist(
        id = "local-8d2b6bd7",
        name = "Jazz for rain",
        trackCount = 3,
        durationMs = null,
        hasPendingLocalEdits = true,
    )

    /** One playlist's entries, in order, from a list of tracks. */
    fun entries(tracks: List<Track>): List<PlaylistEntry> =
        tracks.mapIndexed { index, track -> PlaylistEntry(position = index, track = track) }
}
