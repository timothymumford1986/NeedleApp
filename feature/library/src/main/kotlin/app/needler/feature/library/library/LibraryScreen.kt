@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.CRATE_LONG_PRESS_LABEL
import app.needler.core.design.component.NeedlerAlbumGridCell
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.component.NeedlerChevronRightIcon
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.NeedlerDropdownMenu
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSearchFieldButton
import app.needler.core.design.component.NeedlerSegmentedTabs
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerToolbarIconPill
import app.needler.core.design.component.NeedlerToolbarPill
import app.needler.core.design.component.NeedlerTrackRow
import app.needler.core.design.component.PathClose
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.needlerRowActions
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.AlbumFormatLabel
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.albumFormatSpokenLabel
import app.needler.feature.library.common.hasPlayableFile
import app.needler.feature.library.common.showsOnDeviceCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Library screen: screens 02 (album grid), 13 (list with format badges) and
 * 09 (tablet, four columns).
 *
 * One composable serves all three. The differences are a column count and a
 * header that puts the search field beside the title instead of under it —
 * REQUIREMENTS.md "Tablet layout": "This is one navigation model at two widths,
 * driven by `WindowSizeClass`. Nothing is tablet-only, so no feature needs
 * building twice."
 *
 * The nav rail, the permanent player sidebar and the bottom bar are **not**
 * drawn here. They belong to `:app`'s navigation scaffold, which hosts this
 * screen in its content pane; drawing them again inside the feature would give
 * the app two of each.
 *
 * ## Offline is drawn, not assumed
 *
 * Everything on this screen comes out of the local mirror, so it renders
 * identically with and without a connection. REQUIREMENTS.md asks for that to
 * be visible rather than accidental, which is why [LibraryUiState.offline]
 * produces a plain line saying the library is on the device and plays without a
 * connection, instead of an error, a spinner, or nothing at all.
 *
 * ## The grid and the list are one place in the library
 *
 * [scrollAnchor] is the user's place in the album list, carried across the
 * grid/list toggle. It has to live here rather than in either layout, because
 * the toggle disposes one layout and composes the other and only their common
 * parent outlives both. [AlbumScrollAnchor] says why it holds an album rather
 * than a scroll offset, and what was rejected.
 *
 * @param scrollAnchor the album the album layouts open on. It is a parameter
 *   with a default rather than a private `remember` so that a screenshot test
 *   can render the library already scrolled — which is the only way to see,
 *   rather than assert, that the grid and the list open on the same record.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    widthSizeClass: WindowWidthSizeClass,
    onTabSelect: (LibraryTab) -> Unit,
    onSortSelect: (LibrarySort) -> Unit,
    onViewModeToggle: () -> Unit,
    onSearchClick: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenGenres: () -> Unit,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onAlbumPlay: (ReleaseGroupMbid) -> Unit,
    onArtistClick: (ArtistMbid) -> Unit,
    onSongPlay: (Track) -> Unit,
    onAlbumAddToCrate: (ReleaseGroupMbid, Boolean) -> Unit,
    onSongAddToCrate: (Track, Boolean) -> Unit,
    onDismissNotice: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
    scrollAnchor: AlbumScrollAnchor = remember { AlbumScrollAnchor() },
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = gutter,
                end = gutter,
                top = if (wide) spacing.step12 else spacing.step14,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
        ) {
            LibraryHeader(
                state = state,
                wide = wide,
                onSearchClick = onSearchClick,
            )
            LibraryControls(
                state = state,
                onTabSelect = onTabSelect,
                onSortSelect = onSortSelect,
                onViewModeToggle = onViewModeToggle,
            )
            LibraryBrowseRow(
                onOpenPlaylists = onOpenPlaylists,
                onOpenGenres = onOpenGenres,
            )
            if (state.offline) OfflineNote()
            // Under the controls rather than over the list: it is the answer to a tap that
            // happened in the list, and a card that pushed the rows down would move the row
            // the user is still looking at.
            state.notice?.let { notice ->
                CrateNoticeCard(
                    message = notice.message,
                    detail = state.crateLine,
                    onDismiss = onDismissNotice,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.step9))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> LibrarySkeleton(
                    grid = state.showsGrid,
                    columns = columnsFor(widthSizeClass),
                    gutter = gutter,
                )

                state.showEmptyState -> LibraryEmptyState(
                    tab = state.tab,
                    offline = state.offline,
                    onSyncNow = onSyncNow,
                    gutter = gutter,
                )

                state.tab == LibraryTab.ALBUMS && state.viewMode == LibraryViewMode.GRID ->
                    AlbumGrid(
                        albums = state.albums,
                        columns = columnsFor(widthSizeClass),
                        gutter = gutter,
                        scrollAnchor = scrollAnchor,
                        onAlbumClick = onAlbumClick,
                        onAlbumPlay = onAlbumPlay,
                    )

                state.tab == LibraryTab.ALBUMS -> AlbumList(
                    albums = state.albums,
                    gutter = gutter,
                    scrollAnchor = scrollAnchor,
                    onAlbumClick = onAlbumClick,
                    onAlbumPlay = onAlbumPlay,
                    onAlbumAddToCrate = onAlbumAddToCrate,
                )

                state.tab == LibraryTab.ARTISTS -> ArtistList(
                    artists = state.artists,
                    gutter = gutter,
                    onArtistClick = onArtistClick,
                )

                else -> SongList(
                    songs = state.songs,
                    nowPlayingTrackKey = state.nowPlayingTrackKey,
                    gutter = gutter,
                    onSongPlay = onSongPlay,
                    onSongAddToCrate = onSongAddToCrate,
                )
            }
        }
    }
}

