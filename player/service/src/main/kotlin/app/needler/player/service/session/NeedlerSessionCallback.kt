package app.needler.player.service.session

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.Track
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.player.service.media.BrowseItems
import app.needler.player.service.media.BrowseRow
import app.needler.player.service.media.BrowseTree
import app.needler.player.service.media.MediaId
import app.needler.player.service.media.SessionRowIds
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
 * ## An item from a browse tree is a folder, not a track
 *
 * Auto sends back whatever id it was shown. Tap an album and the session is handed one item whose id names
 * the album and whose track list is empty; say "play the Blue Nile" and it is handed an item with no id at
 * all and a search query in its request metadata. Both are expanded here, through [BrowseTree], into the
 * tracks they mean, in order - REQUIREMENTS.md "Android Auto" promises a tree that mirrors the app, and an
 * album that enqueues one unplayable row is not that. The expansion is the same for
 * [onAddMediaItems] and [onSetMediaItems] so that "add to queue" and "play now" cannot disagree about what a
 * record contains.
 *
 * Row ids are minted for anything that did not already have one, by [SessionRowIds]. A browse selection is
 * many rows from one id, and rows that share an id are the one thing the crate cannot hold.
 *
 * ## Browse and voice search read the mirror and nothing else
 *
 * [onGetChildren] honours `page` and `pageSize` because a browser cannot be handed a whole library, and
 * [onSearch] and [onGetSearchResult] go to `SearchRepository.searchLocal` rather than the catalogue lane:
 * REQUIREMENTS.md "Android Auto" restricts Auto to owned music, "since pulling while driving makes no sense".
 * [BrowseTree] holds both rules and the reasoning behind them.
 *
 * A voice query is deliberately **not** recorded as a recent search. The app's empty-search state lists things
 * the user typed and meant to keep; a phrase dictated at 70 mph, transcribed by someone else's recogniser, is
 * not that.
 *
 * ## Playback resumption
 *
 * [onPlaybackResumption] is what a Bluetooth play button does when nothing is playing and the app has been
 * killed. It restores the persisted crate, which is the whole reason the crate is persisted.
 */
@OptIn(UnstableApi::class)
public class NeedlerSessionCallback(
    private val catalogue: TrackCatalogue,
    private val browseTree: BrowseTree,
    private val settingsRepository: PlaybackSettingsRepository,
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
    private val rowIds: SessionRowIds = SessionRowIds(),
) : MediaLibrarySession.Callback {

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.mediaFuture {
        expandAll(mediaItems).toMutableList()
    }

    /**
     * The same expansion as [onAddMediaItems], plus the one thing a set-items call adds: where to start.
     *
     * The start index arrives as an index into the list the controller sent, and one of those entries may
     * become twelve, so it is translated through the position each source item took in the expanded list. Get
     * that wrong and choosing track nine of an album plays track one - or, with two albums queued at once,
     * something from the wrong record.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.mediaFuture {
        val expanded: MutableList<MediaItem> = mutableListOf()
        val firstExpandedIndex = IntArray(mediaItems.size)
        mediaItems.forEachIndexed { index, item ->
            firstExpandedIndex[index] = expanded.size
            expanded += expandOne(item)
        }
        if (expanded.isEmpty()) {
            // Nothing resolved, so there is no index to start at either. `INDEX_UNSET` is the session's own
            // "use the default position" value; a literal 0 would be an index into an empty timeline.
            MediaSession.MediaItemsWithStartPosition(expanded, C.INDEX_UNSET, C.TIME_UNSET)
        } else {
            val start: Int = if (startIndex in mediaItems.indices) {
                firstExpandedIndex[startIndex].coerceAtMost(expanded.lastIndex)
            } else {
                // C.INDEX_UNSET, or an index the controller sent that its own list does not have.
                0
            }
            MediaSession.MediaItemsWithStartPosition(expanded, start, startPositionMs)
        }
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
                C.TIME_UNSET,
            )
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Answered without touching the mirror: the root is five fixed nodes, and a car waiting on a Room
        // query before it can draw the first screen is a car that looks broken on connect.
        val root: MediaItem = BrowseItems.mediaItem(browseTree.rootRow())
        return Futures.immediateFuture(LibraryResult.ofItem(root, BrowseItems.rootParams(params)))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.mediaFuture {
        val rows: List<BrowseRow> = browseTree.children(parentId, page, pageSize)
        // The tracks on this page are almost certainly the next thing to be played; remembering them now
        // means the play that follows resolves from memory instead of going back to Room.
        catalogue.remember(rows.mapNotNull(BrowseRow::track))
        LibraryResult.ofItemList(rows.map(BrowseItems::mediaItem), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.mediaFuture {
        val row: BrowseRow? = browseTree.row(mediaId)
        if (row == null) {
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        } else {
            catalogue.remember(listOfNotNull(row.track))
            LibraryResult.ofItem(BrowseItems.mediaItem(row), null)
        }
    }

    /**
     * A search from the car's own search box or its microphone.
     *
     * The contract is two-step: this call tells the browser how many results there are and the browser then
     * asks for them a page at a time. [BrowseTree] keeps the rows from this call so the page that follows is
     * the same list the count described.
     */
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.mediaFuture {
        session.notifySearchResultChanged(browser, query, browseTree.searchRowCount(query), params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.mediaFuture {
        val rows: List<BrowseRow> = browseTree.searchRows(query, page, pageSize)
        catalogue.remember(rows.mapNotNull(BrowseRow::track))
        LibraryResult.ofItemList(rows.map(BrowseItems::mediaItem), params)
    }

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

    private suspend fun expandAll(items: List<MediaItem>): List<MediaItem> =
        items.flatMap { item -> expandOne(item) }

    /**
     * One incoming item, as the rows it should occupy in the crate.
     *
     * Three cases, in the order they are tried:
     *
     *  1. A crate row the app's own controller built. Its id and its metadata are kept and only the URI is
     *     rebuilt, so the crate the user is looking at keeps the ids that screen is addressing rows by.
     *  2. Anything carrying an id - a browse node, a track from a browse list, a stale id from a tab Auto held
     *     across an update. [BrowseTree.tracksFor] answers with the tracks it means, which is one track for a
     *     song, a whole record for an album, and nothing at all for an id this build no longer has.
     *  3. An item with no id and a search query, which is how a spoken "play ..." arrives.
     *
     * An empty answer drops the item rather than enqueueing it. A row that cannot resolve to a track has
     * nothing to play and nothing honest to show, and silently skipping it in the crate is the failure this
     * avoids.
     */
    private suspend fun expandOne(item: MediaItem): List<MediaItem> {
        val isExistingRow: Boolean = MediaId.rowSequenceOf(item.mediaId) != null &&
            MediaId.toTrackKey(item.mediaId) != null
        if (isExistingRow) {
            catalogue.withPlayableUri(item)?.let { rebuilt -> return listOf(rebuilt) }
        }
        val byId: List<Track> =
            if (item.mediaId.isEmpty()) emptyList() else browseTree.tracksFor(item.mediaId)
        val query: String? = item.requestMetadata.searchQuery
        val tracks: List<Track> = when {
            byId.isNotEmpty() -> byId
            query.isNullOrBlank() -> emptyList()
            else -> browseTree.tracksForQuery(query)
        }
        catalogue.remember(tracks)
        return tracks.map { track ->
            catalogue.mediaItemFor(track, rowIds.rowIdFor(item.mediaId, track.key))
        }
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
