@file:OptIn(ExperimentalTime::class)

package app.needler.feature.library.library

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.LibraryFormat
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Everything the Library screen renders — screens 02 (grid), 13 (list) and 09
 * (tablet).
 *
 * The screen is stateless: it is handed one of these plus callbacks, so every
 * state it can be in — loading, empty, offline, list, grid, each tab — can be
 * screenshotted and asserted on without a repository, a database or a server.
 *
 * ## Why there is no "error" field
 *
 * REQUIREMENTS.md: "All library browsing reads the local metadata mirror, so it
 * works identically online and offline." There is no network call behind this
 * screen and therefore no request to fail. [offline] is not an error state — it
 * is a fact about the app that the screen states plainly, so that a library
 * that keeps working with no connection reads as designed rather than as luck.
 */
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.ALBUMS,
    val sort: LibrarySort = LibrarySort.RECENT,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,

    /** True until the mirror has answered once. Not "until the server answers"; there is no server call. */
    val loading: Boolean = true,

    val stats: LibraryStats? = null,
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Track> = emptyList(),

    /** The track the player is on, so its row can mark itself. */
    val nowPlayingTrackKey: TrackKey? = null,

    /** The server is unreachable. Browsing is unaffected; the screen says so rather than hiding it. */
    val offline: Boolean = false,

    /** A delta sync is running, so the header's scan time is about to move. */
    val syncing: Boolean = false,

    /** When the state was assembled, so "last scan 47m ago" is computed from a fixed instant. */
    val renderedAt: Instant = Instant.fromEpochSeconds(0L),
) {

    /** True when the selected tab has nothing in it. */
    val isEmpty: Boolean
        get() = when (tab) {
            LibraryTab.ALBUMS -> albums.isEmpty()
            LibraryTab.ARTISTS -> artists.isEmpty()
            LibraryTab.SONGS -> songs.isEmpty()
        }

    /** The empty state is only honest once the mirror has actually answered. */
    val showEmptyState: Boolean get() = !loading && isEmpty

    /**
     * `176 albums · 42 GB · last scan 47m ago`.
     *
     * Screens 02 and 13 draw the first two parts and screen 09 all three; the
     * line is built from whatever is known, so a mirror that has never seen the
     * server's `library/stats` still renders a truthful count from the local
     * sum.
     */
    val headerLine: String
        get() = LibraryFormat.libraryHeaderLine(
            albumCount = stats?.albumCount,
            totalSizeBytes = stats?.totalSizeBytes,
            lastScanAt = stats?.lastScanAt,
            now = renderedAt,
        )

    /**
     * Whether the figures on the header came from the server or from counting
     * the mirror.
     *
     * REQUIREMENTS.md: "`GET /api/v1/library/stats` gives authoritative totals;
     * the local sum is the offline fallback." The two can disagree, and the
     * screen would rather say which it is showing than quietly present a local
     * estimate as the server's number.
     */
    val statsAreLocal: Boolean get() = stats?.source == StatsSource.LOCAL_MIRROR

    /** The grid/list toggle only applies to albums; artists and songs are always lists. */
    val showsGrid: Boolean get() = tab == LibraryTab.ALBUMS && viewMode == LibraryViewMode.GRID
}

/** The segmented tabs drawn on every library screen. */
enum class LibraryTab(val label: String) {
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
}

/**
 * The sort control, `Recent ⌄` on screens 02, 09 and 13.
 *
 * Every option maps to an [AlbumListKind], which maps to a `getAlbumList2`
 * type this server actually accepts. **There is deliberately no "Top rated".**
 * REQUIREMENTS.md: `getAlbumList2` rejects `highest` for the same reason
 * `setRating` is a no-op — the server holds no rating data — so offering the
 * sort would be offering something that cannot work.
 *
 * @property label what the control reads, which the pack keeps to one word.
 * @property spokenLabel what TalkBack announces. The pack's own `aria-label` is
 *   "Sort: recently added", so the short visible label and the long spoken one
 *   are both drawn from the design rather than invented here.
 */
enum class LibrarySort(
    val label: String,
    val spokenLabel: String,
    val kind: AlbumListKind,
) {
    RECENT("Recent", "recently added", AlbumListKind.NEWEST),
    TITLE("Title", "album title, A to Z", AlbumListKind.ALPHABETICAL_BY_NAME),
    ARTIST("Artist", "artist name, A to Z", AlbumListKind.ALPHABETICAL_BY_ARTIST),
    PLAYED("Played", "most played", AlbumListKind.FREQUENT),
    FAVOURITES("Starred", "recently starred", AlbumListKind.STARRED),
}

/** Grid (screens 02, 09) or list with format badges (screen 13). */
enum class LibraryViewMode {
    GRID,
    LIST,
    ;

    val toggled: LibraryViewMode get() = if (this == GRID) LIST else GRID
}
