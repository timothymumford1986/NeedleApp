package app.needler.wear.local

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.needler.wear.playback.WearCrateRow
import app.needler.wear.playback.WearCrateState
import app.needler.wear.playback.WearPlaybackProtocol
import app.needler.wear.playback.WearPlaybackState
import app.needler.wear.playback.orNullOnFailure
import app.needler.wear.store.WearAudioKey
import app.needler.wear.store.WearAudioStore
import app.needler.wear.store.WearStoredAlbum
import app.needler.wear.store.WearTrackRecord
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The watch's local transport: a `MediaController` bound to [WearLocalPlayerService], rendered through
 * the same state types the phone remote uses.
 *
 * ## Why this does not implement `WearPlaybackClient`
 *
 * It is tempting, because the screens would then work against either source without knowing which. It
 * would also be a lie in that interface's own documentation, which says its flows are "cold: collection
 * registers a data-layer listener and cancellation removes it". A local session is nothing like that: it
 * is a service binding that must outlive one collector, and the session keeps playing when the screen
 * goes dark. So the *state types* are shared - [WearPlaybackState] and [WearCrateState], which is where
 * the real reuse is, because the screens are stateless - and the lifecycle is its own.
 *
 * ## What is deliberately not here
 *
 * No seek, no shuffle, no repeat, no reorder. The same list [app.needler.wear.playback.WearPlaybackClient]
 * leaves out, for the same reason: none of them is drawn on this watch. Position in particular is absent
 * for the reason [WearPlaybackProtocol] records - there is no scrubber on either source, so the two
 * transports look identical, which is the point.
 *
 * ## The rows come from the session, not from a list held here
 *
 * [inTheCrate] is built from the controller's own timeline on every event. A parallel copy of what was
 * queued would drift the moment the session outlived this object - which it is designed to do, since the
 * service keeps playing with the app closed - and the drift would show as a crate that plays the wrong
 * track. The `mediaId` of each item is the track key, so the timeline carries everything the crate needs.
 *
 * ## Threading
 *
 * Media3 requires a `MediaController` to be built and used on its application thread, which is the main
 * looper. Every call here that touches [controller] therefore hops to [Dispatchers.Main]. That is not a
 * detail to remove: a controller called from a background thread throws, and it throws at the call site
 * rather than anywhere informative.
 */
