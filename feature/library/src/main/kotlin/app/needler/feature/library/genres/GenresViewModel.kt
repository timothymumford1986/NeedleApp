package app.needler.feature.library.genres

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the genres list.
 *
 * Nearly the shortest ViewModel in the module: one mirror query, one connectivity flow, and one
 * action. Genres themselves are read-only — the server offers no way to change them, and
 * REQUIREMENTS.md keeps library management out of v1 entirely.
 *
 * Nothing is refreshed. `LibraryRepository.observeGenres` reads the genre column
 * the album mirror already holds, so the list is a consequence of sync rather than
 * a call of its own; there is no `refreshGenres` on the repository because there
 * is nothing for one to do.
 *
 * ## Which is exactly why [onSyncNow] exists
 *
 * A genre appears here only once the album carrying it has been synced, so the one thing that can
 * turn an empty genres list into a full one is a library sync — and the empty state named that
 * cause while offering nothing to act on it with. The action is `deltaSync(force = true)`, the same
 * call `LibraryViewModel.onSyncNow` makes, so the two buttons cannot come to mean different things.
 */
@HiltViewModel
class GenresViewModel @Inject constructor(
    library: LibraryRepository,
    sessions: SessionRepository,
    private val sync: SyncRepository,
) : ViewModel() {

    val state: StateFlow<GenresUiState> = combine(
        library.observeGenres(),
        sessions.observeConnectivity(),
    ) { genres, connectivity ->
        GenresUiState(
            loading = false,
            genres = genres,
            offline = !connectivity.isOnline,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = GenresUiState(),
    )

    /** The empty state's only action. Forces a delta sync, as the library screen's does. */
    fun onSyncNow() {
        viewModelScope.launch { sync.deltaSync(force = true) }
    }

    private companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
