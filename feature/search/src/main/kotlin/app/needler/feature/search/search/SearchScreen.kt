package app.needler.feature.search.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerChevronRightIcon
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPullButton
import app.needler.core.design.component.NeedlerRequestSheetOverlay
import app.needler.core.design.component.NeedlerSearchField
import app.needler.core.design.component.NeedlerSearchIcon
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.accessibleLabel
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.SearchBucket
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.SuggestionKind
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.feature.search.common.AlbumArtwork
import app.needler.feature.search.common.ArtistAvatar
import app.needler.feature.search.common.SearchFormat
import app.needler.feature.search.common.TrackArtwork
import app.needler.feature.search.common.albumBadge
import app.needler.feature.search.common.hasPlayableFile
import app.needler.feature.search.common.showsOnDeviceCheck
import kotlin.math.roundToInt

/**
 * The Search screen: `design/html/03-Search.html` on a phone and
 * `design/html/10-TabletSearch.html` on a tablet.
 *
 * One composable serves both. The differences are the affordance beside the
 * field — a **Cancel** link on the phone, a back arrow on the tablet, as the two
 * files draw them — and whether the Albums block is a list of rows or a
 * two-column grid of cards. REQUIREMENTS.md "Tablet layout": "This is one
 * navigation model at two widths, driven by `WindowSizeClass`. Nothing is
 * tablet-only, so no feature needs building twice."
 *
 * The bottom nav bar (03), the nav rail and the permanent player sidebar (10)
 * are **not** drawn here. They belong to `:app`'s navigation scaffold, which
 * hosts this screen in its content pane; drawing them again inside the feature
 * would give the app two of each.
 *
 * ## The list is already merged
 *
 * Every row is an [Album], an [Artist] or a [Track] at some state, and nothing
 * on this screen asks which server lane it came from. That is REQUIREMENTS.md's
 * identity model doing its job: "an album found by catalogue search and an album
 * already in the library are the same domain object at different states". So the
 * four different trailing treatments screen 03 shows — **On device**, **In
 * library**, **Pulling**, and a **Pull** button — are four values of
 * [AlbumState] and not four kinds of result.
 *
 * The albums are drawn in two blocks all the same, split on that state and never
 * on the lane: what the server has, above the songs, and what would have to be
 * pulled, below them. See [SearchUiState] for why the un-split list had to go.
 *
 * ## What the screen says when half of it is missing
 *
 * REQUIREMENTS.md rule 4 requires that an offline or stale-session search "show
 * library results only and state plainly that catalogue search needs a
 * connection", and its note on `service_status` requires upstream degradation to
 * "surface as a quiet inline note, not an error dialog". Both arrive here as
 * [SearchUiState.catalogueNote] and are drawn as one line under the field. The
 * results below it are never hidden, greyed or replaced: the local lane made no
 * network call, so there is nothing about it for a connection to have broken.
 *
 * @param autoFocus whether to put the cursor in the field and raise the keyboard
 *   on arrival. True everywhere in the app: this is a screen whose only purpose
 *   is typing, and it opened with `mInputShown=false` on a device, costing a tap
 *   before any search could start. The parameter exists so a fixture that is
 *   about something else — the request sheet over the results — can render the
 *   field at rest.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    widthSizeClass: WindowWidthSizeClass,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmitQuery: () -> Unit,
    onCancel: () -> Unit,
    onRecentQuerySelect: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
    onSuggestionSelect: (SearchSuggestion) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onShowAll: (SearchBucket) -> Unit,
    onLoadMore: (SearchBucket) -> Unit,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirmPull: () -> Unit,
    onCancelPull: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    // 22px between blocks on the phone (03), 24px on the tablet (10); 10px and
    // 12px respectively between a block's header and its first row.
    val sectionGap: Dp = if (wide) spacing.step12 else spacing.step11
    val headerGap: Dp = if (wide) spacing.step6 else spacing.step5

    // How much of the window's bottom safe area is actually underneath the list.
    // See listBottomInset: this is the measurement Compose's own inset modifiers
    // cannot make, and without it the keyboard costs this screen 200px of dead
    // space and half a row.
    val chromeBelowList = remember { mutableStateOf(0) }
    val bottomInset: Dp = listBottomInset(chromeBelowList.value)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                // Top and sides only. The bottom is this screen's own business and is
                // handled on the list, because whatever the host has put below this
                // screen - a mini-player, a bottom navigation bar - already stands
                // between the list and the bottom of the window.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
        ) {
            SearchHeader(
                state = state,
                wide = wide,
                gutter = gutter,
                autoFocus = autoFocus,
                onQueryChange = onQueryChange,
                onClearQuery = onClearQuery,
                onSubmitQuery = onSubmitQuery,
                onCancel = onCancel,
            )

            // Every query starts at the top.
            //
            // A LazyColumn keeps its scroll position across a content change, and
            // here that is wrong: the list is rebuilt for each query, so a user
            // who had scrolled into the albums of one search arrived at the next
            // one already past the ARTISTS block - results they never asked to
            // skip, in the section most likely to hold what they typed. Keying
            // the effect on the query rather than on the results means a slow
            // catalogue page arriving later does not yank the list back under
            // someone who has started reading.
            val resultsState: LazyListState = rememberLazyListState()
            LaunchedEffect(state.query) {
                resultsState.scrollToItem(0)
            }

            LazyColumn(
                state = resultsState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { coordinates ->
                        val root: LayoutCoordinates = coordinates.findRootCoordinates()
                        val listBottom: Float =
                            coordinates.positionInRoot().y + coordinates.size.height
                        chromeBelowList.value =
                            (root.size.height - listBottom).roundToInt().coerceAtLeast(0)
                    },
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = if (wide) spacing.step12 else spacing.step10,
                    // The list's own trailing space, plus however much of the window's
                    // bottom safe area - the keyboard, above all - is underneath it.
                    bottom = spacing.step12 + bottomInset,
                ),
            ) {
                // Read out of the state once, into locals: `catalogueNote` is a
                // computed property, so re-reading it inside an item lambda would
                // both recompute it and defeat the null check above it.
                val notice: SearchNotice? = state.notice
                val catalogueNote: String? = state.catalogueNote

                if (notice != null) {
                    item(key = "notice") {
                        NoticeLine(
                            message = notice.message,
                            isProblem = notice.isProblem,
                            onDismiss = onDismissNotice,
                        )
                        Spacer(modifier = Modifier.height(headerGap))
                    }
                }

                if (catalogueNote != null) {
                    item(key = "catalogue-note") {
                        NoticeLine(
                            message = catalogueNote,
                            isProblem = state.catalogueNoteIsProblem,
                            onDismiss = null,
                        )
                        Spacer(modifier = Modifier.height(headerGap))
                    }
                }

                when {
                    state.isIdle -> idleBlock(
                        state = state,
                        headerGap = headerGap,
                        onRecentQuerySelect = onRecentQuerySelect,
                        onClearRecentQueries = onClearRecentQueries,
                    )

                    state.searching -> item(key = "skeleton") { SearchSkeleton() }

                    state.showEmptyResult -> emptyResultBlock(
                        state = state,
                        sectionGap = sectionGap,
                        headerGap = headerGap,
                        onSuggestionSelect = onSuggestionSelect,
                    )

                    else -> resultBlocks(
                        state = state,
                        wide = wide,
                        sectionGap = sectionGap,
                        headerGap = headerGap,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onPull = onPull,
                        onPlayTrack = onPlayTrack,
                        onShowAll = onShowAll,
                        onLoadMore = onLoadMore,
                    )
                }
            }
        }
        // The request sheet, over everything, when a Pull has been tapped.
        // REQUIREMENTS.md "Placing a request": the `monitor_artist` flag is "a
        // secondary toggle on the request sheet", and this is `:core:design`'s
        // sheet rather than one built here - the same sheet the library screens
        // open, so a pull looks and behaves identically wherever it starts.
        val sheetAlbum: Album? = state.pullSheetAlbum
        if (sheetAlbum != null) {
            NeedlerRequestSheetOverlay(
                // Renamed from albumTitle/artistName when the same sheet gained
                // the artist-wide batch request: "everything by X" has no album
                // title, so the old names would have been a lie at one of the
                // two call sites.
                //
                // Both are guarded. A catalogue album can arrive with a blank
                // title - `title` defaults to the empty string on the wire and
                // nothing upstream fills it in - and an unguarded blank here
                // renders a sheet whose confirm button says "Pull" over an empty
                // line, which is the same defect that made the Pulls screen
                // unusable on a device.
                title = SearchFormat.albumTitle(sheetAlbum.title),
                subtitle = sheetAlbum.artistName.takeIf { it.isNotBlank() },
                monitorArtist = state.monitorArtist,
                onMonitorArtistChange = onMonitorArtistChange,
                onConfirm = onConfirmPull,
                onCancel = onCancelPull,
                // The server's own `quality_snapshot_summary` when it sent one,
                // which is the only account of what will be downloaded that is
                // guaranteed to be true. Null falls back to the sheet's own line.
                qualityNote = sheetAlbum.qualityPolicySummary,
                busy = state.busy,
                artwork = {
                    AlbumArtwork(
                        album = sheetAlbum,
                        modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
                    )
                },
            )
        }
    }
}

/**
 * How much bottom padding the results list needs: the part of the window's bottom
 * safe area that is genuinely underneath *this list*, and not a pixel more.
 *
 * ## Why `safeDrawingPadding()` was wrong here
 *
 * Compose's inset modifiers are not position-aware. They apply the window's inset
 * wherever they are used, and track only what a parent has already *consumed* -
 * they cannot know that something else is drawn below the element. This screen is
 * hosted inside a scaffold that consumes the top and the sides and then draws an
 * update banner, a mini-player and the bottom navigation bar *below* the content
 * slot, so the list's bottom edge already sits ~200px above the bottom of the
 * window. `safeDrawingPadding()` on the content then applied the whole keyboard
 * inset again, measured from the window: the list ended 200px above the keyboard,
 * and because the padding shrank the viewport instead of extending the scroll, the
 * last row was clipped through the middle of its Pull button and could not be
 * scrolled into view at all. Both halves of that were the same bug.
 *
 * ## What this does instead
 *
 * [chromeBelowListPx] is measured: the distance from the bottom of the list to the
 * bottom of the window, which is exactly the height of whatever the host drew
 * below. Subtracting it from the window's bottom safe inset leaves the overlap -
 * the keyboard's own encroachment on this list - and that goes on the list as
 * *content* padding, so the last row scrolls clear of the keyboard rather than the
 * viewport losing height it could have drawn in.
 *
 * It is `safeDrawing` and not `ime`, so the gesture area is still cleared on a
 * surface with nothing under the list at all - the tablet layout, where the rail
 * and the player sidebar are beside the content and nothing is below it.
 *
 * Returns zero while the list has never been positioned, and in the screenshot
 * tests, which render with no insets at all.
 */