@OptIn(UnstableApi::class)
class WearLocalPlayback(
    context: Context,
    private val store: WearAudioStore,
) {

    private val appContext: Context = context.applicationContext

    private val playbackState: MutableStateFlow<WearPlaybackState> =
        MutableStateFlow(WearPlaybackState.Idle)

    private val crateState: MutableStateFlow<WearCrateState> =
        MutableStateFlow(WearCrateState.Empty)

    /**
     * The local transport.
     *
     * [WearPlaybackState.Idle] rather than `Connecting` before the controller binds, and rather than
     * `PhoneUnreachable` ever: the watch is always reachable from itself, so two of the four states
     * cannot arise here. Idle is the honest answer for "the watch has a store and nothing playing from
     * it", which is what the user sees before they tap an album.
     */
    val state: StateFlow<WearPlaybackState> = playbackState.asStateFlow()

    /** The local crate: the album that is loaded, in record order. */
    val inTheCrate: StateFlow<WearCrateState> = crateState.asStateFlow()

    private val activeState: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * True when the watch's own session has something loaded.
     *
     * This is what decides which source the now-playing screen shows without asking the user to choose:
     * music coming out of the watch's own earbuds wins, because the alternative is a screen offering to
     * pause a phone in another room while the watch plays on.
     */
    val isPlayingLocally: StateFlow<Boolean> = activeState.asStateFlow()

    @Volatile
    private var controller: MediaController? = null

    /** Recomputes everything on every event, which is the only way that cannot drift. */
    private val listener: Player.Listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }
    }

    /**
     * Binds the controller, starting [WearLocalPlayerService] if it is not already running.
     *
     * Starting the service is the cost of this call and the reason the caller must not make it
     * unconditionally: `WearStatePublisher` on the phone records the same hazard - "collecting
     * `observeState()` also *connects* a `MediaController`, which starts the playback service". So
     * `NeedlerWearActivity` connects only when the store has something in it, which is the only
     * circumstance in which local playback is possible at all. A watch with an empty store never binds.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.Main) {
        if (controller != null) return@withContext true
        val token = SessionToken(
            appContext,
            ComponentName(appContext, WearLocalPlayerService::class.java),
        )
        val bound: MediaController? = orNullOnFailure {
            MediaController.Builder(appContext, token).buildAsync().awaitController()
        }
        if (bound == null) {
            // A session that will not bind is not an error to show. The store is still listed, the
            // phone remote still works, and the next connect attempt tries again.
            return@withContext false
        }
        controller = bound
        bound.addListener(listener)
        publish(bound)
        true
    }

    /** Releases the controller. The session keeps playing; only this app's handle on it goes. */
    suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            val current: MediaController = controller ?: return@withContext
            controller = null
            orNullOnFailure {
                current.removeListener(listener)
                current.release()
            }
        }
    }

    /**
     * Plays [album] from [startIndex], from the watch's own store.
     *
     * Only the tracks the store can actually play are queued - see
     * [WearAudioStore.playableFile], which checks the sidecar and the file's length together. A
     * part-transferred album therefore plays the tracks that arrived and silently omits the rest, which
     * is the one honest reading: REQUIREMENTS.md "Partial content is a normal state" keeps the missing
     * tracks visible *in the list* for a part-delivered pull, and the on-watch screen does the same -
     * but a missing track queued into a session is a playback error a second into the album.
     *
     * @return false when nothing in the album is playable, so the screen can say so rather than starting
     *   a session with an empty timeline.
     */
    suspend fun playAlbum(album: WearStoredAlbum, startIndex: Int): Boolean {
        // Built off the main thread: playableFile stats two files per track, and a twenty-track album's
        // worth of that on the main thread is a dropped frame on a watch's single small core.
        val items: List<MediaItem> = withContext(Dispatchers.IO) {
            album.tracks.mapNotNull { record ->
                val file: File = store.playableFile(record.key) ?: return@mapNotNull null
                mediaItemFor(record, file)
            }
        }
        if (items.isEmpty()) return false

        // The index the user tapped counts rows of the album; the queue counts rows that are playable.
        // Translating between the two is what stops a tap on track 9 of a part-transferred album playing
        // whatever happens to be ninth in the queue.
        val tappedKey: String? = album.tracks.getOrNull(startIndex)?.key?.canonical
        val queueIndex: Int = items
            .indexOfFirst { item -> item.mediaId == tappedKey }
            .takeIf { index -> index >= 0 }
            ?: 0

        return withContext(Dispatchers.Main) {
            if (!connect()) return@withContext false
            val current: MediaController = controller ?: return@withContext false
            orNullOnFailure {
                current.setMediaItems(items, queueIndex, 0L)
                current.prepare()
                current.play()
            } ?: return@withContext false
            publish(current)
            true
        }
    }

    suspend fun playPause() {
        onController { current ->
            // The intent, not the target state, exactly as the data-layer command does: the session is
            // the authority on what is playing and this object may be acting on a stale snapshot.
            if (current.isPlaying) current.pause() else current.play()
        }
    }

    suspend fun skipToNext() {
        onController { current -> current.seekToNextMediaItem() }
    }

    /**
     * Previous.
     *
     * `seekToPrevious`, not `seekToPreviousMediaItem`, so the behaviour matches the phone: past a short
     * threshold into a track it restarts the track rather than leaving it. `PlaybackController` on the
     * phone insists that "callers must not implement it themselves, or two surfaces disagree about what
     * the same button does", and a watch whose two sources disagreed with each other would be the same
     * fault inside one app.
     */
    suspend fun skipToPrevious() {
        onController { current -> current.seekToPrevious() }
    }

    /**
     * Jumps to one row of the local crate.
     *
     * By `mediaId` rather than by index, for the reason
     * [WearPlaybackProtocol.PATH_SKIP_TO_ROW] gives: the row the user tapped may have moved by the time
     * the tap lands, and an id that no longer matches anything correctly does nothing.
     */
    suspend fun skipToRow(rowId: String) {
        if (rowId.isEmpty()) return
        onController { current ->
            val index: Int = (0 until current.mediaItemCount).firstOrNull { position ->
                current.getMediaItemAt(position).mediaId == rowId
            } ?: return@onController
            current.seekTo(index, 0L)
            current.play()
        }
    }

    /**
     * The cover the watch holds for [albumKey], or null when it holds none.
     *
     * Null is an ordinary answer and the screen draws its record placeholder, which
     * [app.needler.wear.playback.WearPlaybackClient.loadArtwork] already establishes as "a complete
     * state rather than a loading one". One entry is cached because the watch shows one album at a time.
     */
    suspend fun loadArtwork(albumKey: String): ImageBitmap? {
        decodedCover?.let { cached -> if (decodedCoverKey == albumKey) return cached }
        // The lookup is two file stats and the decode is a decode, so both go off the main thread: the
        // caller is a `produceState` in the composition, which runs on the main dispatcher.
        val bitmap: Bitmap = withContext(Dispatchers.IO) {
            val file: File = store.coverFile(albumKey) ?: return@withContext null
            orNullOnFailure { BitmapFactory.decodeFile(file.absolutePath) }
        } ?: return null
        val image: ImageBitmap = bitmap.asImageBitmap()
        decodedCoverKey = albumKey
        decodedCover = image
        return image
    }

    // ---- internals -------------------------------------------------------------------------------

    @Volatile
    private var decodedCoverKey: String? = null

    @Volatile
    private var decodedCover: ImageBitmap? = null

    private suspend fun onController(block: (MediaController) -> Unit) {
        withContext(Dispatchers.Main) {
            val current: MediaController = controller ?: return@withContext
            orNullOnFailure { block(current) }
            publish(current)
        }
    }

    /**
     * Reads the session and republishes both states.
     *
     * Called on the main thread only - from the listener, which Media3 calls there, and from the
     * suspending functions above, which hop there first.
     */
    private fun publish(player: Player) {
        val rows: List<WearCrateRow> = WearLocalSnapshots.rows(
            count = player.mediaItemCount,
            rowAt = { index -> player.getMediaItemAt(index) },
        )
        val currentIndex: Int = player.currentMediaItemIndex
        playbackState.value = WearLocalSnapshots.nowPlaying(
            rows = rows,
            currentIndex = currentIndex,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            albumTitle = albumTitleOf(player),
        )
        crateState.value = WearCrateState.of(
            rows = rows,
            currentIndex = if (rows.isEmpty()) WearPlaybackProtocol.NO_CURRENT_ROW else currentIndex,
            windowStart = 0,
            // The watch's own crate is never a window: it is one album, all of which is here.
            total = rows.size,
        )
        activeState.value = rows.isNotEmpty()
    }

    private fun albumTitleOf(player: Player): String? {
        val metadata: MediaMetadata? = player.currentMediaItem?.mediaMetadata
        return metadata?.albumTitle?.toString()?.takeIf { title -> title.isNotBlank() }
    }

    private fun mediaItemFor(record: WearTrackRecord, file: File): MediaItem = MediaItem.Builder()
        // The track key, so the crate can address a row by identity rather than by position and the
        // timeline stays readable after this object has gone.
        .setMediaId(record.key.canonical)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(record.title)
                .setArtist(record.artist)
                .setAlbumTitle(record.albumTitle)
                .build(),
        )
        .build()

    /**
     * Awaits a `ListenableFuture` without adding `kotlinx-coroutines-guava`.
     *
     * The same twelve lines `:player:service`'s `MediaFutures.kt` writes, and for the same recorded
     * reason: a new artifact means a new line in `gradle/libs.versions.toml`, "a file whose header
     * records the day and the repository every number was read from". The executor is a lambda rather
     * than Guava's `directExecutor()` so this module names one fewer class it does not declare a
     * dependency on - the `ListenableFuture` type itself is unavoidable, since that is what
     * `buildAsync` returns.
     *
     * Cancelling the coroutine cancels the future, which matters for this one future above all others:
     * `buildAsync` binds to the service, and an abandoned connection attempt that is never cancelled
     * leaves a service binding behind.
     */
    private suspend fun <T> ListenableFuture<T>.awaitController(): T =
        suspendCancellableCoroutine { continuation ->
            val direct = Executor { command -> command.run() }
            addListener(
                {
                    try {
                        continuation.resume(get())
                    } catch (failure: java.util.concurrent.CancellationException) {
                        continuation.cancel(failure)
                    } catch (failure: java.util.concurrent.ExecutionException) {
                        // Resumed with the exception rather than cancelled, and the difference matters:
                        // `orNullOnFailure` rethrows a cancellation and swallows a failure, so cancelling
                        // here would take the caller's coroutine down over a session that would not bind.
                        continuation.resumeWithException(failure.cause ?: failure)
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                },
                direct,
            )
            continuation.invokeOnCancellation { cancel(false) }
        }
}

