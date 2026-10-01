@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.feature.library.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.feature.library.common.hasPlayableFile
import app.needler.feature.library.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives one playlist.
 *
 * ## This screen does refresh, and the list screen does not
 *
 * `getPlaylists` answers with playlists but not their entries — `refreshPlaylists`
 * writes only the playlist rows, and nothing else in the app ever calls
 * `refreshPlaylist`. So the entries of a playlist the user has never opened exist
 * nowhere on the device, and this is the screen that fetches them. It is the same
 * arrangement as album detail, which refreshes an album whose track list may so
 * far only have been a search result.
 *
 * The refresh is not waited for. The mirror is the read path, so the screen is
 * drawn from whatever is already there and fills in when the call lands —
 * REQUIREMENTS.md: the UI never awaits a network call to render. A playlist
 * created offline is not refreshed at all, and the repository says so itself: "A
 * playlist the server has never seen cannot be refreshed from it. This is a
 * success, not an error."
 *
 * ## Edits are local-first and may be queued
 *
 * Every write here applies to the mirror before it is sent and is journalled when
 * it cannot be. The screen therefore never blocks on a server round trip, and the
 * notice says whether the server has been told yet; see [PlaylistNotice] for why
 * that is read from connectivity rather than from the outcome.
 */
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val search: SearchRepository,
    private val sessions: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    /**
     * The playlist this screen is showing.
     *
     * The argument name is [PLAYLIST_ID_ARG], which `:app`'s graph has to agree
     * with. The value is the **bare** playlist id, not the `pl-` prefixed
     * Subsonic one: REQUIREMENTS.md "Identity model" keeps the Subsonic prefixes
     * inside the Subsonic lane, and `PlaylistId` mints the prefixed form when the
     * network layer needs it. A provisional id minted offline (`local-…`) is a
     * legal value here, which is the whole point of it being a real row.
     */
    private val playlistId: PlaylistId =
        PlaylistId(requireNotNull(savedStateHandle.get<String>(PLAYLIST_ID_ARG)) {
            "PlaylistViewModel needs a '" + PLAYLIST_ID_ARG + "' navigation argument"
        })

    private val busy = MutableStateFlow(false)
    private val rename = MutableStateFlow<PlaylistDraft?>(null)
    private val confirmingDelete = MutableStateFlow(false)
    private val notice = MutableStateFlow<PlaylistNotice?>(null)

    /** The picker's query, or null when the picker is closed. A blank string is open-and-empty. */
    private val pickerQuery = MutableStateFlow<String?>(null)
    private val pickerSelection = MutableStateFlow<List<TrackKey>>(emptyList())

    private val nowPlayingKey: Flow<TrackKey?> =
        playback.orElse(null)
            ?.observeState()
            ?.map { playbackState -> playbackState.currentItem?.track?.key }
            ?: flowOf(null)

    private val content: Flow<Content> = combine(
        playlists.observePlaylist(playlistId),
        playlists.observePlaylistEntries(playlistId),
        nowPlayingKey,
        sessions.observeConnectivity(),
    ) { playlist, entries, playingKey, connectivity ->
        Content(
            playlist = playlist,
            entries = entries.toRows(),
            nowPlayingTrackKey = playingKey,
            offline = !connectivity.isOnline,
        )
    }

    /**
     * The picker's search results.
     *
     * `flatMapLatest` is what makes typing behave: the previous query's flow is
     * cancelled the moment a new one arrives, so a slow answer to "su" can never
     * land on top of the answer to "subm". A blank query returns nothing rather
     * than everything — FTS has no defensible answer for one, and a list of the
     * whole library is exactly what [PlaylistTrackPicker] explains this must not be.
     *
     * There is no debounce. REQUIREMENTS.md "Search behaviour" puts the local lane
     * on "the first keystroke, with no network call" and holds it to 50 ms; the
     * 300 ms debounce in that section belongs to the catalogue lane, which this
     * picker does not use.
     */
    private val pickerResults: Flow<List<Track>> = pickerQuery.flatMapLatest { query ->
        if (query == null || query.isBlank()) {
            flowOf(emptyList())
        } else {
            search.searchLocal(query, limit = PICKER_LIMIT).map { it.tracks }
        }
    }

    private val picker: Flow<PlaylistTrackPicker?> = combine(
        pickerQuery,
        pickerResults,
        pickerSelection,
    ) { query, results, selected ->
        query?.let {
            PlaylistTrackPicker(
                query = it,
                // Only tracks with a file behind them can be added: a playlist
                // entry the server cannot resolve to a song is not something the
                // user chose, and `resolveTrackIds` would silently drop it anyway.
                results = results.filter { track -> track.hasPlayableFile },
                selected = selected,
            )
        }
    }

    private val interaction: Flow<Interaction> = combine(
        busy,
        rename,
        confirmingDelete,
        notice,
        picker,
    ) { isBusy, draft, confirming, currentNotice, currentPicker ->
        Interaction(
            busy = isBusy,
            rename = draft,
            confirmingDelete = confirming,
            notice = currentNotice,
            picker = currentPicker,
        )
    }

    val state: StateFlow<PlaylistUiState> = combine(content, interaction) { current, active ->
        PlaylistUiState(
            loading = false,
            playlist = current.playlist,
            entries = current.entries,
            nowPlayingTrackKey = current.nowPlayingTrackKey,
            offline = current.offline,
            busy = active.busy,
            rename = active.rename,
            confirmingDelete = active.confirmingDelete,
            picker = active.picker,
            notice = active.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = PlaylistUiState(),
    )

    init {
        // Fire and forget: the mirror has already drawn the screen, and a failure
        // here is not a failure of the screen. Offline, or for a playlist the
        // server has never seen, this is a no-op by design.
        viewModelScope.launch { playlists.refreshPlaylist(playlistId) }
    }

    // ---- playback -----------------------------------------------------------

    /**
     * Plays the playlist into the crate.
     *
     * Unplayable rows are dropped rather than handed to the player: there is
     * nothing to stream for them anywhere, so a crate containing them would skip
     * through gaps the user cannot see the reason for.
     */
    fun onPlay() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = playableTracks()
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = 0) }
    }

    /**
     * Shuffles the playlist into the crate.
     *
     * The order is shuffled here and handed to the player as a plain list, rather
     * than switching the session's shuffle mode on. `PlaybackController` offers a
     * `shuffle` flag on `playAlbum` and none on `playTracks`, and
     * `setShuffleEnabled` is a *mode*: using it would leave shuffle on for
     * everything the user played afterwards, which is not what pressing Shuffle
     * on one playlist asked for. Album detail's Shuffle has exactly this meaning —
     * "shuffles the album's own tracks rather than turning on the global shuffle
     * mode" — and this is the same promise kept the only way this interface
     * allows.
     */
    fun onShuffle() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = playableTracks().shuffled()
        if (tracks.isEmpty()) return
        viewModelScope.launch { controller.playTracks(tracks, startIndex = 0) }
    }

    /**
     * Plays from one row.
     *
     * The index handed to the player is the row's position among the *playable*
     * tracks, not its position in the playlist: the crate is built from the
     * playable ones only, so an index into the full list would start the wrong
     * song on any playlist with a hole in it.
     */
    fun onPlayTrack(row: PlaylistTrack) {
        if (!row.available) return
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = playableTracks()
        val index: Int = tracks.indexOfFirst { it.key == row.key }.coerceAtLeast(0)
        viewModelScope.launch { controller.playTracks(tracks, startIndex = index) }
    }

    /** Appends the playlist to the crate without disturbing what is playing. */
    fun onAddToCrate() {
        val controller: PlaybackController = playback.orElse(null) ?: return
        val tracks: List<Track> = playableTracks()
        if (tracks.isEmpty()) return
        runExclusively {
            controller.enqueue(tracks, playNext = false)
            PlaylistNotice.AddedToCrate(tracks.size)
        }
    }

    // ---- renaming -----------------------------------------------------------

    fun onRenameClick() {
        if (rename.value != null) return
        rename.value = PlaylistDraft(name = state.value.playlist?.name.orEmpty())
    }

    fun onRenameNameChange(name: String) {
        val current: PlaylistDraft = rename.value ?: return
        if (current.submitting) return
        rename.value = current.copy(name = name)
    }

    fun onRenameCancel() {
        rename.value = null
    }

    fun onRenameConfirm() {
        val current: PlaylistDraft = rename.value ?: return
        if (!current.canSubmit) return
        val name: String = current.trimmedName
        if (name == state.value.playlist?.name) {
            // Nothing changed. Sending a rename that renames nothing would put a
            // pointless entry in the write queue and mark the playlist pending.
            rename.value = null
            return
        }
        rename.value = current.copy(submitting = true)
        runExclusively {
            val queued: Boolean = isOffline()
            val edit = PlaylistEdit.Rename(id = playlistId, name = name)
            when (val result: Outcome<PlaylistId> = playlists.applyEdit(edit)) {
                is Outcome.Success -> {
                    rename.value = null
                    PlaylistNotice.Renamed(name = name, queued = queued)
                }

                is Outcome.Failure -> {
                    rename.value = current.copy(submitting = false)
                    PlaylistNotice.Problem(problemMessage(result.error))
                }
            }
        }
    }

    // ---- editing the contents ----------------------------------------------

    /**
     * Takes one track out of the playlist.
     *
     * Removal is by index, which the protocol chose: `updatePlaylist` takes
     * `songIndexToRemove`. The index passed is the row's own [PlaylistTrack.position],
     * which is its index in the list the screen is showing, and the repository
     * renumbers what is left so the next removal is still correct.
     *
     * A removal made offline can land on the wrong row if the playlist moved on
     * the server in the meantime. That is the conflict REQUIREMENTS.md says the
     * protocol gives no way to detect — "no revision or conflict signal … replay
     * is last-write-wins" — and it is a property of the server interface rather
     * than something this screen can defend against.
     */
    fun onRemoveTrack(row: PlaylistTrack) {
        runExclusively {
            val queued: Boolean = isOffline()
            when (val result: Outcome<Unit> = playlists.removeTracks(playlistId, listOf(row.position))) {
                is Outcome.Success -> PlaylistNotice.TracksRemoved(count = 1, queued = queued)
                is Outcome.Failure -> PlaylistNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- adding tracks ------------------------------------------------------

    fun onAddTracksClick() {
        if (pickerQuery.value != null) return
        pickerSelection.value = emptyList()
        pickerQuery.value = ""
    }

    fun onPickerQueryChange(query: String) {
        if (pickerQuery.value == null) return
        pickerQuery.value = query
    }

    /**
     * Chooses or un-chooses one search result.
     *
     * The selection is a list and an addition goes on the end, so tracks are
     * appended to the playlist in the order they were picked. That is the only
     * order the user can predict, and `updatePlaylist`'s `songIdToAdd` appends in
     * the order it is given.
     */
    fun onToggleCandidate(track: Track) {
        if (pickerQuery.value == null) return
        val key: TrackKey = track.key
        val current: List<TrackKey> = pickerSelection.value
        pickerSelection.value = if (current.contains(key)) current - key else current + key
    }

    fun onPickerCancel() {
        pickerQuery.value = null
        pickerSelection.value = emptyList()
    }

    /**
     * Adds the chosen tracks to the end of the playlist.
     *
     * The same track may already be in the playlist and is added again rather than
     * skipped: a playlist that deliberately plays a song twice is a playlist, and
     * the protocol has no opinion about duplicates. The screen's rows are keyed on
     * position for exactly this reason.
     */
    fun onAddSelected() {
        val keys: List<TrackKey> = pickerSelection.value
        if (keys.isEmpty()) return
        runExclusively {
            val queued: Boolean = isOffline()
            when (val result: Outcome<Unit> = playlists.addTracks(playlistId, keys)) {
                is Outcome.Success -> {
                    pickerQuery.value = null
                    pickerSelection.value = emptyList()
                    PlaylistNotice.TracksAdded(count = keys.size, queued = queued)
                }

                is Outcome.Failure -> PlaylistNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- reordering ---------------------------------------------------------

    /** Moves a row one place towards the top. No-op on the first row. */
    fun onMoveUp(row: PlaylistTrack) {
        move(row, by = -1)
    }

    /** Moves a row one place towards the bottom. No-op on the last row. */
    fun onMoveDown(row: PlaylistTrack) {
        move(row, by = 1)
    }

    // ---- deleting -----------------------------------------------------------

    fun onDeleteClick() {
        confirmingDelete.value = true
    }

    fun onDeleteCancel() {
        confirmingDelete.value = false
    }

    /**
     * Deletes the playlist.
     *
     * [onDeleted] is how the host leaves the screen: the playlist it is showing
     * has just stopped existing, and a detail screen for a deleted row would
     * otherwise sit there showing its "not here" state. Navigation stays the
     * host's business, so this is a callback rather than something the ViewModel
     * does.
     */
    fun onDeleteConfirm(onDeleted: () -> Unit) {
        confirmingDelete.value = false
        val name: String = state.value.playlist?.name ?: ""
        runExclusively {
            val queued: Boolean = isOffline()
            when (val result: Outcome<Unit> = playlists.deletePlaylist(playlistId)) {
                is Outcome.Success -> {
                    onDeleted()
                    PlaylistNotice.Deleted(name = name, queued = queued)
                }

                is Outcome.Failure -> PlaylistNotice.Problem(problemMessage(result.error))
            }
        }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Applies a one-place move as a whole-playlist reorder.
     *
     * [app.needler.core.domain.model.PlaylistEdit.Reorder] is "a full reorder,
     * expressed as the complete desired order", because the protocol has no move:
     * the server is sent `createPlaylist` with the new song list. So the screen
     * computes the order it wants and hands over all of it. Swapping two entries
     * locally and sending only those would be a smaller write the server cannot
     * express.
     *
     * A row with no file behind it can still be moved. It is a real entry of the
     * playlist and its key is what the reorder is expressed in; refusing to move
     * it would strand it wherever a temporarily missing album left it.
     */
    private fun move(row: PlaylistTrack, by: Int) {
        val rows: List<PlaylistTrack> = state.value.entries
        val from: Int = rows.indexOfFirst { it.position == row.position }
        if (from < 0) return
        val to: Int = from + by
        if (to < 0 || to > rows.lastIndex) return
        val reordered: MutableList<PlaylistTrack> = rows.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        val keys: List<TrackKey> = reordered.map { it.key }
        runExclusively {
            val queued: Boolean = isOffline()
            val edit = PlaylistEdit.Reorder(id = playlistId, trackKeys = keys)
            when (val result: Outcome<PlaylistId> = playlists.applyEdit(edit)) {
                is Outcome.Success -> PlaylistNotice.Reordered(queued = queued)
                is Outcome.Failure -> PlaylistNotice.Problem(problemMessage(result.error))
            }
        }
    }

    private fun playableTracks(): List<Track> =
        state.value.entries.filter { it.available }.map { it.track }

    private suspend fun isOffline(): Boolean = !sessions.currentConnectivity().isOnline

    /**
     * Turns mirror rows into list rows.
     *
     * The position is the entry's own, not the loop index: they agree while the
     * mirror's positions are dense, and if they ever disagree the entry's value is
     * the one the server's removal indices are counted in.
     */
    private fun List<PlaylistEntry>.toRows(): List<PlaylistTrack> = map { entry ->
        PlaylistTrack(
            position = entry.position,
            track = entry.track,
            available = entry.track.hasPlayableFile,
        )
    }

    /**
     * Runs one write at a time, disabling the controls while it is in flight.
     *
     * Reordering is the reason this is not optional: two moves in flight at once
     * are two whole-playlist writes built from two different snapshots, and the
     * loser silently undoes the winner.
     */
    private fun runExclusively(block: suspend () -> PlaylistNotice?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                notice.value = block()
            } finally {
                busy.value = false
            }
        }
    }

    private data class Content(
        val playlist: Playlist?,
        val entries: List<PlaylistTrack>,
        val nowPlayingTrackKey: TrackKey?,
        val offline: Boolean,
    )

    private data class Interaction(
        val busy: Boolean,
        val rename: PlaylistDraft?,
        val confirmingDelete: Boolean,
        val notice: PlaylistNotice?,
        val picker: PlaylistTrackPicker?,
    )

    companion object {
        /** The navigation argument this ViewModel reads the playlist id from. */
        const val PLAYLIST_ID_ARG: String = "playlistId"

        /**
         * How many search results the picker shows.
         *
         * `searchLocal`'s own default is 50 across artists, albums and tracks; 60
         * tracks is a screenful and a half on a phone, which is enough to find a
         * song by name and few enough that the FTS query stays inside
         * REQUIREMENTS.md's 50 ms local-search budget.
         */
        private const val PICKER_LIMIT: Int = 60

        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