@Composable
private fun listBottomInset(chromeBelowListPx: Int): Dp {
    val density: Density = LocalDensity.current
    val safeBottomPx: Int = WindowInsets.safeDrawing.getBottom(density)
    val overlapPx: Int = (safeBottomPx - chromeBelowListPx).coerceAtLeast(0)
    return with(density) { overlapPx.toDp() }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * The field and the way out.
 *
 * Screen 03 puts a plain **Cancel** link to the right of the field; screen 10
 * puts a back arrow to its left and caps the field at 560px so it does not
 * stretch across the whole content pane. Both are the same escape hatch drawn
 * for the width they are on, which is why one callback serves both.
 *
 * The keyboard's action key is `Search` and it does not submit anything: results
 * are already live by the time a finger reaches it. All it does is tell the
 * ViewModel this query was meant, so it is worth remembering. That is stated in
 * the action rather than left implicit, because a `Search` key that visibly did
 * nothing would be worse than no action key at all.
 *
 * ## The cursor starts here
 *
 * [autoFocus] asks for the field once, on arrival, and `BasicTextField` raises
 * the keyboard itself as it takes focus. Once, and keyed on nothing, because the
 * user's own later taps — a result, Cancel, the clear button — must be able to
 * take focus away and keep it; a request that re-fired on recomposition would
 * drag the keyboard back up under them. Screen 03 draws the field with the
 * accent focus border already on it, so this is also the state the pack shows.
 */
@Composable
private fun SearchHeader(
    state: SearchUiState,
    wide: Boolean,
    gutter: Dp,
    autoFocus: Boolean,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmitQuery: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    val colors = NeedlerTheme.colors

    val fieldFocus: FocusRequester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            // The field is attached by the time an effect runs, but a host that
            // disposes this screen in the same frame it composed it - a tab
            // swap landing elsewhere - would leave the requester with nothing
            // to give focus to, and that throws rather than returning false.
            runCatching { fieldFocus.requestFocus() }
        }
    }

    val field: @Composable () -> Unit = {
        NeedlerSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            onClear = onClearQuery,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            modifier = Modifier.focusRequester(fieldFocus),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (wide) gutter - 10.dp else gutter,
                end = gutter,
                top = if (wide) spacing.step12 else spacing.step14,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (wide) spacing.step8 else spacing.step5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wide) {
            NeedlerIconButton(contentDescription = "Back", onClick = onCancel) {
                NeedlerStrokeIcon(
                    pathData = PathChevronLeft,
                    tint = colors.textPrimary,
                    size = 24.dp,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 560.dp),
            ) { field() }
        } else {
            Box(modifier = Modifier.weight(1f)) { field() }
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(horizontal = spacing.step4),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Cancel",
                    style = NeedlerTheme.typography.rowTitle,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

/**
 * The blocks, in the order the user needs them: Artists, the albums already in the
 * library, Songs, then the albums that would have to be pulled.
 *
 * Screen 03 draws Artist, Albums, Songs and knows nothing of the fourth block
 * because the pack's mock-up shows four albums, not twenty-four. On a real server
 * the catalogue half of that one Albums block ran to twenty rows and put the Songs
 * block sixteen swipes below the fold — the user's own music ranked below a
 * shopping list. The library leads now: the first three blocks are all things the
 * user has and can play, and everything un-owned is last, capped, and paged.
 *
 * A block with nothing in it is omitted entirely rather than drawn empty, which
 * is what makes a one-artist search look like screen 03 and a song-only search
 * look like a song list instead of three blank headings and a list.
 */
private fun LazyListScope.resultBlocks(
    state: SearchUiState,
    wide: Boolean,
    sectionGap: Dp,
    headerGap: Dp,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onShowAll: (SearchBucket) -> Unit,
    onLoadMore: (SearchBucket) -> Unit,
) {
    var first = true

    // Read out of the state once: these are derived, and re-reading them from
    // inside an item or a key lambda would filter the whole result per row.
    val artists: List<Artist> = state.visibleArtists
    val libraryAlbums: List<Album> = state.libraryAlbums
    val tracks: List<Track> = state.tracks
    val catalogueAlbums: List<Album> = state.visibleCatalogueAlbums

    if (artists.isNotEmpty()) {
        blockHeader(
            key = "artists",
            title = state.artistsHeader,
            trailing = null,
            topGap = 0.dp,
            headerGap = headerGap,
        )
        first = false
        items(
            count = artists.size,
            key = { index -> "artist-" + artists[index].mbid.value },
        ) { index ->
            ArtistRow(artist = artists[index], wide = wide, onClick = onArtistClick)
        }
        moreRowItem(
            bucket = SearchBucket.ARTISTS,
            row = state.moreRow(SearchBucket.ARTISTS),
            onShowAll = onShowAll,
            onLoadMore = onLoadMore,
        )
    }

    if (libraryAlbums.isNotEmpty()) {
        blockHeader(
            key = "albums-library",
            title = LIBRARY_ALBUMS_HEADER,
            // Always "in your library": that is what every row in this block is,
            // whatever lane found it.
            trailing = FROM_LIBRARY,
            topGap = if (first) 0.dp else sectionGap,
            headerGap = headerGap,
        )
        first = false
        albumRows(
            keyPrefix = "owned",
            albums = libraryAlbums,
            wide = wide,
            busy = state.busy,
            onAlbumClick = onAlbumClick,
            onPull = onPull,
        )
    }

    if (tracks.isNotEmpty()) {
        blockHeader(
            key = "songs",
            title = "Songs",
            // Not a choice: catalogue search returns artists and albums only, so
            // this block can never hold anything else.
            trailing = FROM_LIBRARY,
            topGap = if (first) 0.dp else sectionGap,
            headerGap = headerGap,
        )
        first = false
        items(
            count = tracks.size,
            key = { index -> "track-" + tracks[index].key.canonicalString },
        ) { index ->
            SongRow(
                track = tracks[index],
                nowPlayingTrackKey = state.nowPlayingTrackKey,
                onPlay = onPlayTrack,
            )
        }
    }

    if (catalogueAlbums.isNotEmpty()) {
        blockHeader(
            key = "albums-catalogue",
            title = CATALOGUE_ALBUMS_HEADER,
            // "from MusicBrainz" once the catalogue has answered, "in your
            // library" while it has not or cannot — these rows are then cached
            // catalogue records the mirror happens to hold. See SearchUiState.
            trailing = state.albumsSourceNote,
            topGap = if (first) 0.dp else sectionGap,
            headerGap = headerGap,
        )
        albumRows(
            keyPrefix = "pull",
            albums = catalogueAlbums,
            wide = wide,
            busy = state.busy,
            onAlbumClick = onAlbumClick,
            onPull = onPull,
        )
        moreRowItem(
            bucket = SearchBucket.ALBUMS,
            row = state.moreRow(SearchBucket.ALBUMS),
            onShowAll = onShowAll,
            onLoadMore = onLoadMore,
        )
    }
}

/**
 * One block of album rows: a list on a phone, a two-column grid of cards on a
 * tablet.
 *
 * @param keyPrefix distinguishes the two album blocks. A `LazyColumn` key must be
 *   unique across the whole list, and the two blocks hold disjoint sets of release
 *   groups today; the prefix means a future row that appears in both cannot crash
 *   the list.
 */
private fun LazyListScope.albumRows(
    keyPrefix: String,
    albums: List<Album>,
    wide: Boolean,
    busy: Boolean,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
) {
    if (wide) {
        // Screen 10 lays the albums out as a two-column grid of cards. A
        // LazyVerticalGrid cannot be nested inside this LazyColumn, and
        // splitting the screen into two scrollers to get one would scroll
        // the Artist block independently of the albums under it, which the
        // pack does not do. Chunking into rows of two keeps one scroller and
        // one scroll position.
        val pairs: List<List<Album>> = albums.chunked(TABLET_ALBUM_COLUMNS)
        items(
            count = pairs.size,
            key = { index ->
                keyPrefix + "-album-row-" + pairs[index].first().releaseGroupMbid.value
            },
        ) { index ->
            AlbumCardRow(
                albums = pairs[index],
                busy = busy,
                onAlbumClick = onAlbumClick,
                onPull = onPull,
            )
        }
    } else {
        items(
            count = albums.size,
            key = { index -> keyPrefix + "-album-" + albums[index].releaseGroupMbid.value },
        ) { index ->
            AlbumRow(
                album = albums[index],
                busy = busy,
                onClick = onAlbumClick,
                onPull = onPull,
            )
        }
    }
}

/**
 * The one row under a capped block, when there is one.
 *
 * It is a `LazyListScope` extension rather than a composable inside the last item
 * so that it keeps its own key and animates as its own row when it changes from
 * "Show all 14 albums" to "Looking for more…" to nothing.
 */
private fun LazyListScope.moreRowItem(
    bucket: SearchBucket,
    row: SearchMoreRow?,
    onShowAll: (SearchBucket) -> Unit,
    onLoadMore: (SearchBucket) -> Unit,
) {
    if (row == null) return
    item(key = "more-" + bucket.name) {
        MoreRow(
            row = row,
            onClick = {
                when (row.action) {
                    SearchMoreAction.SHOW_ALL -> onShowAll(bucket)
                    SearchMoreAction.LOAD_MORE -> onLoadMore(bucket)
                }
            },
        )
    }
}

/**
 * "Show all 14 albums", "More from MusicBrainz", "Looking for more…", or why the
 * last page failed.
 *
 * One control for all four, because they occupy the same place and only one of
 * them can be true at a time — [SearchUiState.moreRow] decides which. A disabled
 * [NeedlerTextButton] already draws in the muted colour, which is exactly right
 * for the two states that are statements rather than offers.
 *
 * A polite live region, so a screen reader hears "Looking for more" and then the
 * answer without being thrown back to the top of the results.
 */
@Composable
private fun MoreRow(row: SearchMoreRow, onClick: () -> Unit) {
    val colors = NeedlerTheme.colors
    NeedlerTextButton(
        text = row.label,
        onClick = onClick,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        enabled = row.enabled,
        // The palette has one emphasis colour and no error colour, so a problem is
        // drawn in the primary text colour rather than in a red this design system
        // does not have. Same decision as NoticeLine.
        color = if (row.isProblem) colors.textPrimary else colors.accent,
    )
}

private fun LazyListScope.blockHeader(
    key: String,
    title: String,
    trailing: String?,
    topGap: Dp,
    headerGap: Dp,
) {
    item(key = "header-$key") {
        Column {
            if (topGap > 0.dp) Spacer(modifier = Modifier.height(topGap))
            NeedlerSectionHeader(title = title, trailing = trailing)
            Spacer(modifier = Modifier.height(headerGap))
        }
    }
}

/**
 * The artist at the head of the results: a round tile, the name, and how much of
 * their work you already have.
 *
 * The row is 64dp on both widths. Screen 10 draws the name a little larger than
 * screen 03 does, and that difference is deliberately not reproduced: it would
 * mean a second row title style existing only here, and the pack uses the same
 * 16sp/600 row title everywhere else at both widths.
 */
@Composable
private fun ArtistRow(
    artist: Artist,
    wide: Boolean,
    onClick: (Artist) -> Unit,
) {
    val colors = NeedlerTheme.colors
    val subtitle: String = SearchFormat.artistRowSubtitle(artist)
    NeedlerAlbumRow(
        title = artist.name,
        subtitle = subtitle,
        minHeight = NeedlerTheme.sizes.queueRowMinHeight,
        onClick = { onClick(artist) },
        contentDescription = artist.name + ", " + subtitle.replace(" · ", ", "),
        artwork = {
            ArtistAvatar(
                artist = artist,
                modifier = Modifier.size(
                    if (wide) 64.dp else NeedlerTheme.sizes.artworkRow,
                ),
            )
        },
        trailing = {
            NeedlerChevronRightIcon(tint = colors.textMuted, size = 20.dp)
        },
    )
}

/** An album result on a phone: screen 03's 72dp row with a 56dp cover. */
@Composable
private fun AlbumRow(
    album: Album,
    busy: Boolean,
    onClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
) {
    NeedlerAlbumRow(
        title = album.title,
        subtitle = SearchFormat.albumRowSubtitle(album),
        minHeight = NeedlerTheme.sizes.albumRowMinHeight,
        onClick = { onClick(album) },
        contentDescription = albumRowDescription(album),
        artwork = {
            AlbumArtwork(
                album = album,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
            )
        },
        trailing = { AlbumTrailing(album = album, busy = busy, onPull = onPull) },
    )
}

/** One row of screen 10's two-column album grid. */
@Composable
private fun AlbumCardRow(
    albums: List<Album>,
    busy: Boolean,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NeedlerTheme.spacing.step6),
        horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step6),
    ) {
        albums.forEach { album ->
            AlbumCard(
                album = album,
                busy = busy,
                onClick = onAlbumClick,
                onPull = onPull,
                modifier = Modifier.weight(1f),
            )
        }
        // A trailing odd album leaves a gap the width of a card rather than a
        // card stretched to twice the width of its neighbours.
        repeat(TABLET_ALBUM_COLUMNS - albums.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The tablet album card: screen 10's 76px surface tile with a hairline border.
 *
 * The **Pull** button inside it stays its own accessibility target — the card
 * opens the album, the button acquires it, and merging them would leave a
 * screen reader with one control that does two different things depending on
 * where it is tapped.
 */
@Composable
private fun AlbumCard(
    album: Album,
    busy: Boolean,
    onClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .defaultMinSize(minHeight = NeedlerTheme.sizes.albumListRowMinHeight)
            .clickable(role = Role.Button) { onClick(album) }
            .semantics(mergeDescendants = true) {
                contentDescription = albumRowDescription(album)
            }
            .padding(horizontal = NeedlerTheme.spacing.step8, vertical = NeedlerTheme.spacing.step4),
        horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step7),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtwork(
            album = album,
            modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step1),
        ) {
            Text(
                text = album.title,
                style = typography.rowTitle,
                color = colors.textPrimary,
                maxLines = 2,
            )
            Text(
                text = SearchFormat.albumRowSubtitle(album),
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 2,
            )
        }
        AlbumTrailing(album = album, busy = busy, onPull = onPull)
    }
}