/**
 * Columns: two on a phone, four on a tablet.
 *
 * REQUIREMENTS.md "Tablet layout" names both numbers. Medium — a foldable open,
 * a tablet in portrait — gets three, because four cells across a 600dp pane
 * would be smaller than the phone's and the grid would stop reading as
 * artwork.
 */
private fun columnsFor(widthSizeClass: WindowWidthSizeClass): Int = when (widthSizeClass) {
    WindowWidthSizeClass.Expanded -> 4
    WindowWidthSizeClass.Medium -> 3
    else -> 2
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    wide: Boolean,
    onSearchClick: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    val title: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.step2)) {
            Text(
                text = TITLE,
                style = typography.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            // Nothing at all is better than an empty line: before the first
            // sync there is no count, no size and no scan time to report.
            if (state.headerLine.isNotBlank()) {
                Text(
                    // The counts change under the user as a sync lands, and a
                    // screen reader that has just been told "176 albums" should
                    // hear the new figure rather than keep the old one.
                    text = state.headerLine,
                    style = typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = state.headerLine.replace(" · ", ", ")
                    },
                )
            }
        }
    }

    if (wide) {
        // Screen 09: title on the left, search field on the right of the same
        // row, both above the controls.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.step16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { title() }
            NeedlerSearchFieldButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 560.dp),
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sectionGap)) {
            title()
            NeedlerSearchFieldButton(
                onClick = onSearchClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Controls: tabs, sort, grid/list toggle
// ---------------------------------------------------------------------------

/**
 * The control row: segmented tabs, and the two controls that apply to the tab in front of them.
 *
 * ## The sort control is hidden on Artists, not disabled
 *
 * The Artists tab has exactly one order and the sort control could never change it.
 * `LibraryViewModel` calls `LibraryRepository.observeArtists()` for that tab and passes it no
 * ordering at all - the repository has none to take, and `ArtistDao.observeArtists` is a single
 * `ORDER BY sort_name_normalised ASC`. REQUIREMENTS.md "Library browse" fixes it there too: Artists
 * is "Alphabetical", with no alternative named, which is why the query has no parameter.
 *
 * So the pill was reporting an order the tab was not in. Switch to Artists with Recent selected and
 * the control read "Recent" over a list sorted by name. That is the fault [LibrarySort]'s own notes
 * describe as the reason "Played" was removed - "a sort that reports an order it did not apply" -
 * and the resolution there was removal rather than a disabled row offering a word that is not true.
 *
 * Hidden rather than disabled, for the reason that note gives: a disabled control is permanent dead
 * space, and nothing is coming that could enable this one. The grid/list toggle beside it already
 * disappears on this tab on the same grounds, so the row has the precedent as well as the argument.
 * Nothing is lost by hiding it - the selection is held in the view model and is still in force on
 * Albums and Songs when the user tabs back.
 *
 * **The alternative, rejected:** make `observeArtists` take an ordering, so the control means
 * something. It is a change to `:core:domain` and `:core:data` to add orders REQUIREMENTS.md does
 * not ask for, in service of a control that happens to be drawn nearby. The index strip above the
 * artist list is what that scrolling cost actually wanted, and it is only meaningful *because* the
 * order is fixed: an alphabet jump over a list sorted by anything else points at nothing.
 */
@Composable
private fun LibraryControls(
    state: LibraryUiState,
    onTabSelect: (LibraryTab) -> Unit,
    onSortSelect: (LibrarySort) -> Unit,
    onViewModeToggle: () -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        // Top, not centre. At 100% every control is one 48dp line and the two
        // agree; at 200% the FlowRow beside this is two lines tall, and
        // centring would float the toggle in the gap between them, level with
        // nothing. Top keeps it on the tabs' line, which is the line it belongs
        // to — the pack puts the three controls side by side.
        verticalAlignment = Alignment.Top,
    ) {
        // FlowRow rather than Row: at 200% text scale the tabs and the sort
        // control no longer fit across a 390dp phone, and wrapping to a second
        // line is the only way the whole control stays operable.
        // REQUIREMENTS.md "Accessibility": "Text must scale to 200% without
        // clipping".
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
            verticalArrangement = Arrangement.spacedBy(spacing.step4),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // At 200% text the three tabs are wider than a 390dp phone, and
            // `NeedlerSegmentedTabs` is a fixed Row that would simply clip
            // "Songs" in half. Scrolling keeps every option reachable — by
            // touch and by TalkBack — without changing how the control looks
            // at normal sizes, where it never overflows. The component itself
            // wants to handle this; see the handover notes.
            NeedlerSegmentedTabs(
                options = LibraryTab.entries.map { it.label },
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> onTabSelect(LibraryTab.entries[index]) },
                label = "Browse by",
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            if (state.tab != LibraryTab.ARTISTS) {
                SortControl(sort = state.sort, onSortSelect = onSortSelect)
            }
        }
        if (state.tab == LibraryTab.ALBUMS) {
            ViewModeToggle(viewMode = state.viewMode, onToggle = onViewModeToggle)
        }
    }
}

