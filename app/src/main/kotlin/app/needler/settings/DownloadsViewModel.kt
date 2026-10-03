package app.needler.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.Outcome
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
 * Drives the Downloaded albums screen: the list of what is on this device, and the one action that
 * takes something off it.
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
 * [DownloadsUiState.working] guard stops this screen double-tapping itself, and the repository is the
 * authority on what has already been deleted.
 *
 * ## The order comes out of SQL
 *
 * [DownloadedAlbumOrder.LARGEST_FIRST] is named explicitly even though it is the default, because it
 * is the screen's contract rather than an incidental default - REQUIREMENTS.md "Storage, and why
 * there is no budget" specifies "**Downloaded albums listed by size, largest first**" - and
 * `PinRepository.observeDownloadedAlbums` warns that a page taken in one order and re-sorted in
 * another is a page of the wrong albums. No window is passed: the list has to be complete, since its
 * whole purpose is to answer "what is actually taking up the room".
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
            working = pending.working,
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
     * whole screen untrustworthy". The album is passed whole rather than by MBID so the sentence can
     * name it; an album whose download never landed frees nothing, which is a success and is reported
     * as one rather than as an error.
     *
     * One at a time. The second tap during a removal is dropped rather than queued: the two would
     * finish in either order and each would overwrite the other's notice, so the user would be told
     * about one of the two deletions and left to guess at the other.
     */
    fun onRemove(album: DownloadedAlbum) {
        if (transient.value.working) return
        viewModelScope.launch {
            transient.update { it.copy(working = true, notice = null) }
            val notice: String =
                when (val outcome: Outcome<RemovedDownload> = pins.unpinAlbum(album.releaseGroupMbid)) {
                    is Outcome.Success -> SettingsNotices.removed(album, outcome.value)
                    is Outcome.Failure ->
                        "Could not remove " + album.title + ". " + SettingsNotices.failure(outcome.error)
                }
            transient.update { it.copy(working = false, notice = notice) }
        }
    }

    /** Everything that belongs to this screen's session rather than to the cache index. */
    private data class Transient(
        val working: Boolean = false,
        val notice: String? = null,
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
