package app.needler.feature.library.genres

import app.cash.turbine.test
import app.needler.core.domain.model.ConnectivityState
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenresViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sessions = FakeSessions()

    private fun viewModel(library: FakeGenreLibrary) = GenresViewModel(
        library = library,
        sessions = sessions,
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

    @Test
    fun `a count is drawn when the mirror has one and nothing at all when it does not`() {
        assertEquals("12 albums", genreRowValue(SampleGenres.soul))
        assertEquals("1 album", genreRowValue(SampleGenres.jazz))
        // Unknown is not zero: "0 albums" would be a claim the mirror never made.
        assertNull(genreRowValue(SampleGenres.uncounted))
    }
}