/**
 * Session timeline to the two states the watch draws.
 *
 * Pure and separate for the reason `WearSnapshots` is separate from `WearStatePublisher` on the phone:
 * [WearLocalPlayback] holds a `MediaController`, which needs a bound service and a device, and
 * everything with a decision in it can be asserted without one. `PlaybackStateMapper` beside
 * `Media3PlaybackController` is the same split in `:player:service`.
 */
@OptIn(UnstableApi::class)
object WearLocalSnapshots {

    /**
     * The timeline as crate rows.
     *
     * @param rowAt reads one item. A lambda rather than a `Player`, which is the whole point: a test can
     *   hand this three `MediaItem`s and assert what comes out, with no session anywhere.
     */
    fun rows(count: Int, rowAt: (Int) -> MediaItem): List<WearCrateRow> {
        if (count <= 0) return emptyList()
        val rows: MutableList<WearCrateRow> = ArrayList(count)
        for (index in 0 until count) {
            val item: MediaItem = rowAt(index)
            val id: String = item.mediaId
            // An item with no usable id is dropped rather than drawn as an untappable line, exactly as
            // the data-layer crate drops one: the id is the only thing that makes a row actionable.
            if (id.isEmpty() || WearAudioKey.parse(id) == null) continue
            rows.add(
                WearCrateRow(
                    id = id,
                    title = item.mediaMetadata.title?.toString() ?: "",
                    artist = item.mediaMetadata.artist?.toString() ?: "",
                ),
            )
        }
        return rows
    }

    /**
     * The now-playing state for a local session.
     *
     * [WearPlaybackState.Idle] when the timeline is empty or the index is outside it, which is the state
     * a session is in before anything is queued and after it has been cleared. There is no
     * `PhoneUnreachable` here and no `Connecting`: a watch can always see itself.
     *
     * The artwork identifier is the album key, taken off the playing row's own track key rather than
     * carried separately. Every track of an album shares it, which is the same rule
     * [WearPlaybackProtocol.KEY_ARTWORK_ID] states for the transport, and it means the cover is decoded
     * once per album rather than once per track.
     */
    fun nowPlaying(
        rows: List<WearCrateRow>,
        currentIndex: Int,
        isPlaying: Boolean,
        isBuffering: Boolean,
        albumTitle: String?,
    ): WearPlaybackState {
        val row: WearCrateRow = rows.getOrNull(currentIndex) ?: return WearPlaybackState.Idle
        return WearPlaybackState.NowPlaying(
            title = row.title,
            artist = row.artist,
            album = albumTitle?.takeIf { title -> title.isNotBlank() },
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            artworkId = WearAudioKey.parse(row.id)?.releaseGroupMbid,
        )
    }
}