/**
 * What sits on the right of an album result.
 *
 * An album the server does not own wears a **Pull**; anything else wears the
 * badge that says where it has got to. The two are mutually exclusive and both
 * come from [AlbumState], so a row cannot offer a Pull for something already
 * pulling — which is the bug a separate "isOwned" flag beside the state would
 * eventually produce.
 */
@Composable
private fun AlbumTrailing(
    album: Album,
    busy: Boolean,
    onPull: (Album) -> Unit,
) {
    if (album.state == AlbumState.NotOwned) {
        NeedlerPullButton(
            onClick = { onPull(album) },
            albumTitle = album.title,
            enabled = !busy,
        )
    } else {
        albumBadge(album.state)?.let { badge -> NeedlerStateBadge(badge = badge) }
    }
}

/**
 * A song result: the same 72dp row and 56dp cover as the album rows above it,
 * with the duration on the right.
 *
 * ## Why not the pack's 44dp cover
 *
 * Screen 03 draws the Songs block's tiles at 44dp, 12dp smaller than the album
 * rows above them. On a device that put the album titles at `x=237` and the song
 * titles at `x=206` — a 31px step in the one vertical line the eye follows down
 * a list of mixed results. The pack can draw that step because its mock-up shows
 * one block at a time; a real merged result has Artists, Albums and Songs
 * stacked in one scroller, and there the step reads as a layout fault rather
 * than as a hierarchy.
 *
 * So one thumbnail size serves every section, which is also the size the artist
 * avatars and the loading skeleton already use. The row grows from 56dp to 72dp
 * as a consequence: a 56dp tile plus the row's own 8dp of vertical padding is
 * 72dp whatever the minimum says, so there is no height to be saved by keeping
 * the smaller minimum.
 *
 * A track a part-delivered pull never brought has nothing to play, so the row
 * does not offer the tap. REQUIREMENTS.md "Partial content is a normal state":
 * such tracks are shown rather than hidden, because hiding them would
 * misrepresent what the user owns.
 */
