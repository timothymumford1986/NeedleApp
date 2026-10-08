package app.needler.feature.search.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.CRATE_LONG_PRESS_LABEL
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerChevronRightIcon
import app.needler.core.design.component.NeedlerClockIcon
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.NeedlerDropdownMenu
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerMoreIcon
import app.needler.core.design.component.NeedlerOnDeviceIcon
import app.needler.core.design.component.NeedlerPullButton
import app.needler.core.design.component.NeedlerRequestSheetOverlay
import app.needler.core.design.component.NeedlerSearchField
import app.needler.core.design.component.NeedlerSearchIcon
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.accessibleLabel
import app.needler.core.design.component.needlerRowActions
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.RequestStatus
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
 * One composable serves both. The only difference left is whether the Albums
 * block is a list of rows or a grid of cards; the way out of the screen is the
 * same control at both widths, for the reason given on [LEAVE_SEARCH_LABEL].
 * REQUIREMENTS.md "Tablet layout": "This is one
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
 * four different trailing treatments screen 03 shows — **Device**, **Server**,
 * **Pulling**, and a **Pull** button — are four values of
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
    onRetrySearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onAddTrackToCrate: (Track, Boolean) -> Unit,
    onAddAlbumToCrate: (Album, Boolean) -> Unit,
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

            // The pane's own width, which is not the window's: on a tablet this
            // screen sits between a nav rail and the player sidebar. The album
            // grid's column count is measured from it rather than fixed, so the
            // cards are a readable width at whatever width the host gives.
            // BoxWithConstraints and not the `onGloballyPositioned` below it,
            // because a column count written into state after layout renders one
            // frame at the wrong count - and the screenshot tests capture frames.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val albumColumns: Int = albumColumnsFor(wide, maxWidth - gutter * 2)
                LazyColumn(
                    state = resultsState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberKeyboardDismissOnScroll())
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
                    // Read out of the state once, into locals: `catalogueBanner` is a
                    // computed property, so re-reading it inside an item lambda would
                    // both recompute it and defeat the null check above it.
                    val notice: SearchNotice? = state.notice
                    val banner: SearchBanner? = state.catalogueBanner

                    if (notice != null) {
                        item(key = "notice") {
                            NoticeLine(
                                message = notice.message,
                                isProblem = notice.isProblem,
                                onDismiss = onDismissNotice,
                                // The crate's own count and total duration, under the sentence
                                // saying something went into it.
                                detail = if (notice.showsCrate) state.crateLine else null,
                            )
                            Spacer(modifier = Modifier.height(headerGap))
                        }
                    }

                    if (banner != null) {
                        item(key = "catalogue-banner") {
                            CatalogueBannerLine(banner = banner, onOpenSettings = onOpenSettings)
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
                            onRecentQuerySelect = onRecentQuerySelect,
                            onRetrySearch = onRetrySearch,
                        )

                        else -> resultBlocks(
                            state = state,
                            wide = wide,
                            albumColumns = albumColumns,
                            sectionGap = sectionGap,
                            headerGap = headerGap,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onPull = onPull,
                            onStopPull = onStopPull,
                            onPlayTrack = onPlayTrack,
                            onAddTrackToCrate = onAddTrackToCrate,
                            onAddAlbumToCrate = onAddAlbumToCrate,
                            onShowAll = onShowAll,
                            onLoadMore = onLoadMore,
                        )
                    }
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
                // "Queue the pull" with no connection. The write is journalled
                // either way; what changes is when the server hears about it, and
                // this is the last line read before the tap that decides.
                confirmLabel = state.pullConfirmLabel,
                // The server's own `quality_snapshot_summary` when it sent one,
                // which is the only account of what will be downloaded that is
                // guaranteed to be true, preceded offline by what becomes of the
                // request itself. Null falls back to the sheet's own line. See
                // SearchUiState.pullSheetNote.
                qualityNote = state.pullSheetNote,
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
/**
 * A scroll of the results puts the keyboard away.
 *
 * ## The trap this opens
 *
 * [SearchScreen]'s `autoFocus` raises the keyboard on arrival, which is right for
 * a screen whose only purpose is typing and is why it is there. What it also does
 * is cover the bottom of the window with about 200px of keyboard before the user
 * has done anything, and the two things underneath are the host's bottom
 * navigation bar and — on the idle screen — the **Clear recent searches** row.
 * Observed on a device: arriving at Search and deciding not to search left no
 * visible way off the tab, because the controls for leaving it were behind the
 * keyboard that had opened itself.
 *
 * The scroll is the gesture a user already makes there, and every list in the
 * platform treats it as "I am reading, not typing". Taking focus off the field on
 * the first scroll of the results dismisses the keyboard the same way, and
 * restores the row and the nav bar together.
 *
 * ## Why not simply stop auto-focusing
 *
 * Because the keyboard is correct on arrival. Search opened with `mInputShown=false`
 * before `autoFocus` existed and cost a tap before any search could start, on the
 * one screen that exists to be typed into. The defect is not that the keyboard
 * appears, it is that nothing dismissed it; this dismisses it.
 *
 * `onPreScroll` rather than `onPostScroll`, so the keyboard goes as the gesture
 * starts rather than after the list has already moved under it. Nothing is
 * consumed — [Offset.Zero] — so the scroll itself is untouched.
 */
