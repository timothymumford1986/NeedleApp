package app.needler.feature.library.genres

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the genres list.
 *
 * The shortest ViewModel in the module, and deliberately so: there is one mirror
 * query, one connectivity flow and no action. Genres are read-only — the server
 * offers no way to change them, and REQUIREMENTS.md keeps library management out
 * of v1 entirely.
 *
 * Nothing is refreshed. `LibraryRepository.observeGenres` reads the genre column
 * the album mirror already holds, so the list is a consequence of sync rather than
 * a call of its own; there is no `refreshGenres` on the repository because there
 * is nothing for one to do.
 */
@HiltViewModel
class GenresViewModel @Inject constructor(
    library: LibraryRepository,
    sessions: SessionRepository,
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

    private companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