@Composable
private fun SongRow(
    track: Track,
    nowPlayingTrackKey: TrackKey?,
    onPlay: (Track) -> Unit,
) {
    val playable: Boolean = track.hasPlayableFile
    val subtitle: String = SearchFormat.songRowSubtitle(track)
    NeedlerAlbumRow(
        title = track.title,
        subtitle = subtitle,
        minHeight = NeedlerTheme.sizes.albumRowMinHeight,
        isPlaying = track.key == nowPlayingTrackKey,
        onClick = if (playable) ({ onPlay(track) }) else null,
        // No ", playing" here: NeedlerAlbumRow appends that to whatever
        // description it is given, so adding it as well would have TalkBack say
        // it twice.
        contentDescription = buildString {
            append(track.title)
            append(", ")
            append(subtitle.replace(" · ", ", "))
            SearchFormat.spokenDuration(track.durationMs)?.let { spoken ->
                append(", ")
                append(spoken)
            }
            if (!playable) append(", not in your library")
        },
        artwork = {
            TrackArtwork(
                track = track,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
            )
        },
        trailing = {
            SearchFormat.duration(track.durationMs)?.let { drawn ->
                Text(
                    text = drawn,
                    style = NeedlerTheme.typography.duration,
                    color = NeedlerTheme.colors.textMuted,
                )
            }
        },
    )
}