@Composable
private fun rememberKeyboardDismissOnScroll(): NestedScrollConnection {
    val focusManager: FocusManager = LocalFocusManager.current
    return remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
}

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
 * Screen 03 puts a plain **Cancel** link to the right of the phone's field and
 * screen 10 a back arrow to the left of the tablet's. One callback always served
 * both, because they are one escape hatch; they are now one control as well, and
 * [LEAVE_SEARCH_LABEL] carries the two reasons. The tablet keeps its 560px cap
 * on the field so it does not stretch across the whole content pane.
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
 *
 * ## And the old query is selected, not cleared
 *
 * `search` is a bottom-navigation destination, so leaving the tab disposes this
 * composition but not [SearchViewModel]: the previous query is still in
 * [SearchUiState.query] when the user taps the library's search box again, which
 * is what `SUBSCRIPTION_TIMEOUT_MS` and REQUIREMENTS.md "Search behaviour"
 * between them intend. What was wrong was the caret. `BasicTextField`'s `String`
 * overload seeds its selection to `TextRange(0)`, so the first keystroke landed
 * at index 0 and **prepended**: typing "beastie" over a previous "dido"
 * searched for "beastiedido" and found nothing, and the empty result looked like
 * a fault in search itself.
 *
 * So the field owns a [TextFieldValue] and seeds it with the whole query
 * selected. The first keystroke replaces it, the way arriving at a field with
 * stale text in it should behave, and a user who came back to *edit* that query
 * still can: one tap in the text puts the caret where they tapped.
 *
 * Clearing the query on arrival instead was rejected, and the reason is in
 * [NeedlerSearchField]'s `TextFieldValue` overload: it costs a flag in a
 * `SavedStateHandle` to stop a rotation wiping the search, and it throws away
 * text the user may have meant to keep.
 *
 * ## Why the re-seed is an effect and not an `if`
 *
 * The ViewModel also changes this text on its own: a recent query, a suggestion,
 * the clear button. Those have to reach the field, so it re-seeds when
 * [SearchUiState.query] stops matching - with the caret at the end, because a
 * completion the user just chose is a starting point to type from, not something
 * to overtype.
 *
 * That comparison is in a [LaunchedEffect] **keyed on the query**, not run bare
 * on every recomposition. A bare `if` also fires on the recomposition a keystroke
 * itself triggers, where the field already holds the new text and `state.query`
 * is one frame behind it, and it would then reset the field to the older text and
 * drag the caret to the end - fighting the user mid-word, which is worse than the
 * defect being fixed. Keyed on the query, the effect runs only when the query
 * actually changed, and the value it sees is the latest one: the ViewModel's
 * [SearchUiState] arrives through a conflated `StateFlow`, so a composition never
 * sees a query older than the keystroke that caused it.
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

    // Selected, so the first keystroke replaces whatever the last visit left here.
    var query: TextFieldValue by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(0, state.query.length)))
    }
    LaunchedEffect(state.query) {
        if (state.query != query.text) {
            query = TextFieldValue(state.query, TextRange(state.query.length))
        }
    }

    val field: @Composable () -> Unit = {
        NeedlerSearchField(
            value = query,
            onValueChange = { next: TextFieldValue ->
                query = next
                onQueryChange(next.text)
            },
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
                // The back control is a 48dp target around a 24dp glyph, so it
                // carries 10dp of its own air on the leading side; the gutter is
                // pulled in by that much so the glyph lines up with the content
                // under it rather than sitting 10dp inside it. Both widths now,
                // because both widths now have the control.
                start = gutter - 10.dp,
                end = gutter,
                top = if (wide) spacing.step12 else spacing.step14,
            ),
        horizontalArrangement = Arrangement.spacedBy(if (wide) spacing.step8 else spacing.step5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerIconButton(contentDescription = LEAVE_SEARCH_LABEL, onClick = onCancel) {
            NeedlerStrokeIcon(
                pathData = PathChevronLeft,
                tint = colors.textPrimary,
                size = 24.dp,
            )
        }
        Box(
            modifier = if (wide) {
                Modifier.weight(1f).widthIn(max = 560.dp)
            } else {
                Modifier.weight(1f)
            },
        ) { field() }
    }
}

