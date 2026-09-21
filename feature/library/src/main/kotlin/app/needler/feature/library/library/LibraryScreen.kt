@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumGridCell
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSearchFieldButton
import app.needler.core.design.component.NeedlerSegmentedTabs
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTrackRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.hasPlayableFile
import app.needler.feature.library.common.showsOnDeviceCheck

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
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    widthSizeClass: WindowWidthSizeClass,
    onTabSelect: (LibraryTab) -> Unit,
    onSortSelect: (LibrarySort) -> Unit,
    onViewModeToggle: () -> Unit,
    onSearchClick: () -> Unit,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onAlbumPlay: (ReleaseGroupMbid) -> Unit,
    onArtistClick: (ArtistMbid) -> Unit,
    onSongPlay: (Track) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
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
            if (state.offline) OfflineNote()
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
                        onAlbumClick = onAlbumClick,
                        onAlbumPlay = onAlbumPlay,
                    )

                state.tab == LibraryTab.ALBUMS -> AlbumList(
                    albums = state.albums,
                    gutter = gutter,
                    onAlbumClick = onAlbumClick,
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
        verticalAlignment = Alignment.CenterVertically,
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
            SortControl(sort = state.sort, onSortSelect = onSortSelect)
        }
        if (state.tab == LibraryTab.ALBUMS) {
            ViewModeToggle(viewMode = state.viewMode, onToggle = onViewModeToggle)
        }
    }
}

/**
 * `Recent ⌄`, opening the list of sorts.
 *
 * The visible label is one word, as the pack draws it; the spoken label is the
 * pack's own `aria-label`, "Sort: recently added", because "Recent" alone does
 * not say what it sorts or that it is a control.
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
        ToolbarPill(
            contentDescription = "Sort: " + sort.spokenLabel,
            onClick = { expanded = true },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sort.label,
                    style = typography.metaStrong,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                NeedlerChevronDownIcon(tint = colors.textSecondary)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            style = typography.body,
                            color = if (option == sort) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSortSelect(option)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Sort by " + option.spokenLabel
                    },
                )
            }
        }
    }
}

/** The grid/list switch on the right of the controls row (screens 02, 09, 13). */
@Composable
private fun ViewModeToggle(
    viewMode: LibraryViewMode,
    onToggle: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    ToolbarPill(
        contentDescription = when (viewMode) {
            LibraryViewMode.GRID -> "Switch to list view"
            LibraryViewMode.LIST -> "Switch to grid view"
        },
        onClick = onToggle,
        width = 44.dp,
    ) {
        NeedlerStrokeIcon(
            pathData = when (viewMode) {
                // Showing the grid offers the list, and the other way round —
                // the icon is the destination, as the pack draws it.
                LibraryViewMode.GRID -> PATH_LIST_VIEW
                LibraryViewMode.LIST -> PATH_GRID_VIEW
            },
            tint = colors.textSecondary,
        )
    }
}

/**
 * A 36dp-tall pill with a 48dp touch target.
 *
 * The pack draws these controls 36px tall and REQUIREMENTS.md asks for 48dp
 * targets. Both are satisfiable at once — the *drawn* pill stays 36dp and the
 * *clickable* box around it is 48dp — which is why this is a local composable
 * rather than `NeedlerPillButton`, whose clickable area is the pill itself.
 * Worth lifting into `:core:design`; see the handover notes.
 */
@Composable
private fun ToolbarPill(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val shape = NeedlerTheme.shapes.pill
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = sizes.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (width != null) Modifier.width(width) else Modifier)
                .defaultMinSize(minHeight = sizes.pillMinHeight)
                .clip(shape)
                .border(sizes.hairlineThickness, colors.hairline, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    columns: Int,
    gutter: Dp,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onAlbumPlay: (ReleaseGroupMbid) -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = columns > 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
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
                title = album.title,
                artistName = album.artistName,
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
 * Screen 13: the same albums as rows, each badged with its format.
 *
 * The badge is drawn in the positive green with the on-device glyph when the
 * album is on this device, and in the muted grey without one when it is only in
 * the library — which makes the list view the one place in the app where "what
 * quality is this, and do I have it with me" is answerable at a glance.
 */
@Composable
private fun AlbumList(
    albums: List<Album>,
    gutter: Dp,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = albums, key = { it.releaseGroupMbid.value }) { album ->
            val format: String? = LibraryFormat.quality(album.quality)
            val onDevice: Boolean = album.showsOnDeviceCheck
            NeedlerAlbumRow(
                title = album.title,
                subtitle = album.artistName,
                minHeight = sizes.albumListRowMinHeight,
                onClick = { onAlbumClick(album.releaseGroupMbid) },
                showDivider = true,
                contentDescription = buildString {
                    append(album.title)
                    append(", ")
                    append(album.artistName)
                    if (format != null) {
                        append(", ")
                        append(format)
                    }
                    if (onDevice) append(", on device")
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
                    if (format != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onDevice) NeedlerOnDeviceIcon(tint = colors.positive, size = 14.dp)
                            Text(
                                text = format,
                                style = typography.caption,
                                color = if (onDevice) colors.positive else colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<Artist>,
    gutter: Dp,
    onArtistClick: (ArtistMbid) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            bottom = NeedlerTheme.spacing.step12,
        ),
    ) {
        items(items = artists, key = { it.mbid.value }) { artist ->
            NeedlerAlbumRow(
                title = artist.name,
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

@Composable
private fun SongList(
    songs: List<Track>,
    nowPlayingTrackKey: TrackKey?,
    gutter: Dp,
    onSongPlay: (Track) -> Unit,
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
            NeedlerAlbumRow(
                title = track.title,
                subtitle = LibraryFormat.songRowSubtitle(track),
                onClick = if (playable) ({ onSongPlay(track) }) else null,
                showDivider = true,
                contentDescription = buildString {
                    append(track.title)
                    append(", ")
                    append(LibraryFormat.songRowSubtitle(track))
                    LibraryFormat.spokenDuration(track.durationMs)?.let {
                        append(", ")
                        append(it)
                    }
                    if (track.key == nowPlayingTrackKey) append(", playing")
                    if (!playable) append(", not in your library")
                },
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
) {
    NeedlerTrackRow(
        index = index,
        title = track.title,
        duration = LibraryFormat.duration(track.durationMs) ?: "--:--",
        modifier = modifier,
        isPlaying = isPlaying,
        available = available,
        durationSpoken = LibraryFormat.spokenDuration(track.durationMs) ?: "unknown length",
        onClick = if (available) onClick else null,
        onMoreClick = onMoreClick,
    )
}