/**
 * Playlists and Genres: the browse screens that are not albums, artists or songs.
 *
 * ## Why they are here at all
 *
 * They were reachable from nowhere. `NeedlerNavHost` registers `playlists` and
 * `genres`, and the only navigation into either came from inside those screens,
 * so four finished screens — Playlists, one playlist, Genres, one genre — could
 * not be opened by any sequence of taps. Every test exercised the screens
 * themselves and none asserted that anyone could get to them, which is how that
 * survived; [LibraryControlsTest] now asserts the entry point instead.
 *
 * ## Why not two more segments
 *
 * Because they are not peers of Albums, Artists and Songs. REQUIREMENTS.md
 * states the app's own browse shape where it describes the Auto tree, which
 * "mirrors the app": "Library with albums, artists and songs; Recently added;
 * Playlists; Favourites; Device." Albums, artists and songs are one thing, and
 * playlists sit beside that thing rather than inside it. The tablet structure
 * agrees — "segmented tabs, a sort control and a grid/list toggle", four items
 * on that row, not six — and a five-segment control is also the one that stops
 * fitting a 390dp phone first, which is the pressure the row was already under.
 *
 * ## Why a pill with a right chevron
 *
 * The pack draws no playlists or genres screen on any of its 21 artboards, so
 * there is no drawn entry point to transcribe and the idiom was chosen. It is
 * [NeedlerToolbarPill], the same control the sort wears, because a third pill
 * shape on one screen is the defect this screen was just fixed for. What
 * separates it from its neighbours is the chevron, and the pack has exactly the
 * two glyphs needed: the sort carries `PathChevronDown`, which the design
 * system calls "the disclosure chevron on a sort or output control" — a menu
 * drops here — and these carry `PathChevronRight`, "the row chevron" — a screen
 * opens there. The tabs carry neither, because they change this screen.
 *
 * **The alternatives, rejected.** A fifth and sixth segment, for the reason
 * above. A nav-rail or bottom-bar entry, because screen 09 fixes the rail at
 * Library, Search, Pulls and Settings, and a phone-only tab that vanishes on a
 * tablet is worse than none — this row is inside the content pane, so it is
 * identical at both widths. Full-width rows in the pack's list style, because
 * two of them cost 96dp of a phone's first screen where two pills cost 48dp.
 *
 * The spoken label says the action rather than the noun: "Open playlists"
 * rather than "Playlists", so a TalkBack user hears that this opens something
 * instead of hearing the same word the tabs use. REQUIREMENTS.md
 * "Accessibility" asks for a content description on every control, and a
 * description that is only the visible word adds nothing.
 */
@Composable
private fun LibraryBrowseRow(
    onOpenPlaylists: () -> Unit,
    onOpenGenres: () -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        verticalArrangement = Arrangement.spacedBy(spacing.step4),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerToolbarPill(
            text = "Playlists",
            contentDescription = "Open playlists",
            onClick = onOpenPlaylists,
            trailingIcon = { tint -> NeedlerChevronRightIcon(tint = tint) },
        )
        NeedlerToolbarPill(
            text = "Genres",
            contentDescription = "Open genres",
            onClick = onOpenGenres,
            trailingIcon = { tint -> NeedlerChevronRightIcon(tint = tint) },
        )
    }
}

/**
 * `Recent ⌄`, opening the list of sorts.
 *
 * The visible label is one word, as the pack draws it; the spoken label is the
 * pack's own `aria-label`, "Sort: recently added", because "Recent" alone does
 * not say what it sorts or that it is a control. [LibrarySort] carries the two
 * strings side by side so neither can be edited without seeing the other.
 *
 * The pill itself is [NeedlerToolbarPill], the pack's own control, rather than
 * the private one this used to draw. REQUIREMENTS.md "Tablet layout" calls the
 * row "segmented tabs, a sort control and a grid/list toggle" — three peers —
 * and two of the three being local composables beside a pack component is how
 * the row came to look like three different apps on the device.
 *
 * ## The menu is part of the control
 *
 * [NeedlerDropdownMenu], not Material's, for the reason that component gives: an
 * undressed menu takes a 2dp radius and one of Material's own dark greys, so a
 * pill opened a square in the wrong colour. A user judges a sort control by the
 * list it produces, which is the part they are looking at while they choose.
 */
@Composable
private fun SortControl(
    sort: LibrarySort,
    onSortSelect: (LibrarySort) -> Unit,
) {
    var expanded: Boolean by remember { mutableStateOf(false) }
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Box {
        NeedlerToolbarPill(
            text = sort.label,
            contentDescription = "Sort: " + sort.spokenLabel,
            onClick = { expanded = true },
            trailingIcon = { tint -> NeedlerChevronDownIcon(tint = tint) },
        )
        NeedlerDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LibrarySort.entries.forEach { option ->
                val chosen: Boolean = option == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = typography.body,
                            color = if (chosen) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortSelect(option)
                    },
                    // The accent on the chosen row is colour alone, which
                    // REQUIREMENTS.md "Accessibility" does not accept as the
                    // only carrier of a state. `selected` is what TalkBack
                    // announces instead of asking the user to see the blue.
                    modifier = Modifier.semantics {
                        contentDescription = "Sort by " + option.spokenLabel
                        selected = chosen
                    },
                )
            }
        }
    }
}

