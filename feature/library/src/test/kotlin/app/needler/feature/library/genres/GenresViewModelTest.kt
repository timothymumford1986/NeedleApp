package app.needler.feature.library.genres

import app.cash.turbine.test
import app.needler.core.domain.model.ConnectivityState
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.FakeSyncRepository
import app.needler.feature.library.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenresViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sessions = FakeSessions()
    private val sync = FakeSyncRepository()

    private fun viewModel(library: FakeGenreLibrary) = GenresViewModel(
        library = library,
        sessions = sessions,
        sync = sync,
    )

    @Test
    fun `genres are drawn in the order the mirror gave them`() = runTest {
        // REQUIREMENTS.md orders genres alphabetically and
        // `LibraryRepository.observeGenres` is what does it, sorting
        // case-insensitively on the way out. A screen that sorted again would be a
        // second opinion about the same list, and the two would part company the
        // day one of them learned about diacritics.
        val library = FakeGenreLibrary(SampleGenres.all)
        viewModel(library).state.test {
            assertTrue(awaitItem().loading)
            var loaded = awaitItem()
            while (loaded.genres.isEmpty()) loaded = awaitItem()
            assertEquals(
                listOf("Soul", "Dream pop", "Jazz", "Shoegaze"),
                loaded.genres.map { it.name },
            )
            assertEquals("4 genres", loaded.countLine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no genres is an empty state, not a loading one`() = runTest {
        viewModel(FakeGenreLibrary()).state.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.showEmptyState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline is a fact about the app, not a failure of this screen`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        val library = FakeGenreLibrary(SampleGenres.all)
        viewModel(library).state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.genres.isEmpty()) loaded = awaitItem()
            assertTrue(loaded.offline)
            // The list is the same list. Everything here came out of the mirror.
            assertEquals(4, loaded.genres.size)
            assertFalse(loaded.showEmptyState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Every genre row draws a figure, and a counted zero is one of them.
     *
     * `screenshots/genres-phone.png` had a bare gap where Shoegaze's count belongs, against a
     * `genre-empty-phone.png` reading `0 tracks` for the same genre - one emptiness drawn twice,
     * and the blank one reads as a rendering fault. "Unknown is not zero" is about not inventing a
     * figure; `observeGenres` counts, so zero is not invented. The genuinely unknown case, which
     * that query cannot produce, says so in words rather than drawing nothing.
     */
    @Test
    fun `every genre row draws a figure, including a counted zero`() {
        assertEquals("12 albums", genreRowValue(SampleGenres.soul))
        assertEquals("1 album", genreRowValue(SampleGenres.jazz))
        assertEquals("0 albums", genreRowValue(SampleGenres.empty))
        assertEquals(COUNT_UNKNOWN, genreRowValue(SampleGenres.uncounted))
    }

    /** The empty state's one action reaches the repository, forced. */
    @Test
    fun `sync now forces a delta sync`() = runTest {
        viewModel(FakeGenreLibrary(emptyList())).onSyncNow()
        advanceUntilIdle()

        assertEquals(1, sync.deltaSyncCalls)
        assertEquals(true, sync.lastForced)
    }
}
