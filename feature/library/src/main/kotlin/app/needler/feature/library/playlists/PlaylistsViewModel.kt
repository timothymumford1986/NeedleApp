package app.needler.feature.library.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.feature.library.common.hasPlayableFile
import app.needler.feature.library.common.problemMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the playlists list.
 *
 * Reads come from the mirror through [PlaylistRepository], so the screen is drawn
 * before any network call could have returned — REQUIREMENTS.md "Offline and
 * caching": "Metadata is fully mirrored, so browsing, search and queueing never
 * wait on the server."
 *
 * ## Nothing is refreshed here
 *
 * [PlaylistRepository.refreshPlaylists] says of itself: "Called by sync, not by
 * screens", and `DefaultSyncRepository` calls it on both the delta and the full
 * pass. A refresh on entering this screen would therefore duplicate work sync
 * has already done, and — worse — would make the list's contents depend on a
 * network call the architecture promises it does not wait for. The playlist
 * *detail* screen does refresh, because `getPlaylists` returns playlists without
 * their entries and nothing else ever fetches them; see [PlaylistViewModel].
 *
 * ## Writes are local-first
 *
 * Every edit applies to the mirror before it is sent, and is journalled when it
 * cannot be sent, so [PlaylistNotice] reports what happened rather than whether
 * the server agreed. REQUIREMENTS.md "Playlists": edits "queue locally and
 * replay on reconnect, last-write-wins".
 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val sessions: SessionRepository,
    private val playback: Optional<PlaybackController>,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val draft = MutableStateFlow<PlaylistDraft?>(null)
    private val pendingDeletion = MutableStateFlow<PlaylistId?>(null)
    private val notice = MutableStateFlow<PlaylistNotice?>(null)

    private val content: Flow<Content> = combine(
        playlists.observePlaylists(),
        sessions.observeConnectivity(),
    ) { rows, connectivity ->
        Content(playlists = rows, offline = !connectivity.isOnline)
    }

    private val interaction: Flow<Interaction> = combine(
        busy,
        draft,
        pendingDeletion,
        notice,
    ) { isBusy, currentDraft, deleting, currentNotice ->
        Interaction(
            busy = isBusy,
            draft = currentDraft,
            pendingDeletion = deleting,
            notice = currentNotice,
        )
    }

    val state: StateFlow<PlaylistsUiState> = combine(content, interaction) { current, active ->
        PlaylistsUiState(
            loading = false,
            playlists = current.playlists,
            offline = current.offline,
            busy = active.busy,
            draft = active.draft,
            pendingDeletion = active.pendingDeletion,
            notice = active.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = PlaylistsUiState(),
    )

    // ---- creating -----------------------------------------------------------

    fun onCreateClick() {
        if (draft.value == null) draft.value = PlaylistDraft()
    }

    fun onDraftNameChange(name: String) {
        val current: PlaylistDraft = draft.value ?: return
        if (current.submitting) return
        draft.value = current.copy(name = name)
    }

    fun onCreateCancel() {
        draft.value = null
    }

    /**
     * Creates the playlist the form names.
     *
     * Empty, and the form simply stays open: the repository would accept a
     * blank name and the user would end up with a row they cannot tell from the
     * next one.
     *
     * The new playlist is created empty rather than with a selection. Tracks are
     * added from an album or a song row, which is where the user is when they
     * decide something belongs in a playlist; a create flow that also asked
     * "which tracks?" would be a second browse inside a text field.
     */
    fun onCreateConfirm() {
        val current: PlaylistDraft = draft.value ?: return
        if (!current.canSubmit) return
        val name: String = current.trimmedName
        draft.value = current.copy(submitting = true)
        runExclusively {
            val queued: Boolean = isOffline()
            when (val result: Outcome<PlaylistId> = playlists.createPlaylist(name)) {
                is Outcome.Success -> {
                    draft.value = null
                    PlaylistNotice.Created(name = name, queued = queued)
                }

                is Outcome.Failure -> {
                    draft.value = current.copy(submitting = false)
                    PlaylistNotice.Problem(problemMessage(result.error))
                }
            }
        }
    }

    // ---- deleting -----------------------------------------------------------

    /**
     * Asks for the delete, which is confirmed before it happens.
     *
     * The only destructive action on this screen, and the one thing here that
     * cannot be undone from the app: a deleted playlist is gone from the mirror
     * at once and from the server on the next replay.
     */
    fun onDeleteRequest(playlist: Playlist) {
        pendingDeletion.value = playlist.id
    }

    fun onDeleteCancel() {
        pendingDeletion.value = null
    }

    fun onDeleteConfirm() {
        val id: PlaylistId = pendingDeletion.value ?: return
        // Resolved against the list rather than through the state's own
        // "pending deletion" projection, which would go null the instant the flag
        // below is cleared.
        val name: String = state.value.playlists.firstOrNull { it.id == id }?.name ?: ""
        pendingDeletion.value = null
        runExclusively {
            val queued: Boolean = isOffline()
            when (val result: Outcome<Unit> = playlists.deletePlaylist(id)) {
                is Outcome.Success -> PlaylistNotice.Deleted(name = name, queued = queued)
                is Outcome.Failure -> PlaylistNotice.Problem(problemMessage(result.error))
            }
        }
    }

    // ---- playback -----------------------------------------------------------

    /**
     * Plays the whole playlist into the crate.
     *
     * The entries are read once from the mirror rather than observed: this is a
     * command, and a command that kept listening would replace the crate every
     * time a later sync touched the playlist.
     *
     * Tracks with no file behind them are dropped. REQUIREMENTS.md "Partial
     * content is a normal state" — a playlist can point at a track of a
     * part-delivered pull, and "there is nothing to stream for them", so handing
     * them to the player would produce a crate with holes that skip.
     */
    fun onPlay(playlist: Playlist) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        runExclusively {
            val tracks: List<Track> = playableTracks(playlist.id)
            if (tracks.isEmpty()) return@runExclusively NOTHING_TO_PLAY
            controller.playTracks(tracks, startIndex = 0)
            null
        }
    }

    /** Appends the playlist to the crate without disturbing what is playing. */
    fun onAddToCrate(playlist: Playlist) {
        val controller: PlaybackController = playback.orElse(null) ?: return
        runExclusively {
            val tracks: List<Track> = playableTracks(playlist.id)
            if (tracks.isEmpty()) return@runExclusively NOTHING_TO_PLAY
            controller.enqueue(tracks, playNext = false)
            PlaylistNotice.AddedToCrate(tracks.size)
        }
    }

    fun onDismissNotice() {
        notice.value = null
    }

    // ---- internals ----------------------------------------------------------

    private suspend fun playableTracks(id: PlaylistId): List<Track> =
        playlists.observePlaylistEntries(id).first()
            .map(PlaylistEntry::track)
            .filter { it.hasPlayableFile }

    private suspend fun isOffline(): Boolean = !sessions.currentConnectivity().isOnline

    /**
     * Runs one action at a time, disabling the controls while it is in flight.
     *
     * Every action here is a write the user can tap twice. Two creates make two
     * playlists with the same name; two deletes race over the same row.
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
        val playlists: List<Playlist>,
        val offline: Boolean,
    )

    private data class Interaction(
        val busy: Boolean,
        val draft: PlaylistDraft?,
        val pendingDeletion: PlaylistId?,
        val notice: PlaylistNotice?,
    )

    private companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L

        private val NOTHING_TO_PLAY: PlaylistNotice = PlaylistNotice.Problem(
            "Nothing in this playlist can be played: none of its tracks has a file on the server.",
        )
    }
}