/**
 * The grid/list switch on the right of the controls row (screens 02, 09, 13).
 *
 * [NeedlerToolbarIconPill] is the same pack pill the sort control wears, in its
 * icon-only 44x36 form, so the two controls at the right of the row cannot
 * drift apart again.
 *
 * The spoken label names the destination rather than the pack's own
 * `aria-label="Switch view"`, which says neither which way it is about to go nor
 * which way it is now. REQUIREMENTS.md "Accessibility" requires a content
 * description on every control; one that leaves a TalkBack user guessing which
 * of two layouts a tap produces is a description in name only.
 */
@Composable
private fun ViewModeToggle(
    viewMode: LibraryViewMode,
    onToggle: () -> Unit,
) {
    NeedlerToolbarIconPill(
        contentDescription = when (viewMode) {
            LibraryViewMode.GRID -> "Switch to list view"
            LibraryViewMode.LIST -> "Switch to grid view"
        },
        onClick = onToggle,
    ) { tint ->
        NeedlerStrokeIcon(
            pathData = when (viewMode) {
                // Showing the grid offers the list, and the other way round —
                // the icon is the destination, as the pack draws it.
                LibraryViewMode.GRID -> PATH_LIST_VIEW
                LibraryViewMode.LIST -> PATH_GRID_VIEW
            },
            tint = tint,
        )
    }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

/**
 * Keeps [scrollAnchor] pointing at the album at the top of the viewport.
 *
 * It has to be recorded continuously rather than on the way out. When the view
 * mode flips, Compose runs the incoming layout's composition — including the
 * `remember` that builds its scroll state from the anchor — *before* it disposes
 * the outgoing one, so anything written in an `onDispose` would arrive one frame
 * too late to be read.
 *
 * [albums] and [firstVisibleItemIndex] are read through `rememberUpdatedState`
 * because the effect deliberately does not restart when either changes: keying
 * it on the album list would tear down and rebuild the collector on every sync,
 * and keying it on the lambda would do the same on every recomposition.
 */
@Composable
private fun RecordScrollAnchor(
    scrollAnchor: AlbumScrollAnchor,
    albums: List<Album>,
    firstVisibleItemIndex: () -> Int,
) {
    val currentAlbums: List<Album> by rememberUpdatedState(albums)
    val currentIndex: () -> Int by rememberUpdatedState(firstVisibleItemIndex)
    LaunchedEffect(scrollAnchor) {
        snapshotFlow { currentIndex() }
            .collect { index -> scrollAnchor.record(currentAlbums, index) }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    columns: Int,
    gutter: Dp,
    scrollAnchor: AlbumScrollAnchor,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onAlbumPlay: (ReleaseGroupMbid) -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = columns > 2
    // The anchor is read once, when the state is created. `rememberLazyGridState`
    // takes no inputs, so a later change to the anchor cannot yank the grid about
    // under a scrolling finger; and on a rotation the state's own saved index wins
    // over the initial value, which is what keeps the exact offset rotation
    // already preserved.
    val gridState: LazyGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = scrollAnchor.indexIn(albums),
    )
    RecordScrollAnchor(
        scrollAnchor = scrollAnchor,
        albums = albums,
    ) { gridState.firstVisibleItemIndex }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = spacing.step12),
        horizontalArrangement = Arrangement.spacedBy(
            if (wide) spacing.gridColumnGapTablet else spacing.gridColumnGapPhone,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (wide) spacing.gridRowGapTablet else spacing.gridRowGapPhone,
        ),
    ) {
        items(
            count = albums.size,
            key = { index -> albums[index].releaseGroupMbid.value },
        ) { index ->
            val album: Album = albums[index]
            NeedlerAlbumGridCell(
                // Guarded: `Album.title` can be blank, because `ReleaseItemDto.title`
                // is nullable and the catalogue mapper maps it with `.orEmpty()`. The
                // cell builds its own spoken description and its "Play <title>" label
                // from these two strings, so guarding here fixes all three at once.
                title = LibraryFormat.albumTitle(album.title),
                artistName = LibraryFormat.artistName(album.artistName),
                onClick = { onAlbumClick(album.releaseGroupMbid) },
                onDevice = album.showsOnDeviceCheck,
                onPlayClick = { onAlbumPlay(album.releaseGroupMbid) },
                artwork = {
                    AlbumArtwork(
                        album = album,
                        modifier = Modifier.fillMaxSize(),
                        shape = NeedlerTheme.shapes.artworkGrid,
                        decorative = true,
                    )
                },
            )
        }
    }
}

/**
 * Screen 13: the same albums as rows, each badged with its format and each with a
 * play button.
 *
 * ## Why the row gained a Play
 *
 * The grid cell has a play affordance and the list row had none, so the same
 * albums offered a different capability depending on which way the view toggle
 * happened to be set — a toggle whose label says "Switch to list view", promising
 * a different *layout* and delivering a different *feature set*.
 *
 * Of the two ways to make that consistent, the row gains a button rather than the
 * cell losing one. Three reasons, in order of weight. The cell's affordance is
 * drawn in the design pack (screens 02 and 09) and the row's absence is not a
 * drawn decision, it is an omission. Playing an album without opening it is the
 * commonest thing anyone does on a library screen, and removing it would be a
 * regression dressed as a fix. And a list row has room the cell does not: the
 * trailing column already holds the format label, and a 48dp button beside it fits
 * inside the pack's own 76dp row height.
 *
 * REQUIREMENTS.md "Accessibility" is satisfied the same way the grid cell
 * satisfies it — `NeedlerIconButton` expands any visual size to a 48dp target — so
 * the button is drawn at the pack's 32dp weight and still reaches the minimum.
 *
 * The format label is [AlbumFormatLabel], which is also what the artist screen
 * uses, so "what quality is this, and do I have it with me" is answered the same
 * way on both. It no longer leans on hue alone; see that function for why that
 * mattered.
 */
