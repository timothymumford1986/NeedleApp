// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards. See SettingsUiStateTest for the same note.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.ReleaseGroupMbid
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivations behind the Downloaded albums screen, tested without rendering anything.
 *
 * The one worth having here is the **ordering**. REQUIREMENTS.md "Storage, and why there is no
 * budget" specifies "**Downloaded albums listed by size, largest first**", and that order is the
 * screen's whole argument: it is what makes the list actionable for someone trying to free space.
 * The query orders the rows too, but a contract that can only be checked by standing up a database
 * is a contract that gets checked once - so [DownloadsUiState] sorts as well, and these tests are
 * what make the promise provable from a literal.
 */
class DownloadsUiStateTest {

    // ---- ordering -----------------------------------------------------------

    @Test
    fun `albums are listed largest first, whatever order they arrived in`() {
        val state = DownloadsUiState(
            loading = false,
            downloaded = listOf(
                album("Small", 1_048_576L),
                album("Huge", 4_294_967_296L),
                album("Middling", 536_870_912L),
            ),
        )
        assertEquals(listOf("Huge", "Middling", "Small"), state.albums.map { it.title })
    }

    @Test
    fun `an album whose download never landed sorts last rather than being dropped`() {
        // A pin with nothing on disk is still a row the user can remove, and removing it is the only
        // way to stop the downloader fetching it again. Zero bytes is not absence.
        val state = DownloadsUiState(
            loading = false,
            downloaded = listOf(album("Nothing yet", 0L), album("On disk", 1_048_576L)),
        )
        assertEquals(listOf("On disk", "Nothing yet"), state.albums.map { it.title })
    }

    @Test
    fun `equal sizes keep the order the query gave them`() {
        // The query breaks size ties by title; re-sorting here must not undo that, which is what a
        // stable sort guarantees.
        val state = DownloadsUiState(
            loading = false,
            downloaded = listOf(album("Aaa", 512L), album("Bbb", 512L)),
        )
        assertEquals(listOf("Aaa", "Bbb"), state.albums.map { it.title })
    }

    // ---- the summary --------------------------------------------------------

    @Test
    fun `the summary counts the albums and totals what they occupy`() {
        val state = DownloadsUiState(
            loading = false,
            downloaded = listOf(album("One", 1_073_741_824L), album("Two", 1_073_741_824L)),
        )
        assertEquals("2 albums · 2.0 GB on this device", state.summary)
    }

    @Test
    fun `one album is not pluralised`() {
        val state = DownloadsUiState(loading = false, downloaded = listOf(album("One", 1_048_576L)))
        assertEquals("1 album · 1.0 MB on this device", state.summary)
    }

    @Test
    fun `a device with nothing downloaded says so`() {
        val state = DownloadsUiState(loading = false)
        assertTrue(state.isEmpty)
        assertEquals("Nothing is downloaded to this device.", state.summary)
    }

    @Test
    fun `a screen that has not read the index yet is not an empty device`() {
        // "Unknown is not evidence", which REQUIREMENTS.md says about staleness and is just as true
        // here: announcing "nothing is downloaded" before the answer has arrived would tell a user
        // with a full phone that there is nothing to remove.
        val state = DownloadsUiState()
        assertTrue(state.loading)
        assertFalse(state.isEmpty)
        assertEquals("", state.summary)
    }

    @Test
    fun `the total is the bytes the listed albums account for`() {
        val state = DownloadsUiState(
            loading = false,
            downloaded = listOf(album("One", 3L), album("Two", 4L)),
        )
        assertEquals(7L, state.totalBytes)
    }

    // ---- the remove guard ---------------------------------------------------

    @Test
    fun `a removal in flight stops every remove control responding`() {
        assertFalse(DownloadsUiState(loading = false, working = true).canRemove)
        assertTrue(DownloadsUiState(loading = false).canRemove)
    }

    private fun album(title: String, sizeBytes: Long): DownloadedAlbum = DownloadedAlbum(
        // The title keys the row, so it has to be distinct per album in these fixtures. An MBID is
        // only required to be non-blank, and a readable one makes a failure legible.
        releaseGroupMbid = ReleaseGroupMbid("d2b6bd7d-8d2d-4f33-9a8e-" + title),
        title = title,
        artistName = "The Marías",
        sizeBytes = sizeBytes,
        pinnedAt = Instant.fromEpochSeconds(1_700_000_000L),
    )
}