/** `Mordechai, Khruangbin, 2024, On device` — the whole row in one phrase. */
private fun albumRowDescription(album: Album): String {
    val badge: NeedlerAlbumBadge? = albumBadge(album.state)
    return buildString {
        append(album.title)
        append(", ")
        append(SearchFormat.albumRowSubtitle(album).replace(" · ", ", "))
        if (badge != null) {
            append(", ")
            append(badge.accessibleLabel())
        }
        // The green check the pack puts on the artwork of a complete download is
        // already covered by the On device badge, so it is not repeated; what is
        // worth saying is when a *pinned* album is not yet fully down.
        if (album.state is AlbumState.Pinned && !album.showsOnDeviceCheck) {
            append(", still downloading to this device")
        }
    }
}

// ---------------------------------------------------------------------------
// Nothing typed, nothing found, and the wait in between
// ---------------------------------------------------------------------------

/**
 * Before anything is typed.
 *
 * The pack draws no such state — screen 03 opens with a query already in the
 * field — so this is built from what the domain offers rather than invented:
 * `SearchRepository.observeRecentQueries` describes itself as being "for the
 * empty-search state", which makes previous searches the intended content. A
 * first run has no history, and then the screen says what the field is for
 * instead of showing an empty heading.
 */
private fun LazyListScope.idleBlock(
    state: SearchUiState,
    headerGap: Dp,
    onRecentQuerySelect: (String) -> Unit,
    onClearRecentQueries: () -> Unit,
) {
    if (state.recentQueries.isEmpty()) {
        item(key = "intro") { IntroBlock() }
        return
    }

    blockHeader(
        key = "recent",
        title = "Recent searches",
        trailing = null,
        topGap = 0.dp,
        headerGap = headerGap,
    )
    items(
        count = state.recentQueries.size,
        key = { index -> "recent-" + state.recentQueries[index] },
    ) { index ->
        val text: String = state.recentQueries[index]
        QueryRow(
            text = text,
            detail = null,
            spoken = "Search again for $text",
            onClick = { onRecentQuerySelect(text) },
        )
    }
    item(key = "recent-clear") {
        NeedlerTextButton(
            text = "Clear recent searches",
            onClick = onClearRecentQueries,
        )
    }
}