@Composable
private fun AlbumList(
    albums: List<Album>,
    gutter: Dp,
    scrollAnchor: AlbumScrollAnchor,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onAlbumPlay: (ReleaseGroupMbid) -> Unit,
    onAlbumAddToCrate: (ReleaseGroupMbid, Boolean) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    // See [AlbumGrid]: the same anchor, read the same way, so the two layouts open
    // on the same record.
    val listState: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = scrollAnchor.indexIn(albums),
    )
    RecordScrollAnchor(
        scrollAnchor = scrollAnchor,
        albums = albums,
    ) { listState.firstVisibleItemIndex }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = albums, key = { it.releaseGroupMbid.value }) { album ->
            val onDevice: Boolean = album.showsOnDeviceCheck
            val title: String = LibraryFormat.albumTitle(album.title)
            var crateMenuOpen: Boolean by remember(album.releaseGroupMbid.value) {
                mutableStateOf(false)
            }
            NeedlerAlbumRow(
                title = title,
                subtitle = LibraryFormat.artistName(album.artistName),
                minHeight = sizes.albumListRowMinHeight,
                onClick = { onAlbumClick(album.releaseGroupMbid) },
                showDivider = true,
                contentDescription = buildString {
                    append(title)
                    append(", ")
                    append(LibraryFormat.artistName(album.artistName))
                    // The chip border that marks a lossless format on screen is nothing
                    // at all to a screen reader, so the words go here instead.
                    albumFormatSpokenLabel(album.quality, onDevice)?.let {
                        append(", ")
                        append(it)
                    }
                },
                artwork = {
                    AlbumArtwork(
                        album = album,
                        modifier = Modifier.size(sizes.artworkThumbLarge),
                        shape = NeedlerTheme.shapes.artworkThumb,
                        decorative = true,
                    )
                },
                trailing = {
                    AlbumFormatLabel(quality = album.quality, onDevice = onDevice)
                    NeedlerIconButton(
                        contentDescription = "Play " + title,
                        onClick = { onAlbumPlay(album.releaseGroupMbid) },
                        visualSize = 32.dp,
                    ) {
                        NeedlerStrokeIcon(
                            pathData = PathPlay,
                            tint = colors.accent,
                            size = 16.dp,
                            filled = true,
                        )
                    }
                    // Beside the Play that replaces the crate: the same record, queued
                    // instead of started. In the trailing slot and not behind a long press,
                    // because `NeedlerAlbumRow` keeps interactive trailing content reachable
                    // as its own target, and a tap here opens the album rather than playing
                    // it - there is nothing destructive for a long press to intercept.
                    NeedlerCrateControl(
                        subject = title,
                        expanded = crateMenuOpen,
                        onExpandedChange = { crateMenuOpen = it },
                        onAddToCrate = { onAlbumAddToCrate(album.releaseGroupMbid, false) },
                        onPlayNext = { onAlbumAddToCrate(album.releaseGroupMbid, true) },
                    )
                },
            )
        }
    }
}

/**
 * The Artists tab: the alphabet strip, then every artist as a row.
 *
 * REQUIREMENTS.md "Library browse" asks for "Alphabetical, with index jump". The list was
 * alphabetical and there was no jump, so on the reference library - 289 albums - reaching D meant
 * scrolling past every A, B and C. [ArtistIndex] decides which letters exist and where each starts;
 * [ArtistIndexStrip] is the control.
 *
 * The strip is above the list rather than down the right-hand edge, which is where this control
 * usually goes. Three reasons, in the order they decided it:
 *
 *  * **The design pack draws nothing for it.** Screens 02, 09 and 13 are the Library at three
 *    widths and none of them has an index, a scroll thumb or a section header - the Artists tab is
 *    drawn as a plain list. So the form is chosen here rather than ported, and it is chosen against
 *    REQUIREMENTS.md "Accessibility" instead: "transport controls are at least 48 dp" and "Text must
 *    scale to 200% without clipping".
 *  * **A vertical A-Z rail cannot be 48dp per letter.** Twenty-seven targets down an 844dp phone is
 *    31dp each at normal text and less once the list has a header above it, and the number does not
 *    improve at 200% text - it is a function of the screen's height. A horizontal strip is bounded by
 *    width instead, and width is the one axis that can scroll: the strip is 27 chips wide at any text
 *    size and scrolls to reach the rest, which is exactly what `NeedlerSegmentedTabs` does a few
 *    composables up this file and for exactly the same reason.
 *  * **A fast-scroll thumb is a drag and nothing else.** REQUIREMENTS.md "Accessibility" is explicit
 *    that reordering the crate "must" be reachable "not only by dragging"; a jump control whose only
 *    form is a dragged thumb fails the same standard. Sticky section headers were the third
 *    candidate and were rejected for a plainer reason: a heading says where you *are*. It is not a
 *    jump, and the requirement asks for a jump.
 */
