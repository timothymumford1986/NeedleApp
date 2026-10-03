@file:OptIn(ExperimentalTime::class)

package app.needler.feature.library.library

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import app.needler.feature.library.album.AlbumNotice
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

    /**
     * What the last add to the crate did, or null.
     *
     * [app.needler.feature.library.album.AlbumNotice] rather than a type of this screen's own,
     * because it is already the module's shared notice type - the artist screen reads the same
     * one - and a second sealed hierarchy saying "added to the crate" in slightly different words
     * is how two screens come to disagree about what happened.
     */
    val notice: AlbumNotice? = null,

    /** How many rows the crate holds, from `PlaybackController.observeQueue`. */
    val crateTrackCount: Int = 0,

    /** The crate's total running time in milliseconds, from the same flow. */
    val crateDurationMs: Long = 0L,

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
     * `19 in the crate · 1 hr 14 min`, or null when the crate is empty.
     *
     * Shown under the line an add leaves behind. Adding to a queue with no visible
     * change is indistinguishable from a tap that did not register, and these are the
     * two figures REQUIREMENTS.md "Queue" asks the crate screen itself to show.
     */
    val crateLine: String? get() = LibraryFormat.crateLine(crateTrackCount, crateDurationMs)

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
 * ## One control, two vocabularies
 *
 * The same four options drive the Albums tab and the Songs tab, and three of
 * them mean measurably different things on each: Title is the record's title in
 * one list and the song's in the other, Artist groups records in one and
 * gathers an artist's whole shelf in the other, Starred reads two different
 * tables. Carrying [kind] and [trackKind] side by side is what keeps that
 * difference stated rather than implied. It is stated here because it was once
 * implied: the Songs tab used to be assembled by flattening the tracks of the
 * first few albums of the *album* list, so choosing Title on it produced one
 * record in track order — a bug that only exists if a songs ordering is allowed
 * to be an album ordering wearing a different hat.
 *
 * ## Why there is no "Played"
 *
 * There was one, and it lied. `Played` mapped to `AlbumListKind.FREQUENT` and
 * `TrackListKind.FREQUENT`, both of which fall through to recently-added in
 * `:core:data` — REQUIREMENTS.md, on the Songs tab: "'Played' cannot be
 * honoured, because play counts are computed server-side and the mirror holds no
 * column reproducing them, so it falls back to recently-added exactly as the
 * album list does." So choosing it produced the order the control had just been
 * showing, under a control that then read "Played". The app told the user it had
 * sorted by something it had not sorted by.
 *
 * That is the failure this codebase refuses elsewhere on principle.
 * `FavouriteButton` explains why star ratings are not offered: `setRating`
 * "would report success and silently lose the user's input, which is the worst
 * failure a control can have". A sort that reports an order it did not apply is
 * the same fault in a different control, and REQUIREMENTS.md "Playlists"
 * resolves that one by removal — "Needler must not offer star ratings" — not by
 * offering a star that does nothing.
 *
 * It was removed rather than disabled or relabelled. Nothing is lost: the order
 * it produced is `Recent`, which is still in the menu and is still the default,
 * so the only thing the user gives up is a word that was not true. A disabled
 * row would be permanent dead space in a four-item menu, because no column that
 * could enable it is coming from a server-side figure; a row reading "Played —
 * unavailable, showing recent" would be honest and would also be the longest
 * label in a menu opened from a one-word pill. The earlier note here claimed
 * "the design pack draws it" as the reason to keep it — the pack draws no sort
 * menu at all, on any of its 21 screens, and no screen in it contains the word.
 *
 * `AlbumListKind.FREQUENT` and `TrackListKind.FREQUENT` stay in `:core:domain`:
 * they are the server's vocabulary, and `getAlbumList2` accepts `frequent`. What
 * changed is that no control offers them.
 *
 * @property label what the control reads, which the pack keeps to one word.
 * @property spokenLabel what TalkBack announces. The pack's own `aria-label` is
 *   "Sort: recently added", so the short visible label and the long spoken one
 *   are both drawn from the design rather than invented here. Title's is
 *   deliberately not "album title": one string is announced on both tabs, and
 *   the unqualified word is true on each of them.
 * @property kind how the Albums tab reads this option.
 * @property trackKind how the Songs tab reads it.
 */
enum class LibrarySort(
    val label: String,
    val spokenLabel: String,
    val kind: AlbumListKind,
    val trackKind: TrackListKind,
) {
    RECENT("Recent", "recently added", AlbumListKind.NEWEST, TrackListKind.NEWEST),
    TITLE(
        "Title",
        "title, A to Z",
        AlbumListKind.ALPHABETICAL_BY_NAME,
        TrackListKind.ALPHABETICAL_BY_TITLE,
    ),
    ARTIST(
        "Artist",
        "artist name, A to Z",
        AlbumListKind.ALPHABETICAL_BY_ARTIST,
        TrackListKind.ALPHABETICAL_BY_ARTIST,
    ),
    FAVOURITES("Starred", "recently starred", AlbumListKind.STARRED, TrackListKind.STARRED),
}

/** Grid (screens 02, 09) or list with format badges (screen 13). */
enum class LibraryViewMode {
    GRID,
    LIST,
    ;

    val toggled: LibraryViewMode get() = if (this == GRID) LIST else GRID
}