/**
 * Both lanes answered and neither had anything.
 *
 * The copy differs by connectivity because the way out differs: a connected
 * search that found nothing really has found nothing, while an offline one has
 * searched half of what it normally would and the missing half is the likelier
 * home of the answer.
 */
private fun LazyListScope.emptyResultBlock(
    state: SearchUiState,
    sectionGap: Dp,
    headerGap: Dp,
    onSuggestionSelect: (SearchSuggestion) -> Unit,
) {
    item(key = "empty") {
        val colors = NeedlerTheme.colors
        val typography = NeedlerTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step3)) {
            Text(
                text = "Nothing found",
                style = typography.displayCompact,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (state.offline) {
                    "Nothing in your library matches \"" + state.query.trim() + "\". The " +
                        "MusicBrainz catalogue could not be searched without a connection, and " +
                        "it is the half that holds everything you do not own yet."
                } else {
                    "Nothing in your library or in the MusicBrainz catalogue matches \"" +
                        state.query.trim() + "\". A shorter query, or the artist's name on its " +
                        "own, usually finds more."
                },
                style = typography.body,
                color = colors.textSecondary,
            )
        }
    }

    if (!state.showSuggestions) return

    blockHeader(
        key = "suggestions",
        title = "Suggestions",
        trailing = null,
        topGap = sectionGap,
        headerGap = headerGap,
    )
    items(
        count = state.suggestions.size,
        key = { index -> "suggestion-" + state.suggestions[index].text },
    ) { index ->
        val suggestion: SearchSuggestion = state.suggestions[index]
        val detail: String? = when (suggestion.kind) {
            SuggestionKind.ARTIST -> "artist"
            SuggestionKind.ALBUM -> "album"
            SuggestionKind.QUERY -> null
        }
        QueryRow(
            text = suggestion.text,
            detail = detail,
            spoken = buildString {
                append("Search for ")
                append(suggestion.text)
                if (detail != null) {
                    append(", ")
                    append(detail)
                }
            },
            onClick = { onSuggestionSelect(suggestion) },
        )
    }
}