@Composable
private fun ArtistList(
    artists: List<Artist>,
    gutter: Dp,
    onArtistClick: (ArtistMbid) -> Unit,
) {
    val listState: LazyListState = rememberLazyListState()
    val scope: CoroutineScope = rememberCoroutineScope()
    val reducedMotion: Boolean = NeedlerTheme.reducedMotion
    val entries: List<ArtistIndexEntry> = remember(artists) { ArtistIndex.entriesFor(artists) }

    Column(modifier = Modifier.fillMaxSize()) {
        ArtistIndexStrip(
            entries = entries,
            gutter = gutter,
            onJump = { index ->
                // REQUIREMENTS.md "Accessibility": "Motion honours the reduced-motion setting." A
                // jump of two hundred rows is the longest animation this screen can produce, so it
                // is the one that most needs to be skippable.
                scope.launch {
                    if (reducedMotion) {
                        listState.scrollToItem(index)
                    } else {
                        listState.animateScrollToItem(index)
                    }
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                bottom = NeedlerTheme.spacing.step12,
            ),
        ) {
            items(items = artists, key = { it.mbid.value }) { artist ->
                NeedlerAlbumRow(
                    title = LibraryFormat.artistName(artist.name),
                    subtitle = LibraryFormat.artistRowSubtitle(
                        ownedAlbumCount = artist.ownedAlbumCount,
                        catalogueAlbumCount = artist.catalogueAlbumCount,
                    ),
                    onClick = { onArtistClick(artist.mbid) },
                    showDivider = true,
                )
            }
        }
    }
}

/**
 * The alphabet jump: `#`, then A to Z, each a real button.
 *
 * `NeedlerToolbarPill` rather than a bare `Text` with a `clickable`, because it is the pack's pill
 * and it already solves the two things a hand-rolled chip gets wrong: it is drawn at 36dp and
 * touched at 48dp through `minimumInteractiveComponentSize`, and it takes a content description
 * separate from its label - which a one-character label needs more than any other control in the
 * app, since "A" on its own says neither that it is a control nor what it does.
 *
 * An empty letter is `enabled = false`: drawn in the muted colour, still focusable, and announced
 * as having nothing under it. [ArtistIndexEntry.spokenLabel] carries that word, because
 * REQUIREMENTS.md "Accessibility" does not accept colour as the only carrier of a state - and the
 * muted grey it would be carried in is the one the same section measures at 3.82:1 on the surface
 * these pills are filled with, under the 4.5:1 it needs. Hiding the empty
 * letters instead was rejected in [ArtistIndex]: it shifts every letter after the gap, so the strip
 * rearranges itself as the library grows.
 */
@Composable
private fun ArtistIndexStrip(
    entries: List<ArtistIndexEntry>,
    gutter: Dp,
    onJump: (Int) -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = gutter, end = gutter, bottom = spacing.step4)
            .semantics { contentDescription = ARTIST_INDEX_LABEL },
        horizontalArrangement = Arrangement.spacedBy(spacing.step1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { entry ->
            val target: Int? = entry.firstArtistIndex
            NeedlerToolbarPill(
                text = entry.label,
                contentDescription = entry.spokenLabel,
                enabled = target != null,
                onClick = { if (target != null) onJump(target) },
            )
        }
    }
}

/**
 * What a screen reader announces before the twenty-seven chips.
 *
 * The same job `NeedlerSegmentedTabs`' "Browse by" does for the three tabs: a strip of
 * single-character buttons with no group name is twenty-seven unexplained letters in the traversal
 * order, and the name is what makes it one control a user can skip past or step into.
 */
private const val ARTIST_INDEX_LABEL: String = "Jump to a letter"

/**
 * The Songs tab: every track in the library as a row, with its duration on the
 * right.
 *
 * [nowPlayingTrackKey] is what marks the row the player is on — accent title,
 * record glyph, and ", playing" in the spoken description, the same three
 * signals the album screen gives the same track. The key comes from the
 * playback state rather than from anything this screen holds, so the marker
 * follows playback started anywhere in the app.
 */
