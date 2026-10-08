package app.needler.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.repository.DownloadedAlbumOrder
import app.needler.core.domain.repository.PinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Downloaded albums screen: the list of what is on this device, the action that takes
 * something off it, and the one that puts it back.
 *
 * ## Why this is not [SettingsViewModel]
 *
 * It would have been fewer files. `hiltViewModel()` inside a navigation destination scopes to that
 * destination's back-stack entry, so asking it for a [SettingsViewModel] here would **construct a
 * second one** - and that one reads `PackageManager`, subscribes to the session, the sync state, the
 * preference store and the negotiated capabilities, and fires an HTTP refresh of the scrobble targets
 * from its `init`. Every one of those is work this screen has no use for, done again, while the
 * Settings entry it was opened from is still on the stack holding the first instance alive.
 *
 * So this takes the one repository the screen actually needs. `PinRepository` is also the thing that
 * serialises the removal, which is why having two ViewModels able to ask for one is safe: the
 * [DownloadsUiState.canRemove] guard stops this screen double-tapping itself, and the repository is
 * the authority on what has already been deleted.
 *
 * ## The order comes out of SQL
 *
 * [DownloadedAlbumOrder.LARGEST_FIRST] is named explicitly even though it is the default, because it
 * is the screen's contract rather than an incidental default - REQUIREMENTS.md "Storage, and why
 * there is no budget" specifies "**Downloaded albums listed by size, largest first**" - and
 * `PinRepository.observeDownloadedAlbums` warns that a page taken in one order and re-sorted in
 * another is a page of the wrong albums. No window is passed: the list has to be complete, since its
 * whole purpose is to answer "what is actually taking up the room".
 *
 * ## Why the removal sentence is written here and not by SettingsNotices
 *
 * `SettingsNotices.removed` exists so that two screens reporting one deletion cannot disagree about
 * what the app just did, and that argument still holds for the figures: both call `SettingsFormat`,
 * so the bytes and the track count are formatted in one place. What is not shared any more is the
 * **shape**, because the two surfaces are no longer the same shape. Settings draws a line of text
 * under a row; this screen draws a notice with a headline, a figure and an Undo, and folding that
 * into one string put a colon straight after an album title - `Removed Dummy: 11 tracks, 128 MB
 * freed.` - which reads as a label rather than a report and breaks outright on a title that already
 * carries punctuation. [DownloadsNotice] splits it instead.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val pins: PinRepository,
) : ViewModel() {

    private val transient: MutableStateFlow<Transient> = MutableStateFlow(Transient())

    val state: StateFlow<DownloadsUiState> = combine(
        pins.observeDownloadedAlbums(order = DownloadedAlbumOrder.LARGEST_FIRST),
        transient,
    ) { albums: List<DownloadedAlbum>, pending: Transient ->
        DownloadsUiState(
            loading = false,
            downloaded = albums,
            removing = pending.removing,
            undoing = pending.undoing,
            notice = pending.notice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = DownloadsUiState(),
    )

    /**
     * Removes one downloaded album and reports what that actually gave back.
     *
     * REQUIREMENTS.md "Storage, and why there is no budget" is explicit that the figure has to be
     * real: "a 'remove' that leaves the usage figure unchanged is the one thing that would make this
     * whole screen untrustworthy". The album is passed whole rather than by MBID so the notice can
     * name it and so an undo has something to put back; an album whose download never landed frees
     * nothing, which is a success and is reported as one rather than as an error.
     *
     * One at a time. The second tap during a removal is dropped rather than queued: the two would
     * finish in either order and each would overwrite the other's notice, so the user would be told
     * about one of the two deletions and left to guess at the other. The row being deleted is named
     * in [Transient.removing] while that runs, so the screen can mark it rather than greying out the
     * whole list.
     */
    fun onRemove(album: DownloadedAlbum) {
        if (transient.value.removing != null || transient.value.undoing) return
        viewModelScope.launch {
            transient.update { it.copy(removing = album.releaseGroupMbid, notice = null) }
            val notice: DownloadsNotice =
                when (val outcome: Outcome<RemovedDownload> = pins.unpinAlbum(album.releaseGroupMbid)) {
                    is Outcome.Success -> removed(album, outcome.value)
                    is Outcome.Failure -> DownloadsNotice(
                        headline = "Could not remove " + album.title,
                        detail = SettingsNotices.failure(outcome.error),
                    )
                }
            transient.update { it.copy(removing = null, notice = notice) }
        }
    }

    /**
     * Puts a removed album back: pins it again, which starts the download again.
     *
     * Not a rollback, and the copy never claims to be one. The files were deleted when the removal
     * reported what it freed, because REQUIREMENTS.md "Storage, and why there is no budget" leaves
     * this screen as the only lever on a full device and bytes held back for an undo window are bytes
     * the screen promised to free and did not. What this restores is the *pin*, and the server sends
     * the audio again - which is what the screen's own explainer already says is the way back.
     *
     * [DownloadsNotice.undo] is the album as it was listed, so the sentence can name it even though
     * the row has gone.
     */
    fun onUndo(album: DownloadedAlbum) {
        if (transient.value.removing != null || transient.value.undoing) return
        viewModelScope.launch {
            transient.update { it.copy(undoing = true) }
            val notice: DownloadsNotice =
                when (val outcome: Outcome<Unit> = pins.pinAlbum(album.releaseGroupMbid, PinSource.MANUAL)) {
                    is Outcome.Success -> DownloadsNotice(
                        headline = album.title + " is back on the list",
                        detail = "The server is sending it to this device again.",
                    )

                    is Outcome.Failure -> DownloadsNotice(
                        headline = "Could not put " + album.title + " back",
                        detail = SettingsNotices.failure(outcome.error),
                        // Still offered: the pin was not written, so the album is still gone and
                        // trying again is the only thing the user can do from here.
                        undo = album,
                    )
                }
            transient.update { it.copy(undoing = false, notice = notice) }
        }
    }

    /**
     * Clears the notice.
     *
     * It exists because the notice now carries an offer. A line of text that only reports can sit
     * until the next action replaces it; an **Undo** that stays on screen after the user has decided
     * against it is a button waiting to be hit by accident, and on this screen that button starts a
     * download.
     */
    fun onDismissNotice() {
        transient.update { it.copy(notice = null) }
    }

    /**
     * What one finished removal says.
     *
     * The zero case is a success and is worded as one. A pin whose download never landed frees
     * nothing, and `PinRepository.unpinAlbum` says so outright: "A pin whose download never landed
     * removes nothing and reports zero, which is a success, not an error." It is not marked
     * destructive either - nothing left the device - which is what keeps the one red thing on this
     * screen meaning "bytes are gone".
     */
    private fun removed(album: DownloadedAlbum, removal: RemovedDownload): DownloadsNotice =
        if (removal.removedTracks == 0) {
            DownloadsNotice(
                headline = "Removed " + album.title,
                detail = "None of it had reached this device, so nothing was freed.",
                undo = album,
            )
        } else {
            DownloadsNotice(
                headline = "Removed " + album.title,
                detail = SettingsFormat.plural(removal.removedTracks.toLong(), "track") +
                    " deleted, " + SettingsFormat.bytes(removal.freedBytes) + " freed.",
                undo = album,
                destructive = true,
            )
        }

    /** Everything that belongs to this screen's session rather than to the cache index. */
    private data class Transient(
        val removing: ReleaseGroupMbid? = null,
        val undoing: Boolean = false,
        val notice: DownloadsNotice? = null,
    )

    private companion object {
        /**
         * Keep the cache-index query alive briefly after the last subscriber leaves, so a rotation
         * does not re-measure every downloaded album on disk. The same figure [SettingsViewModel]
         * uses, for the same reason.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