/**
 * A row that puts a piece of text back in the field: a recent search, or a
 * completion from `suggest`.
 *
 * Hand-rolled from the pack's own parts rather than reusing `NeedlerSettingsRow`
 * or `NeedlerAlbumRow`. The first would draw a disclosure chevron promising a
 * screen to push, and the second insists on a subtitle these rows do not have —
 * a second line of empty space under every entry.
 */
@Composable
private fun QueryRow(
    text: String,
    detail: String?,
    spoken: String,
    onClick: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(vertical = NeedlerTheme.spacing.step4),
            horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step7),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeedlerSearchIcon(tint = colors.textMuted, size = 18.dp)
            Text(
                text = text,
                style = NeedlerTheme.typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = NeedlerTheme.typography.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        NeedlerHairline()
    }
}

/**
 * The first-run state: no history to offer, so say what the field reaches.
 *
 * Two lines, and both are about the user's music rather than about the app's
 * architecture. The first draft read "One field, both halves" over four
 * sentences explaining that two lanes are queried and merged — true, and the
 * user's problem is that they have not typed anything yet. The heading now names
 * what they get and the body names the one thing they could not have guessed:
 * that a result they do not own is still something they can act on.
 */
@Composable
private fun IntroBlock() {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step3)) {
        Text(
            text = "Your music, and the rest",
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Your library answers as you type, online or off. Anything the server " +
                "does not have yet turns up too — pull it from the results.",
            style = typography.body,
            color = colors.textSecondary,
        )
    }
}