/**
 * The one way out, on both widths.
 *
 * Screen 03 draws a text **Cancel** to the right of the phone's field and screen
 * 10 a back arrow to the left of the tablet's, and reproducing both gave Search
 * two dismissal models and two escape gestures for one escape — with an `X`
 * inside the field on each, which clears the query and is a different act
 * entirely. A reader who learned the phone could not use the tablet.
 *
 * ## And the text label was taking the query's width
 *
 * The field is the one thing on this screen that must stay legible, and it was
 * the thing that shrank. `Cancel` is `rowTitle`, so at the 200% text scale
 * REQUIREMENTS.md "Accessibility" requires it drew about 150dp of a 390dp phone
 * — and `BasicTextField` is a single-line editor with no ellipsis, so the field
 * does not report that it has run out of room, it simply stops: the query
 * "khruangbin" rendered as "khruangb" with nothing to say it had been cut.
 *
 * A [NeedlerIconButton] is 44dp visual on a 48dp target at every text size,
 * because an icon does not scale with the font. That is 100dp of the phone's
 * width handed back to the query at 200%, and about 30dp at 100%, and it is the
 * same control the tablet already had.
 */
private const val LEAVE_SEARCH_LABEL: String = "Leave search"

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
    albumColumns: Int,
    sectionGap: Dp,
    headerGap: Dp,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onAddTrackToCrate: (Track, Boolean) -> Unit,
    onAddAlbumToCrate: (Album, Boolean) -> Unit,
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
            state = state,
            wide = wide,
            columns = albumColumns,
            onAlbumClick = onAlbumClick,
            onPull = onPull,
            onStopPull = onStopPull,
            onAddToCrate = onAddAlbumToCrate,
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
                onAddToCrate = onAddTrackToCrate,
            )
        }
    }

    if (catalogueAlbums.isNotEmpty()) {
        blockHeader(
            key = "albums-catalogue",
            title = CATALOGUE_ALBUMS_HEADER,
            // The one block on this screen holding records the user does not
            // have. Everything above it - the artists, the library's albums, the
            // songs - is theirs and plays; everything below it is a shopping
            // list. The two blocks were drawn in identical typography, at
            // identical geometry, with identical thumbnails, and the only
            // pre-attentive cue that the boundary had been crossed was the Pull
            // pill on the right of a row. That is a cue you have to read a row to
            // get, which is not a cue at all when the question is "which of
            // these do I own".
            //
            // A full-width rule above the header is the cheapest answer that
            // invents nothing: NeedlerHairline is what the pack already uses to
            // divide a list, and a horizontal line is the one mark the eye reads
            // as a boundary without being told.
            rule = true,
            // "from MusicBrainz" once the catalogue has answered, "from your
            // last sync" while it has not or cannot — these rows are then the
            // un-owned catalogue records the mirror happens to hold, and saying
            // "in your library" over a block of albums that are not is the one
            // claim this header cannot make. See SearchUiState.
            trailing = state.albumsSourceNote,
            topGap = if (first) 0.dp else sectionGap,
            headerGap = headerGap,
        )
        albumRows(
            keyPrefix = "pull",
            albums = catalogueAlbums,
            state = state,
            wide = wide,
            columns = albumColumns,
            onAlbumClick = onAlbumClick,
            onPull = onPull,
            onStopPull = onStopPull,
            onAddToCrate = onAddAlbumToCrate,
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
    state: SearchUiState,
    wide: Boolean,
    columns: Int,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onAddToCrate: (Album, Boolean) -> Unit,
) {
    if (wide) {
        // Screen 10 lays the albums out as a grid of cards. A LazyVerticalGrid
        // cannot be nested inside this LazyColumn, and splitting the screen into
        // two scrollers to get one would scroll the Artist block independently of
        // the albums under it, which the pack does not do. Chunking into rows
        // keeps one scroller and one scroll position.
        val rows: List<List<Album>> = albums.chunked(columns)
        items(
            count = rows.size,
            key = { index ->
                keyPrefix + "-album-row-" + rows[index].first().releaseGroupMbid.value
            },
        ) { index ->
            AlbumCardRow(
                albums = rows[index],
                columns = columns,
                state = state,
                onAlbumClick = onAlbumClick,
                onPull = onPull,
                onStopPull = onStopPull,
                onAddToCrate = onAddToCrate,
            )
        }
    } else {
        items(
            count = albums.size,
            key = { index -> keyPrefix + "-album-" + albums[index].releaseGroupMbid.value },
        ) { index ->
            AlbumRow(
                album = albums[index],
                state = state,
                onClick = onAlbumClick,
                onPull = onPull,
                onStopPull = onStopPull,
                onAddToCrate = onAddToCrate,
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

/**
 * @param rule draws a hairline across the list above the header, for the one
 *   boundary on this screen that is not simply "a new heading": the step from
 *   what the user has to what they do not.
 */
private fun LazyListScope.blockHeader(
    key: String,
    title: String,
    trailing: String?,
    topGap: Dp,
    headerGap: Dp,
    rule: Boolean = false,
) {
    item(key = "header-$key") {
        Column {
            // `topGap` is zero when this block is the first on the screen, and a
            // boundary at the top of a list divides it from nothing.
            if (rule && topGap > 0.dp) {
                // Half the gap above the rule and half below, so the line sits in
                // the middle of the space between the blocks rather than crowding
                // the heading it introduces.
                Spacer(modifier = Modifier.height(topGap / 2))
                NeedlerHairline()
                Spacer(modifier = Modifier.height(topGap / 2))
            } else if (topGap > 0.dp) {
                Spacer(modifier = Modifier.height(topGap))
            }
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
    state: SearchUiState,
    onClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onAddToCrate: (Album, Boolean) -> Unit,
) {
    NeedlerAlbumRow(
        title = album.title,
        subtitle = SearchFormat.albumRowSubtitle(album),
        minHeight = NeedlerTheme.sizes.albumRowMinHeight,
        onClick = { onClick(album) },
        contentDescription = albumRowDescription(album, state),
        artwork = {
            AlbumArtwork(
                album = album,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
            )
        },
        trailing = {
            AlbumTrailing(
                album = album,
                state = state,
                onPull = onPull,
                onStopPull = onStopPull,
                onAddToCrate = onAddToCrate,
            )
        },
    )
}

/** One row of screen 10's two-column album grid. */
@Composable
private fun AlbumCardRow(
    albums: List<Album>,
    columns: Int,
    state: SearchUiState,
    onAlbumClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onAddToCrate: (Album, Boolean) -> Unit,
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
                state = state,
                onClick = onAlbumClick,
                onPull = onPull,
                onStopPull = onStopPull,
                onAddToCrate = onAddToCrate,
                modifier = Modifier.weight(1f),
            )
        }
        // A trailing odd album leaves a gap the width of a card rather than a
        // card stretched to twice the width of its neighbours.
        repeat(columns - albums.size) {
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
    state: SearchUiState,
    onClick: (Album) -> Unit,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onAddToCrate: (Album, Boolean) -> Unit,
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
                contentDescription = albumRowDescription(album, state)
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
                // Compose's default is TextOverflow.Clip, which on a card this
                // narrow cut "Two Star & The Dream Police" to "Two Star & The
                // Dream" and stopped - no ellipsis, on a 2560px screen, beside a
                // library that draws the same record in full. A hard clip is
                // indistinguishable from a title that really ends there, which is
                // the one thing a truncation must never be. NeedlerAlbumRow, which
                // draws the phone's rows, has ellipsised since it was written; this
                // card is the copy that did not.
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = SearchFormat.albumRowSubtitle(album),
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AlbumTrailing(
            album = album,
            state = state,
            onPull = onPull,
            onStopPull = onStopPull,
            onAddToCrate = onAddToCrate,
        )
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
    state: SearchUiState,
    onPull: (Album) -> Unit,
    onStopPull: (Album) -> Unit,
    onAddToCrate: (Album, Boolean) -> Unit,
) {
    var crateMenuOpen: Boolean by remember(album.releaseGroupMbid.value) {
        mutableStateOf(false)
    }
    var pullMenuOpen: Boolean by remember(album.releaseGroupMbid.value) {
        mutableStateOf(false)
    }
    val busy: Boolean = state.busy
    val badge: NeedlerAlbumBadge? = rowBadge(album, state)

    if (state.offersPull(album)) {
        NeedlerPullButton(
            onClick = { onPull(album) },
            albumTitle = album.title,
            enabled = !busy,
        )
    } else {
        badge?.let { NeedlerStateBadge(badge = it) }
        when {
            // The crate control only for a record the server actually has. A catalogue result is
            // metadata - its tracks exist in MusicBrainz and nowhere else - so queueing one would
            // add nothing and claim it had; an album still being pulled has no files yet either.
            // The badge beside this is what says which of those a row is.
            album.isOwned -> NeedlerCrateControl(
                subject = SearchFormat.albumTitle(album.title),
                expanded = crateMenuOpen,
                onExpandedChange = { crateMenuOpen = it },
                onAddToCrate = { onAddToCrate(album, false) },
                onPlayNext = { onAddToCrate(album, true) },
                enabled = !busy,
            )

            // A pull in flight. Every other row on this screen carries something
            // in this column and these carried nothing at all, so the one record
            // the user is actually waiting on - and the only one they may have
            // asked for by mistake - was the one row with no way to act on it.
            isInFlight(album, state) -> StopPullControl(
                album = album,
                expanded = pullMenuOpen,
                onExpandedChange = { pullMenuOpen = it },
                onStopPull = { onStopPull(album) },
                enabled = !busy,
            )
        }
    }
}

/**
 * The badge this row wears, which is not always the one its [AlbumState] implies.
 *
 * A request this session placed has been answered by the server and has not yet
 * reached the mirror, so the album is still `NotOwned` while the server is
 * already acting on it. [SearchUiState.placedPulls] is the only record of that
 * window, and drawing it is what stops the row offering a **Pull** it has already
 * placed.
 */
private fun rowBadge(album: Album, state: SearchUiState): NeedlerAlbumBadge? {
    val placed: RequestStatus? = state.placedPull(album)
    return when {
        placed == null -> albumBadge(album.state)
        // The server is acquiring it. No percentage: nothing has reported one
        // yet, and a 0% would read as a stall rather than as a start.
        placed == RequestStatus.ACCEPTED -> NeedlerAlbumBadge.Pulling()
        // Pending approval, and a pull queued on the device, are both waits on
        // somebody else - an administrator, or a connection.
        else -> NeedlerAlbumBadge.Waiting
    }
}

/** Whether something is happening to this record that the user could stop. */
private fun isInFlight(album: Album, state: SearchUiState): Boolean =
    state.placedPull(album) != null ||
        album.state is AlbumState.Acquiring ||
        album.state is AlbumState.PendingApproval

/**
 * The `⋯` on a row whose pull has not finished.
 *
 * One item, and a menu rather than a bare button for the reason the crate control
 * is one: every trailing control on this list is the same three-dot glyph opening
 * the actions for that row, and a row that answered the same gesture with an
 * immediate, irreversible stop would be the one control on the screen that fires
 * on the first tap. The menu is also where "open this in Pulls" goes when this
 * module is given a way to navigate there, which is in the handover notes.
 *
 * Stopping is deliberately not styled as destructive. REQUIREMENTS.md reserves
 * `#e8908a` for data the user is about to lose, and a pull that never landed has
 * cost them nothing but time — the same reasoning `NeedlerStateBadge` gives for
 * not drawing a failed pull in that colour.
 */
@Composable
private fun StopPullControl(
    album: Album,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStopPull: () -> Unit,
    enabled: Boolean,
) {
    val colors = NeedlerTheme.colors
    val title: String = SearchFormat.albumTitle(album.title)
    Box {
        NeedlerIconButton(
            contentDescription = "Pull actions for " + title + ". Stop this pull.",
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            visualSize = 32.dp,
            shape = NeedlerTheme.shapes.pill,
        ) {
            NeedlerMoreIcon(tint = colors.textMuted, size = 18.dp)
        }
        NeedlerDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Stop pulling " + title
                    role = Role.Button
                    onClick(label = STOP_PULL_LABEL) {
                        onExpandedChange(false)
                        onStopPull()
                        true
                    }
                },
                text = {
                    Text(
                        text = STOP_PULL_LABEL,
                        style = NeedlerTheme.typography.body,
                        color = if (enabled) colors.textPrimary else colors.disabled,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onStopPull()
                },
            )
        }
    }
}

private const val STOP_PULL_LABEL: String = "Stop this pull"

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
    onAddToCrate: (Track, Boolean) -> Unit,
) {
    val playable: Boolean = track.hasPlayableFile
    val isPlaying: Boolean = track.key == nowPlayingTrackKey
    val subtitle: String = SearchFormat.songRowSubtitle(track)
    var crateMenuOpen: Boolean by remember(track.key.canonicalString) { mutableStateOf(false) }
    // The whole reading, because the gesture modifier clears the row's own semantics.
    // NeedlerAlbumRow would have appended ", playing" to whatever description it was given,
    // so with those cleared this has to append it instead - and must not when the row keeps
    // them, or TalkBack says it twice.
    val spoken: String = buildString {
        append(track.title)
        append(", ")
        append(subtitle.replace(" · ", ", "))
        SearchFormat.spokenDuration(track.durationMs)?.let { duration ->
            append(", ")
            append(duration)
        }
        if (!playable) append(", not in your library")
        if (playable && isPlaying) append(", playing")
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerAlbumRow(
            title = track.title,
            subtitle = subtitle,
            modifier = if (playable) {
                Modifier
                    .weight(1f)
                    // The long press played the song and replaced the crate, because
                    // `clickable` fires on the release however long the hold was. It opens
                    // the crate menu now and the tap is consumed rather than firing behind
                    // it: this was one of the three gestures that could silently lose a
                    // queue.
                    .needlerRowActions(
                        description = spoken,
                        onTap = { onPlay(track) },
                        tapLabel = "Play",
                        onLongPress = { crateMenuOpen = true },
                        longPressLabel = CRATE_LONG_PRESS_LABEL,
                    )
            } else {
                Modifier.weight(1f)
            },
            minHeight = NeedlerTheme.sizes.albumRowMinHeight,
            isPlaying = isPlaying,
            // Handed to the modifier whenever there is one: two clickables on one row is a
            // tap that fires twice.
            onClick = null,
            contentDescription = spoken,
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
        // Beside the row rather than in its trailing slot: the gesture modifier clears the
        // row's descendants, so a control inside would be drawn and unreachable to TalkBack.
        if (playable) {
            NeedlerCrateControl(
                subject = SearchFormat.albumTitle(track.title),
                expanded = crateMenuOpen,
                onExpandedChange = { crateMenuOpen = it },
                onAddToCrate = { onAddToCrate(track, false) },
                onPlayNext = { onAddToCrate(track, true) },
            )
        }
    }
}

/** `Mordechai, Khruangbin, 2024, Device` — the whole row in one phrase. */
private fun albumRowDescription(album: Album, state: SearchUiState): String {
    val badge: NeedlerAlbumBadge? = rowBadge(album, state)
    return buildString {
        append(album.title)
        append(", ")
        append(SearchFormat.albumRowSubtitle(album).replace(" · ", ", "))
        if (badge != null) {
            append(", ")
            append(badge.accessibleLabel())
        }
        // The green check the pack puts on the artwork of a complete download is
        // already covered by the Device badge, so it is not repeated; what is
        // worth saying is when a *pinned* album is not yet fully down.
        if (album.state is AlbumState.Pinned && !album.showsOnDeviceCheck) {
            append(", pulling to device")
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
            icon = QueryRowIcon.HISTORY,
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
 * A search settled and neither lane had anything — which, offline, means the
 * mirror settled and the other lane never ran.
 *
 * The copy differs by what was actually searched, and that is
 * [SearchUiState.emptyResultDetail]'s decision rather than this block's: the
 * sentence here claimed a MusicBrainz lookup on a device that had no network,
 * so it is the thing under test and belongs on the state where a test can reach
 * it without rendering a screen. See [SearchUiState.catalogueAnswered] for which
 * signal that decision reads and which one it refuses to.
 */
private fun LazyListScope.emptyResultBlock(
    state: SearchUiState,
    sectionGap: Dp,
    headerGap: Dp,
    onSuggestionSelect: (SearchSuggestion) -> Unit,
    onRecentQuerySelect: (String) -> Unit,
    onRetrySearch: () -> Unit,
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
                text = state.emptyResultDetail,
                style = typography.body,
                color = colors.textSecondary,
            )
            // Offered on exactly the branch [SearchUiState.showRetry] allows: the
            // half of the search that never ran is the half that may run now, and
            // this is otherwise a screen of prose with nothing on it to press. A
            // search the catalogue did answer gets no retry, because asking the
            // same question of the same answer is not a next step.
            if (state.showRetry) {
                NeedlerSecondaryButton(
                    text = "Try again",
                    onClick = onRetrySearch,
                    size = NeedlerButtonSize.Small,
                    contentDescription = "Search again for " + state.query.trim(),
                )
            }
        }
    }

    val recents: List<String> = state.emptyResultRecents
    if (!state.showSuggestions && recents.isEmpty()) return

    blockHeader(
        key = "suggestions",
        title = if (state.showSuggestions) "Suggestions" else "Recent searches",
        trailing = null,
        topGap = sectionGap,
        headerGap = headerGap,
    )

    if (!state.showSuggestions) {
        items(count = recents.size, key = { index -> "empty-recent-" + recents[index] }) { index ->
            val text: String = recents[index]
            QueryRow(
                text = text,
                detail = null,
                icon = QueryRowIcon.HISTORY,
                spoken = "Search again for $text",
                onClick = { onRecentQuerySelect(text) },
            )
        }
        return
    }

    items(
        count = state.suggestions.size,
        key = { index -> "suggestion-" + state.suggestions[index].text },
    ) { index ->
        val suggestion: SearchSuggestion = state.suggestions[index]
        QueryRow(
            text = suggestion.text,
            detail = suggestionKindLabel(suggestion.kind),
            icon = QueryRowIcon.SEARCH,
            spoken = "Search for " + suggestion.text + ", " + suggestionKindLabel(suggestion.kind),
            onClick = { onSuggestionSelect(suggestion) },
        )
    }
}

/**
 * The quiet word on the right of a completion: what kind of thing it names.
 *
 * Three kinds and three labels. [SuggestionKind.QUERY] drew nothing, so a list
 * read "artist", "album", and then a blank where the third label should have
 * been — which reads as a label that failed to render rather than as a kind with
 * no name. It is a search string the server thinks is worth trying, so that is
 * what it is called.
 */
private fun suggestionKindLabel(kind: SuggestionKind): String = when (kind) {
    SuggestionKind.ARTIST -> "artist"
    SuggestionKind.ALBUM -> "album"
    SuggestionKind.QUERY -> "search"
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
    icon: QueryRowIcon,
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
            when (icon) {
                QueryRowIcon.SEARCH -> NeedlerSearchIcon(tint = colors.textMuted, size = 18.dp)
                QueryRowIcon.HISTORY -> NeedlerClockIcon(tint = colors.textMuted, size = 18.dp)
            }
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
 * Which glyph a [QueryRow] leads with.
 *
 * Both kinds of row put text back in the field, and they are not the same thing:
 * one is a completion of what is being typed now, the other is something typed
 * before. Every row of both drew the magnifier, so the history looked like a list
 * of searches the app was offering rather than a record of the user's own.
 *
 * A clock for history is the platform convention and the one every search field
 * on the phone already uses, so it is read without being learned.
 */
private enum class QueryRowIcon {
    /** A completion from `suggest`. */
    SEARCH,

    /** Something searched for before. */
    HISTORY,
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
    detail: String? = null,
) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val spoken: String = buildString {
        append(message)
        if (detail != null) {
            append(" ")
            append(detail.replace(" · ", ", "))
        }
        if (onDismiss != null) append(" Tap to dismiss.")
    }
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
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = message,
                style = NeedlerTheme.typography.caption,
                // The palette has one emphasis colour and no error colour, so a
                // problem is drawn in the primary text colour rather than in a red
                // this design system does not have.
                color = if (isProblem) colors.textPrimary else colors.textSecondary,
            )
            // The crate's count and total duration, when the message is about the crate.
            if (detail != null) {
                Text(
                    text = detail,
                    style = NeedlerTheme.typography.caption,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/**
 * The line under the field about the catalogue lane.
 *
 * ## What was wrong with one line for four states
 *
 * A search in progress, a degraded upstream, an expired sign-in and no
 * connection were drawn as the identical rounded grey block: same fill, same
 * hairline, same 13sp secondary text, no icon, no action. Two of those four
 * resolve themselves and two do not, and one of the two that does not is fixed
 * by two taps the banner was describing in prose and not offering. Put side by
 * side the four images are indistinguishable without reading the sentence, which
 * is the opposite of what a banner is for: a banner is read at a glance or it is
 * not read.
 *
 * ## What each one draws now
 *
 * | Kind | Glyph | Hue | Action |
 * | --- | --- | --- | --- |
 * | [SearchBannerKind.PROGRESS] | the search glyph | muted | none |
 * | [SearchBannerKind.DEGRADED] | a warning triangle | secondary | none |
 * | [SearchBannerKind.EXPIRED] | a padlock | accent, and an accent border | **Sign in** |
 * | [SearchBannerKind.OFFLINE] | the phone-and-check | positive | none |
 * | [SearchBannerKind.PROBLEM] | a warning triangle | secondary | none |
 *
 * The weight runs with the hue. Progress is the quietest thing on the screen
 * because it is about to stop being true; the expired session is the only one
 * that takes the accent border, because it is the only one with something for the
 * user to do. Nothing is encoded in colour alone: each kind has its own glyph and
 * its own sentence, and the one that can be acted on also has a button.
 *
 * The offline glyph is `NeedlerOnDeviceIcon` in the positive green, which is
 * exactly what `:feature:library`'s own offline note draws. The same condition
 * was iconised on one screen and not the other; it is the same mark on both now.
 *
 * REQUIREMENTS.md is unchanged by any of this: none of the four is a dialog, none
 * blocks, and none hides the results under it.
 */
@Composable
private fun CatalogueBannerLine(banner: SearchBanner, onOpenSettings: () -> Unit) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val tint: Color = when (banner.kind) {
        SearchBannerKind.PROGRESS -> colors.textMuted
        SearchBannerKind.DEGRADED, SearchBannerKind.PROBLEM -> colors.textSecondary
        SearchBannerKind.EXPIRED -> colors.accent
        SearchBannerKind.OFFLINE -> colors.positive
    }
    val border: Color = if (banner.kind == SearchBannerKind.EXPIRED) tint else colors.hairline
    val spoken: String = buildString {
        append(bannerPrefix(banner.kind))
        append(banner.message)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, border, shape)
            .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step5),
            verticalAlignment = Alignment.Top,
        ) {
            when (banner.kind) {
                SearchBannerKind.PROGRESS -> NeedlerSearchIcon(tint = tint, size = 16.dp)
                SearchBannerKind.DEGRADED, SearchBannerKind.PROBLEM ->
                    NeedlerStrokeIcon(pathData = PATH_WARNING, tint = tint, size = 16.dp)

                SearchBannerKind.EXPIRED ->
                    NeedlerStrokeIcon(pathData = PATH_LOCKED, tint = tint, size = 16.dp)

                SearchBannerKind.OFFLINE -> NeedlerOnDeviceIcon(tint = tint, size = 16.dp)
            }
            Text(
                text = banner.message,
                style = NeedlerTheme.typography.caption,
                // The sentence stays readable text rather than taking the glyph's
                // hue: a whole paragraph in the accent is a paragraph nobody reads
                // as prose, and the hue has already done its work on the mark.
                color = if (banner.kind == SearchBannerKind.PROGRESS) {
                    colors.textMuted
                } else {
                    colors.textSecondary
                },
                modifier = Modifier.weight(1f),
            )
        }
        when (banner.action) {
            SearchBannerAction.SIGN_IN -> NeedlerSecondaryButton(
                text = "Sign in",
                onClick = onOpenSettings,
                size = NeedlerButtonSize.Small,
                contentDescription = "Sign in again, in Settings",
            )

            null -> Unit
        }
    }
}

/**
 * What a screen reader hears before the sentence, so the kind is spoken as well
 * as drawn.
 *
 * The glyph is decorative - `NeedlerStrokeIcon` clears its own semantics - so
 * without this the four banners read identically to TalkBack, which is the same
 * defect in the other channel.
 */
private fun bannerPrefix(kind: SearchBannerKind): String = when (kind) {
    SearchBannerKind.PROGRESS -> ""
    SearchBannerKind.DEGRADED, SearchBannerKind.PROBLEM -> "Warning. "
    SearchBannerKind.EXPIRED -> "Sign-in expired. "
    SearchBannerKind.OFFLINE -> "Offline. "
}

/**
 * A warning triangle with a bang in it.
 *
 * Drawn here rather than taken from `:core:design`, which owns no warning mark:
 * the 21 screens of the pack draw none, so there is no `d` attribute to
 * transcribe and nothing in the icon set to borrow. `Icons.kt` says in as many
 * words that a screen needing a glyph the design system does not own may draw it
 * with [NeedlerStrokeIcon] on the pack's own 24-unit viewport, which is what
 * this and [PATH_LOCKED] do: same viewport, same 1.8-unit round-capped stroke.
 *
 * It belongs in `:core:design` the moment a second screen wants it, and that is
 * in the handover notes with the rest.
 */
private const val PATH_WARNING: String = "M12 4L2.5 20h19zM12 10v4M12 17.2v.1"

/** A closed padlock: the body, the shackle, and the keyhole. */
private const val PATH_LOCKED: String =
    "M5.5 10.5h13v9h-13zM8.5 10.5V7.5a3.5 3.5 0 017 0v3M12 14v2.5"

/**
 * How many album cards fit across [contentWidth], which is the pane this screen
 * was given minus its own gutters.
 *
 * ## Why this is measured and not two
 *
 * It was `2`, and the screen therefore drew two columns at every width a tablet,
 * a foldable or a desktop window could give it. The library's grid is four
 * across on an Expanded width, so the two screens disagreed about a pane they
 * share.
 *
 * They disagree because the cells are not the same cell. The library's are
 * artwork tiles with the caption *underneath*, so a cell is as narrow as its
 * cover; a search card is a **row** — 56dp of artwork, two lines of text and
 * either a state badge or a **Pull** pill, side by side. Measured off
 * `screenshots/search-results-tablet.png` at 2px to the dp, the pack's content
 * pane is 783dp and its gutters leave 703dp; four of those with 12dp between
 * them is 167dp a card, of which the artwork and the card's own padding take 88
 * and the trailing Pull pill about 80, leaving nothing at all for the title.
 * Four columns of this card is not a denser grid, it is four clipped ones.
 *
 * So the count comes from a minimum card width instead of from a number. At the
 * pack's 783dp pane it is 2, which is what screen 10 draws and what fits; a
 * wider pane — a 1600dp desktop window, a tablet with the sidebar collapsed —
 * gets 3 or 4 without anything here changing. [MIN_ALBUM_CARD] is the width at
 * which a card still holds an album title and an action on one line.
 *
 * The phone is always one column of rows, not cards, so the question does not
 * arise there.
 */
private fun albumColumnsFor(wide: Boolean, contentWidth: Dp): Int {
    if (!wide) return 1
    val gap: Dp = 12.dp
    val fits: Int = ((contentWidth + gap).value / (MIN_ALBUM_CARD + gap).value).toInt()
    return fits.coerceIn(MIN_ALBUM_COLUMNS, MAX_ALBUM_COLUMNS)
}

/**
 * The narrowest an album card may be.
 *
 * 340dp: 32dp of card padding, 56dp of artwork, 14dp to the text, 80dp for the
 * widest trailing control (the **Pull** pill), 14dp before it, and the ~144dp
 * left over is about twenty characters of `rowTitle` a line — a title read
 * rather than recognised, which is the same budget `NeedlerAlbumBadge.NeedsAttention`
 * was shortened to protect.
 */
private val MIN_ALBUM_CARD: Dp = 340.dp

/** Screen 10 draws two, and two is also the fewest that is a grid rather than a list. */
private const val MIN_ALBUM_COLUMNS: Int = 2

/** The library's own count on an Expanded width, so the two screens cannot disagree upwards. */
private const val MAX_ALBUM_COLUMNS: Int = 4

/** Enough skeleton rows to fill a phone screen, so the wait does not look like an empty result. */
private const val SKELETON_ROWS: Int = 6
