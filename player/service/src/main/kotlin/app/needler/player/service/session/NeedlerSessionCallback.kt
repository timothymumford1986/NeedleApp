package app.needler.player.service.session

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.player.service.media.MediaId
import app.needler.player.service.media.TrackCatalogue
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope

/**
 * What the session does when a controller - the app, the lock screen, Android Auto, Wear, a Bluetooth
 * headset - asks it for something.
 *
 * ## Rebuilding the URI is not optional
 *
 * `MediaItem.LocalConfiguration`, which holds the URI, is deliberately not bundled across the session
 * boundary: a session must not hand another process a URL that may carry a credential. So an item added by
 * Auto, by Wear, or by a controller in the app's own process after a restart arrives with a media id and
 * nothing to play. [onAddMediaItems] rebuilds `needler://track/...` from the id, and without it the item
 * lands in the crate and is silently skipped - which looks exactly like a corrupt library.
 *
 * ## Playback resumption
 *
 * [onPlaybackResumption] is what a Bluetooth play button does when nothing is playing and the app has been
 * killed. It restores the persisted crate, which is the whole reason the crate is persisted.
 *
 * ## The browse tree is not built
 *
 * Android Auto's tree - Library, Recently added, Playlists, Favourites, On device - and voice search are the
 * next piece of work, not this one. The browse root is answered so that Auto connects and shows an empty
 * library rather than an error, and the children are empty. The `MediaBrowserService` intent filter is in the
 * manifest from the start because retrofitting browse means restructuring playback.
 */
@OptIn(UnstableApi::class)
public class NeedlerSessionCallback(
    private val catalogue: TrackCatalogue,
    private val settingsRepository: PlaybackSettingsRepository,
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val resolved: MutableList<MediaItem> = mediaItems
            .mapNotNull { item -> catalogue.withPlayableUri(item) }
            .toMutableList()
        return Futures.immediateFuture(resolved)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return scope.mediaFuture {
            val queue: PlayQueue = settingsRepository.restorePersistedQueue()
            catalogue.remember(queue.items.map { it.track })
            val items: List<MediaItem> = queue.items.map { row ->
                catalogue.mediaItemFor(row.track, row.id)
            }
            MediaSession.MediaItemsWithStartPosition(
                items,
                queue.currentIndex ?: 0,
                androidx.media3.common.C.TIME_UNSET,
            )
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root: MediaItem = MediaItem.Builder()
            .setMediaId(MediaId.BROWSE_ROOT)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle("Needler")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        // Empty rather than an error: Auto shows "nothing here" instead of "this app is broken", and the tree
        // that fills it is the next piece of work.
        Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return scope.mediaFuture {
            val track = catalogue.resolveOne(mediaId)
            if (track == null) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(catalogue.mediaItemFor(track, MediaId.forTrack(track.key)), null)
            }
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))

    /**
     * Notes a controller's intent to skip, so the transition that follows is heard as a manual skip.
     *
     * Media3 gives a skip-to-next and a scrubber drag the same discontinuity reason, and screen 20 gives them
     * different fades, so the distinction has to be recorded at the point the command arrives.
     */
    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int,
    ): Int {
        if (playerCommand in MANUAL_SKIP_COMMANDS) {
            coordinator.nextTransitionIsManual = true
        }
        return androidx.media3.session.SessionResult.RESULT_SUCCESS
    }

    private companion object {
        /**
         * `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM`, `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, `COMMAND_SEEK_TO_NEXT`,
         * `COMMAND_SEEK_TO_PREVIOUS` and `COMMAND_SEEK_TO_MEDIA_ITEM`: every way a person presses "next".
         */
        val MANUAL_SKIP_COMMANDS: Set<Int> = setOf(
            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
            androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        )
    }
}