@Composable
private fun SongList(
    songs: List<Track>,
    nowPlayingTrackKey: TrackKey?,
    gutter: Dp,
    onSongPlay: (Track) -> Unit,
    onSongAddToCrate: (Track, Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = songs, key = { it.key.canonicalString }) { track ->
            val playable: Boolean = track.hasPlayableFile
            val title: String = LibraryFormat.trackTitle(track.title)
            val isPlaying: Boolean = track.key == nowPlayingTrackKey
            var crateMenuOpen: Boolean by remember(track.key.canonicalString) {
                mutableStateOf(false)
            }
            // The whole reading, because the gesture modifier below clears the row's own.
            // `NeedlerAlbumRow` would have appended ", playing" to the description it was
            // given, so with the row's semantics cleared this has to append it instead -
            // and must not when the row keeps them, or TalkBack says it twice.
            val spoken: String = buildString {
                append(title)
                append(", ")
                append(LibraryFormat.songRowSubtitle(track))
                LibraryFormat.spokenDuration(track.durationMs)?.let {
                    append(", ")
                    append(it)
                }
                if (!playable) append(", " + LibraryFormat.NOT_IN_LIBRARY)
                if (playable && isPlaying) append(", playing")
            }
            // The divider is drawn here rather than by the row, because the crate control
            // sits beside the row and a hairline that stopped 48dp short of the edge would
            // read as a rendering fault.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeedlerAlbumRow(
                        title = title,
                        subtitle = LibraryFormat.songRowSubtitle(track),
                        isPlaying = isPlaying,
                        modifier = if (playable) {
                            Modifier
                                .weight(1f)
                                // A long press here played the song and threw the crate
                                // away, because `clickable` fires on the release however
                                // long the hold was. It opens the crate menu now, and the
                                // tap is consumed rather than firing behind it.
                                .needlerRowActions(
                                    description = spoken,
                                    onTap = { onSongPlay(track) },
                                    tapLabel = "Play",
                                    onLongPress = { crateMenuOpen = true },
                                    longPressLabel = CRATE_LONG_PRESS_LABEL,
                                )
                        } else {
                            Modifier.weight(1f)
                        },
                        // Handed to the modifier whenever there is one: two clickables on
                        // one row is a tap that fires twice.
                        onClick = null,
                        contentDescription = spoken,
                        trailing = {
                            LibraryFormat.duration(track.durationMs)?.let { duration ->
                                Text(
                                    text = duration,
                                    style = NeedlerTheme.typography.duration,
                                    color = NeedlerTheme.colors.textMuted,
                                )
                            }
                        },
                    )
                    // Beside the row, not inside it: the gesture modifier clears the row's
                    // descendants, so a control in the trailing slot would be drawn and
                    // unreachable to TalkBack. Only where there is something to queue.
                    if (playable) {
                        NeedlerCrateControl(
                            subject = title,
                            expanded = crateMenuOpen,
                            onExpandedChange = { crateMenuOpen = it },
                            onAddToCrate = { onSongAddToCrate(track, false) },
                            onPlayNext = { onSongAddToCrate(track, true) },
                        )
                    }
                }
                NeedlerHairline()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Loading, empty and offline
// ---------------------------------------------------------------------------

/**
 * The loading state.
 *
 * Empty artwork tiles rather than a spinner, because what is being waited for
 * is a local database read that finishes in single-digit milliseconds — a
 * spinner would flash and be gone. The tiles hold the layout still so the grid
 * does not jump when the first rows arrive.
 */
@Composable
private fun LibrarySkeleton(grid: Boolean, columns: Int, gutter: Dp) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) {
                contentDescription = "Loading your library"
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(
            if (grid) spacing.gridRowGapPhone else spacing.none,
        ),
    ) {
        if (grid) {
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.gridColumnGapPhone),
                ) {
                    repeat(columns) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(spacing.step5),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(NeedlerTheme.shapes.artworkGrid)
                                    .background(colors.surface),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .height(12.dp)
                                    .clip(NeedlerTheme.shapes.progress)
                                    .background(colors.surface),
                            )
                        }
                    }
                }
            }
        } else {
            repeat(8) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NeedlerTheme.sizes.albumListRowMinHeight),
                    horizontalArrangement = Arrangement.spacedBy(spacing.step14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(NeedlerTheme.sizes.artworkThumbLarge)
                            .clip(NeedlerTheme.shapes.artworkThumb)
                            .background(colors.surface),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(12.dp)
                            .clip(NeedlerTheme.shapes.progress)
                            .background(colors.surface),
                    )
                }
            }
        }
    }
}

/**
 * Nothing in the library yet.
 *
 * The copy differs by tab and by connectivity, because the way out differs. A
 * library with no albums on a connected device wants a sync; the same library
 * with no connection wants the user to know that syncing is what is missing,
 * not their music.
 */
@Composable
private fun LibraryEmptyState(
    tab: LibraryTab,
    offline: Boolean,
    onSyncNow: () -> Unit,
    gutter: Dp,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = spacing.step20),
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = when (tab) {
                LibraryTab.ALBUMS -> "No albums yet"
                LibraryTab.ARTISTS -> "No artists yet"
                LibraryTab.SONGS -> "No songs yet"
            },
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = if (offline) {
                "Needler has not synced this server's library yet, and there is no connection " +
                    "to sync it over. Everything already on this device still plays."
            } else {
                "Nothing has been synced from your server yet. Sync now fetches the whole " +
                    "library once; after that, browsing works with no connection at all."
            },
            style = typography.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(spacing.step4))
        NeedlerPrimaryButton(
            text = "Sync now",
            onClick = onSyncNow,
            enabled = !offline,
            contentDescription = if (offline) {
                "Sync now. Unavailable while offline."
            } else {
                null
            },
        )
    }
}

/**
 * The offline line.
 *
 * Deliberately not an error, and deliberately not dismissible. REQUIREMENTS.md:
 * "Offline is a first-class state, not an error." What it says is what the
 * architecture guarantees — the mirror is the read path, so browsing never
 * waited on the server in the first place.
 */
/**
 * What the last add to the crate did, with the crate's own figures under it.
 *
 * A fourth card of this shape in the module, deliberately not extracted. The album screen's
 * and the playlist screens' each carry their own tinting rules and their own notice types,
 * and folding four into one shared component would be a refactor of three working screens to
 * serve one new line. What is shared is the *copy*, which comes from one place:
 * `AlbumNotice.AddedToCrate` builds the sentence and `LibraryFormat.crateLine` the figures.
 *
 * REQUIREMENTS.md "Accessibility" asks every control to carry a description; this is not a
 * control but it is the only confirmation an add gets, so it is an assertive live region -
 * TalkBack reads it when it appears rather than when focus happens to reach it.
 */
