package app.needler.feature.library.playlists

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Track
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerDevice
import app.needler.feature.library.screenshot.NeedlerScreenshots
import app.needler.feature.library.screenshot.TabletFrame
import app.needler.feature.library.screenshot.assertRendered
import app.needler.feature.library.screenshot.captureNeedlerScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders one playlist.
 *
 * Every state that is hard to reach by hand is here: a playlist with holes in it, a
 * playlist the server has never seen, the rename form and the delete confirmation.
 * The last two are why both forms are inline rather than dialogs — a `Dialog` is a
 * separate platform window and would not appear in any of these images at all.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class PlaylistScreenshotTest {

    @Test
    fun `a playlist on the server`() {
        capture("playlist", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        capture("playlist", NeedlerDevice.Tablet, LOADED)
    }

    @Test
    fun `created offline, and honest about it`() {
        capture(
            "playlist-not-sent",
            NeedlerDevice.Phone,
            LOADED.copy(
                playlist = SamplePlaylists.neverSent,
                entries = rows(SampleLibrary.submarineTracks.take(3)),
                offline = true,
            ),
        )
    }

    @Test
    fun `with two tracks that cannot be played`() {
        capture(
            "playlist-holes",
            NeedlerDevice.Phone,
            LOADED.copy(entries = rows(SampleLibrary.submarinePartialTracks)),
        )
    }

    @Test
    fun `the rename form`() {
        capture(
            "playlist-rename",
            NeedlerDevice.Phone,
            LOADED.copy(rename = PlaylistDraft(name = "Sunday morning")),
        )
    }

    @Test
    fun `the delete confirmation`() {
        capture("playlist-delete", NeedlerDevice.Phone, LOADED.copy(confirmingDelete = true))
    }

    @Test
    fun `nothing in it yet`() {
        capture(
            "playlist-empty",
            NeedlerDevice.Phone,
            LOADED.copy(
                playlist = SamplePlaylists.playlist(
                    id = "11",
                    name = "Jazz for rain",
                    trackCount = 0,
                    durationMs = null,
                ),
                entries = emptyList(),
            ),
        )
    }

    @Test
    fun `playing one of its tracks`() {
        capture(
            "playlist-playing",
            NeedlerDevice.Phone,
            LOADED.copy(nowPlayingTrackKey = SampleLibrary.submarineTracks[2].key),
        )
    }

    @Test
    fun `the add-tracks picker, with two songs ticked`() {
        val found = SampleLibrary.blackClassicalTracks.take(5)
        capture(
            "playlist-add-tracks",
            NeedlerDevice.Phone,
            LOADED.copy(
                picker = PlaylistTrackPicker(
                    query = "dayes",
                    results = found,
                    selected = listOf(found[1].key, found[3].key),
                ),
            ),
        )
    }

    @Test
    fun `the picker before anything is typed`() {
        capture(
            "playlist-add-tracks-empty",
            NeedlerDevice.Phone,
            LOADED.copy(picker = PlaylistTrackPicker()),
        )
    }

    @Test
    fun `the picker at 200 percent text size`() {
        val found = SampleLibrary.blackClassicalTracks.take(4)
        capture(
            "playlist-add-tracks-large-text",
            NeedlerDevice.Phone,
            LOADED.copy(
                picker = PlaylistTrackPicker(
                    query = "dayes",
                    results = found,
                    selected = listOf(found[0].key),
                ),
            ),
            fontScale = 2f,
        )
    }

    @Test
    fun `not in the mirror`() {
        capture("playlist-not-found", NeedlerDevice.Phone, PlaylistUiState(loading = false))
    }

    @Test
    fun `loading`() {
        capture("playlist-loading", NeedlerDevice.Phone, PlaylistUiState(loading = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("playlist-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: PlaylistUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            when (device) {
                NeedlerDevice.Phone -> Screen(state, WindowWidthSizeClass.Compact)
                NeedlerDevice.Tablet -> TabletFrame {
                    Screen(state, WindowWidthSizeClass.Expanded)
                }
            }
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: PlaylistUiState, widthSizeClass: WindowWidthSizeClass) {
        PlaylistScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onBack = {},
            onPlay = {},
            onShuffle = {},
            onAddToCrate = {},
            onPlayTrack = {},
            onRemoveTrack = {},
            onMoveUp = {},
            onMoveDown = {},
            onRetryTrack = {},
            onAddTracksClick = {},
            onPickerQueryChange = {},
            onToggleCandidate = {},
            onAddSelected = {},
            onPickerCancel = {},
            onRenameClick = {},
            onRenameNameChange = {},
            onRenameConfirm = {},
            onRenameCancel = {},
            onDeleteClick = {},
            onDeleteConfirm = {},
            onDeleteCancel = {},
            onDismissNotice = {},
        )
    }

    private companion object {

        /** The same mapping the ViewModel does, so the images show real rows. */
        fun rows(tracks: List<Track>): List<PlaylistTrack> =
            tracks.mapIndexed { index, track ->
                PlaylistTrack(
                    position = index,
                    track = track,
                    available = track.fetch.fileId.value != "unavailable",
                )
            }

        val LOADED = PlaylistUiState(
            loading = false,
            playlist = SamplePlaylists.onServer,
            entries = rows(SampleLibrary.submarineTracks),
        )
    }
}