/**
 * The wait between the first keystroke and the first result.
 *
 * Skeleton rows rather than a spinner, for the same reason the library screen
 * uses them: what is being waited for is a local FTS query with a 50 ms budget,
 * and a spinner would flash and be gone. The rows hold the layout still so the
 * list does not jump when the first match lands.
 */
@Composable
private fun SearchSkeleton() {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Searching"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        repeat(SKELETON_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sizes.albumRowMinHeight),
                horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step7),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(sizes.artworkRow)
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

/**
 * The quiet line: what the catalogue lane is doing, or what the last action did.
 *
 * A line on the surface with a hairline round it, never a dialog and never a
 * blocking state — REQUIREMENTS.md is explicit that upstream degradation
 * "should surface as a quiet inline note, not an error dialog", and the same
 * restraint is right for every other thing this screen has to mention. It is a
 * polite live region so a screen reader hears the change without losing its
 * place in the results.
 *
 * @param onDismiss when supplied, the whole line becomes the dismiss control.
 *   Notes about the catalogue lane pass null: they describe a condition rather
 *   than report an event, and dismissing one would only mean it reappeared on
 *   the next keystroke.
 */
@Composable
private fun NoticeLine(
    message: String,
    isProblem: Boolean,
    onDismiss: (() -> Unit)?,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .then(
                if (onDismiss != null) {
                    Modifier.clickable(role = Role.Button, onClick = onDismiss)
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (onDismiss == null) {
                    message
                } else {
                    "$message Tap to dismiss."
                }
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.caption,
            // The palette has one emphasis colour and no error colour, so a
            // problem is drawn in the primary text colour rather than in a red
            // this design system does not have.
            color = if (isProblem) colors.textPrimary else colors.textSecondary,
        )
    }
}

/** Screen 10's album grid: two cards across the content pane. */
private const val TABLET_ALBUM_COLUMNS: Int = 2

/** Enough skeleton rows to fill a phone screen, so the wait does not look like an empty result. */
private const val SKELETON_ROWS: Int = 6
