package app.needler.feature.library.playlists

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
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
 * Renders the playlists list.
 *
 * The design pack draws no playlists screen, which makes these images more useful
 * rather than less: they are the only way to see that a screen assembled from the
 * pack's parts still reads as part of the pack. The 200% render is the one that
 * earns its keep — REQUIREMENTS.md "Accessibility" requires text to scale to 200%
 * without clipping, and the inline create form is the piece most likely to fail it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class PlaylistsScreenshotTest {

    @Test
    fun `three playlists, one per sync state`() {
        capture("playlists", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `on a tablet`() {
        capture("playlists", NeedlerDevice.Tablet, LOADED)
    }

    @Test
    fun `offline, where editing still works`() {
        capture("playlists-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `the create form, mid-entry`() {
        capture(
            "playlists-create",
            NeedlerDevice.Phone,
            LOADED.copy(draft = PlaylistDraft(name = "Jazz for rain")),
        )
    }

    @Test
    fun `the delete confirmation`() {
        capture(
            "playlists-delete",
            NeedlerDevice.Phone,
            LOADED.copy(pendingDeletion = SamplePlaylists.onServer.id),
        )
    }

    @Test
    fun `a queued edit reported back`() {
        capture(
            "playlists-notice",
            NeedlerDevice.Phone,
            LOADED.copy(
                notice = PlaylistNotice.Created(name = "Jazz for rain", queued = true),
            ),
        )
    }

    @Test
    fun `nothing yet`() {
        capture(
            "playlists-empty",
            NeedlerDevice.Phone,
            PlaylistsUiState(loading = false),
        )
    }

    @Test
    fun `loading`() {
        capture("playlists-loading", NeedlerDevice.Phone, PlaylistsUiState(loading = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture(
            "playlists-large-text",
            NeedlerDevice.Phone,
            LOADED.copy(draft = PlaylistDraft(name = "Jazz for rain")),
            fontScale = 2f,
        )
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: PlaylistsUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            when (device) {
                NeedlerDevice.Phone -> Screen(state, WindowWidthSizeClass.Compact)
                // The rail and the player sidebar are `:app`'s and
                // `:feature:player`'s, so the tablet render borrows the same
                // placeholder frame the artist and library images use. Without it
                // the content would spread across the whole 1280dp and the PNG
                // could not be compared with the pack's tablet artboard.
                NeedlerDevice.Tablet -> TabletFrame {
                    Screen(state, WindowWidthSizeClass.Expanded)
                }
            }
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: PlaylistsUiState, widthSizeClass: WindowWidthSizeClass) {
        PlaylistsScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onCreateClick = {},
            onDraftNameChange = {},
            onCreateConfirm = {},
            onCreateCancel = {},
            onPlaylistClick = {},
            onPlay = {},
            onAddToCrate = {},
            onDeleteRequest = {},
            onDeleteConfirm = {},
            onDeleteCancel = {},
            onDismissNotice = {},
        )
    }

    private companion object {
        val LOADED = PlaylistsUiState(
            loading = false,
            playlists = listOf(
                SamplePlaylists.onServer,
                SamplePlaylists.withQueuedEdit,
                SamplePlaylists.neverSent,
            ),
        )
    }
}