@Composable
private fun CrateNoticeCard(message: String, detail: String?, onDismiss: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val spoken: String = if (detail == null) {
        message
    } else {
        message + " " + detail.replace(" · ", ", ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.positive, shape)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = message,
                style = NeedlerTheme.typography.caption,
                color = colors.textSecondary,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = NeedlerTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
        NeedlerIconButton(
            contentDescription = "Dismiss",
            onClick = onDismiss,
            visualSize = 36.dp,
        ) {
            NeedlerStrokeIcon(pathData = PathClose, tint = colors.textMuted, size = 16.dp)
        }
    }
}

@Composable
private fun OfflineNote() {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = OFFLINE_NOTE
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerOnDeviceIcon(tint = colors.positive)
        Text(
            text = OFFLINE_NOTE,
            style = typography.caption,
            color = colors.textSecondary,
            overflow = TextOverflow.Visible,
        )
    }
}

private const val TITLE: String = "LIBRARY"

internal const val OFFLINE_NOTE: String =
    "Offline. Your library is on this device, so browsing and downloaded music work as usual."

/** The pack's list-view glyph: three full-width rules. */
private const val PATH_LIST_VIEW: String = "M4 7h16M4 12h16M4 17h16"

/** The pack's grid-view glyph: four rounded squares. */
private const val PATH_GRID_VIEW: String =
    "M4.5 4.5h6v6h-6zM13.5 4.5h6v6h-6zM4.5 13.5h6v6h-6zM13.5 13.5h6v6h-6z"

/**
 * A track row, used by the album and artist screens rather than by this one.
 *
 * It lives here so that the four screens in this module agree on what a track
 * row is: the index, the title, the duration and — for a track a part-delivered
 * pull never brought — the greyed, unclickable variant REQUIREMENTS.md requires.
 *
 * @param onLongPress the crate menu, opened by holding the row. Supplying it moves
 *   the gesture and the spoken reading out of `NeedlerTrackRow` and into
 *   [needlerRowActions]; see [libraryTrackRowDescription] for what that costs and
 *   why it is worth it. It cannot be combined with [onMoreClick]: the modifier
 *   clears the row's descendants, which would leave that button unreachable.
 */
@Composable
internal fun LibraryTrackRow(
    index: Int,
    track: Track,
    isPlaying: Boolean,
    available: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onMoreClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val tap: (() -> Unit)? = if (available) onClick else null
    // The long press is only claimed where there is a tap to protect: an unplayable row
    // does nothing when tapped, so there is nothing destructive to intercept and nothing
    // to add to the crate either. Resolved to one nullable so that the two decisions
    // below - which modifier, and whether the row keeps its own click - agree.
    val longPress: (() -> Unit)? = if (tap == null) null else onLongPress
    val gestures: Modifier = if (longPress != null && tap != null) {
        Modifier.needlerRowActions(
            description = libraryTrackRowDescription(
                index = index,
                track = track,
                isPlaying = isPlaying,
                available = available,
            ),
            onTap = tap,
            tapLabel = "Play",
            onLongPress = longPress,
            longPressLabel = CRATE_LONG_PRESS_LABEL,
        )
    } else {
        Modifier
    }
    NeedlerTrackRow(
        index = index,
        // `CatalogueTrackDto.title` defaults to the empty string, so a catalogue
        // track list can arrive with nothing to draw. A blank line beside a track
        // number reads as a rendering fault rather than as missing metadata.
        title = LibraryFormat.trackTitle(track.title),
        duration = LibraryFormat.duration(track.durationMs) ?: "--:--",
        modifier = modifier.then(gestures),
        isPlaying = isPlaying,
        available = available,
        durationSpoken = LibraryFormat.spokenDuration(track.durationMs) ?: "unknown length",
        // Handed over entirely when the gesture modifier is in force: two clickables on
        // one row is a tap that fires twice.
        onClick = if (longPress == null) tap else null,
        onMoreClick = onMoreClick,
    )
}

/**
 * What TalkBack reads for a track row that carries the crate gesture.
 *
 * A deliberate restatement of `NeedlerTrackRow`'s own spoken string, which
 * [needlerRowActions] clears along with the rest of the row's descendants. Same order,
 * same four parts, built from the same `LibraryFormat` calls the row is handed — so the
 * two can only disagree if one of them is changed alone, and this is the only copy in
 * this module.
 *
 * The alternative to the restatement is a `contentDescription` parameter on
 * `NeedlerTrackRow`, which `NeedlerAlbumRow` has and that row does not. Adding one would
 * be the better shape and is a change to a component every list in the application
 * draws; this function keeps the reading correct without it.
 */
private fun libraryTrackRowDescription(
    index: Int,
    track: Track,
    isPlaying: Boolean,
    available: Boolean,
): String = buildString {
    // The number is dropped while playing, because the row replaces it with the record
    // glyph and reading a number that is not drawn describes a different row.
    if (!isPlaying) append(index.toString() + ". ")
    append(LibraryFormat.trackTitle(track.title))
    append(", ")
    append(LibraryFormat.spokenDuration(track.durationMs) ?: "unknown length")
    if (isPlaying) append(", playing")
    if (!available) append(", " + LibraryFormat.NOT_IN_LIBRARY)
}
