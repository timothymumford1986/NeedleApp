@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerArtwork
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerButtonTone
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.NeedlerFavouriteButton
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerPullButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.AlbumFormatLabel
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheet
import app.needler.feature.library.common.RequestSheetState
import app.needler.feature.library.common.albumBadge
import app.needler.feature.library.common.albumFormatSpokenLabel
import app.needler.feature.library.common.favouriteContentDescription
import app.needler.feature.library.common.showsOnDeviceCheck

/**
 * The artist screen: owned albums first, then the rest of the discography.
 *
 * This is the one screen where the two server lanes are visible side by side.
 * The first section is the mirror — present offline, always accurate about what
 * you own. The second is the MusicBrainz catalogue by way of
 * `/api/v1/artists/{mbid}/releases`, and every row in it carries a **Pull**.
 *
 * When the catalogue half cannot be fetched the first half is still drawn, with
 * one line saying why the rest is missing. Failing the whole screen because the
 * optional half of it needs a connection would defeat the mirror.
 *
 * ## The discography is paged, and says so
 *
 * `/api/v1/artists/{mbid}/releases` answers fifty release groups at a time and reports whether
 * there are more. Nothing used to ask: the first page was fetched, drawn, and presented as the
 * artist's complete output. The one row under the list - see [ArtistUiState.discographyMoreRow] -
 * is where the rest is asked for, and it carries the server's own count of how much of the
 * discography has been looked up.
 *
 * ## Play, Shuffle and a star
 *
 * This screen had none of the three. The album screen had Play, Shuffle and Pull
 * local, which the device audit noted made the omission starker rather than
 * smaller. Play and Shuffle act on every playable track of every owned album, in
 * the order this screen lists them; the star is the same binary favourite an album
 * and a track now carry, and `getStarred2` returns starred artists alongside both.
 *
 * Pull to device is deliberately **not** here. Pinning is per album - `PinRepository`
 * is keyed on a release group and REQUIREMENTS.md's storage rules are written per
 * album - so an artist-wide pin would be a new concept rather than a missing
 * button, and it would silently commit a listener to however many gigabytes that
 * artist happens to be.
 *
 * ## An un-owned artist is not a dead end
 *
 * Catalogue search returns artists the library has never heard of, labels them "Not in
 * your library yet", and invites the tap. That tap used to land on "That artist is not
 * here" - a screen with the artist unnamed, no action but Back, and a sentence blaming
 * the network for a catalogue lookup that had plainly succeeded seconds earlier.
 *
 * Three things follow from fixing it, and they are the same three for an owned artist
 * whose DroppedNeedle id can never be an MBID, because both are "the discography could
 * not be listed" and differ only in why. The artist is **named** from the route when the
 * mirror has no row. The reason is **stated honestly**, never as a network failure unless
 * it was one. And a **Pull all** stays on screen wherever there is an un-owned release
 * to ask for, because REQUIREMENTS.md "Scope" lists requesting missing albums as one of
 * v1's four jobs and this is the screen that job starts from.
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onPull: (Album) -> Unit,
    onPullArtist: () -> Unit,
    onRetryDiscography: () -> Unit,
    onShowMoreDiscography: () -> Unit,
    onFindInCatalogue: () -> Unit,
    onOpenArtist: (ArtistMbid) -> Unit,
    // The namesake rows' own tap, carrying the name and comment the catalogue gave for a
    // row the mirror has no artist for. Defaulted to [onOpenArtist] so a preview or a
    // bounds test that has nothing to carry still gets a working tap and today's nameless
    // header, rather than an inert one; `ArtistRoute` requires it, which is where it
    // matters that a host cannot forget. See that KDoc for why this is a second callback
    // and not a wider [onOpenArtist].
    onOpenCatalogueArtist: (ArtistMbid, String, String?) -> Unit =
        { mbid, _, _ -> onOpenArtist(mbid) },
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    onAddAlbumToCrate: (ReleaseGroupMbid, Boolean) -> Unit,
    onToggleFavourite: () -> Unit,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirmRequest: () -> Unit,
    onDismissRequestSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass == WindowWidthSizeClass.Expanded
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gutter - 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeedlerIconButton(contentDescription = "Back", onClick = onBack) {
                    NeedlerStrokeIcon(
                        pathData = PathChevronLeft,
                        tint = colors.textPrimary,
                        size = 24.dp,
                    )
                }
                // Only for an artist the mirror actually has. `star` on an artist the server
                // has never heard of would be a write it cannot key, and `getStarred2` would
                // never return it - so the control is absent rather than present and futile.
                if (state.artist != null) {
                    NeedlerFavouriteButton(
                        isFavourite = state.isFavourite,
                        contentDescription = favouriteContentDescription(
                            isFavourite = state.isFavourite,
                            name = state.spokenName,
                        ),
                        onToggle = onToggleFavourite,
                        visualSize = 44.dp,
                        glyphSize = 22.dp,
                    )
                }
            }

            when {
                state.loading -> ArtistSkeleton(gutter = gutter)
                state.notFound -> ArtistNotFound(
                    gutter = gutter,
                    reason = if (state.discographyUnavailable) {
                        catalogueNoticeMessage(
                            error = state.discographyError,
                            offline = state.offline,
                            notInCatalogue = state.artistNotInCatalogue,
                        )
                    } else {
                        null
                    },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = gutter,
                        end = gutter,
                        bottom = spacing.step12,
                    ),
                ) {
                    item(key = "header") { ArtistHeader(state) }

                    if (state.canPlay || state.canPullArtist || state.canRetryDiscography) {
                        item(key = "actions") {
                            Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step5))
                            ArtistActions(
                                state = state,
                                onPlay = onPlay,
                                onShuffle = onShuffle,
                                onAddToCrate = onAddToCrate,
                                onPullArtist = onPullArtist,
                                onRetryDiscography = onRetryDiscography,
                                onFindInCatalogue = onFindInCatalogue,
                            )
                        }
                    }

                    val notice: AlbumNotice? = state.notice
                    if (notice != null) {
                        item(key = "notice") {
                            NoticeLine(
                                message = notice.message,
                                // The crate's count and total duration under the sentence
                                // that says something went into it.
                                detail = if (notice is AlbumNotice.AddedToCrate) {
                                    state.crateLine
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    if (state.ownedAlbums.isNotEmpty()) {
                        item(key = "owned-header") {
                            SectionSpacer()
                            // No `trailing` count. It was the same expression as the
                            // header's subtitle, so the screen said "6 albums" and then
                            // "6 albums" again a hundred pixels below it; the subtitle
                            // keeps the figure because it also says how many more there
                            // are to pull. See [ArtistHeader].
                            NeedlerSectionHeader(title = "In your library")
                        }
                        ownedRows(
                            albums = state.ownedAlbums,
                            credit = state.creditedName,
                            busy = state.busy,
                            onAlbumClick = onAlbumClick,
                            onPlayAlbum = onPlayAlbum,
                            onAddAlbumToCrate = onAddAlbumToCrate,
                        )
                    }

                    if (state.catalogueAlbums.isNotEmpty()) {
                        item(key = "catalogue-header") {
                            SectionSpacer()
                            NeedlerSectionHeader(title = "More from this artist")
                        }
                        catalogueRows(
                            albums = state.catalogueAlbums,
                            credit = state.creditedName,
                            busy = state.busy,
                            onAlbumClick = onAlbumClick,
                            onPull = onPull,
                        )

                        // The legitimate half of the old unavailable sentence. These rows came out
                        // of the mirror and the lane that refreshes them did not answer, so the
                        // list may be short - and nothing else on the screen could tell a reader
                        // that the nine records above are a cache rather than a catalogue.
                        if (state.discographyIncomplete) {
                            item(key = "catalogue-incomplete") {
                                NoticeLine(message = CATALOGUE_INCOMPLETE)
                            }
                        }
                    }

                    // The next page. Outside the block above on purpose: a first page can be fifty
                    // release groups the user already owns, which the join drops, leaving no
                    // catalogue rows and a discography that is nonetheless only begun.
                    moreRowItem(
                        row = state.discographyMoreRow,
                        name = state.spokenName,
                        onClick = onShowMoreDiscography,
                    )

                    if (state.discographyUnavailable) {
                        item(key = "catalogue-unavailable") {
                            SectionSpacer()
                            NoticeLine(
                                message = catalogueNoticeMessage(
                                    error = state.discographyError,
                                    offline = state.offline,
                                    notInCatalogue = state.artistNotInCatalogue,
                                ),
                            )
                        }
                    }

                    // What the catalogue knows by this name, for the artist whose id cannot reach
                    // it. Rows rather than an automatic jump: MusicBrainz holds several artists per
                    // name and binding the user's library to the wrong one would put a stranger's
                    // discography under their own records. See `ArtistViewModel.onFindInCatalogue`.
                    if (state.catalogueNamesakes.isNotEmpty()) {
                        item(key = "namesakes-header") {
                            SectionSpacer()
                            NeedlerSectionHeader(title = "In the catalogue")
                        }
                        namesakeRows(
                            artists = state.catalogueNamesakes,
                            onOpenArtist = onOpenCatalogueArtist,
                        )
                    }

                    if (state.noNamesakesFound) {
                        item(key = "namesakes-empty") {
                            SectionSpacer()
                            NoticeLine(message = NO_NAMESAKES)
                        }
                    }

                    if (state.namesakeSearchFailed) {
                        item(key = "namesakes-failed") {
                            SectionSpacer()
                            NoticeLine(message = NAMESAKE_SEARCH_FAILED)
                        }
                    }

                    if (state.hasNothing && !state.discographyUnavailable) {
                        item(key = "empty") {
                            SectionSpacer()
                            Text(
                                text = "Nothing by this artist has been synced or found yet.",
                                style = NeedlerTheme.typography.body,
                                color = NeedlerTheme.colors.textSecondary,
                            )
                        }
                    }

                    // The lookup finished and the catalogue had nothing more. Said rather than
                    // left blank: with owned albums above it, a missing "More from this artist"
                    // is indistinguishable from an artist who recorded nothing else, and the
                    // device showed exactly that for an artist with a dozen un-owned records.
                    // Guarded on `hasNothing` so it does not follow the line above saying the
                    // same thing about a screen with nothing on it at all.
                    if (state.discographyEmpty && !state.hasNothing) {
                        item(key = "catalogue-empty") {
                            SectionSpacer()
                            NoticeLine(message = CATALOGUE_COMPLETE)
                        }
                    }
                }
            }
        }

        val sheet: RequestSheetState? = state.requestSheet
        if (sheet != null) {
            RequestSheet(
                sheet = sheet,
                busy = state.busy,
                onMonitorArtistChange = onMonitorArtistChange,
                onConfirm = onConfirmRequest,
                onCancel = onDismissRequestSheet,
            )
        }
    }
}

/**
 * Everything this screen can do about the artist as a whole.
 *
 * Three actions, each present only when it can succeed, and between them they mean this
 * screen is never a dead end: **Play** and **Shuffle** when there is something owned to
 * play, **Pull all** when the catalogue knows un-owned releases to ask for, and **Try
 * again** when the discography could not be listed but might be next time.
 *
 * The one case with none of them is an artist whose whole discography is already in the
 * library and whose id can never reach the catalogue — and that screen is a full list of
 * their records, which is not a dead end either.
 *
 * A `FlowRow` for the same reason the library's controls are one: at 200% text the
 * labels no longer fit across a 390dp phone, and REQUIREMENTS.md "Accessibility"
 * requires text to scale to 200% without clipping. Every button is the album screen's
 * own `Medium` size, so the two screens' primary actions are the same shape and weight.
 */
@Composable
private fun ArtistActions(
    state: ArtistUiState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    onPullArtist: () -> Unit,
    onRetryDiscography: () -> Unit,
    onFindInCatalogue: () -> Unit,
) {
    val spacing = NeedlerTheme.spacing
    val name: String = state.spokenName
    var crateMenuOpen: Boolean by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        verticalArrangement = Arrangement.spacedBy(spacing.step4),
    ) {
        if (state.canPlay) {
            NeedlerPrimaryButton(
                text = "Play",
                onClick = onPlay,
                size = NeedlerButtonSize.Medium,
                enabled = !state.busy,
                leadingIcon = { tint ->
                    NeedlerStrokeIcon(pathData = PathPlay, tint = tint, size = 18.dp, filled = true)
                },
                contentDescription = "Play everything by " + name + " in your library",
            )
            NeedlerSecondaryButton(
                text = "Shuffle",
                onClick = onShuffle,
                size = NeedlerButtonSize.Medium,
                enabled = !state.busy,
                contentDescription = "Shuffle everything by " + name + " in your library",
            )
        }
        // The pack's one filled green button is the pull, and it is green because acquiring
        // music is not playback. On an artist nobody owns this is the only primary action
        // there is, which is the point: the route used to end here with a Back button.
        if (state.canPullArtist) {
            NeedlerPrimaryButton(
                text = "Pull all",
                onClick = onPullArtist,
                tone = NeedlerButtonTone.Positive,
                size = NeedlerButtonSize.Medium,
                enabled = !state.busy,
                leadingIcon = { tint ->
                    NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 18.dp)
                },
                contentDescription = "Pull all " + state.pullableAlbums.size +
                    " albums by " + name + " that you do not own",
            )
        }
        // Offered only where retrying could work. An artist whose id can never reach the
        // catalogue is excluded, because a button that re-issues a request the server answers
        // 400 to is worse than no button.
        if (state.canRetryDiscography) {
            NeedlerSecondaryButton(
                text = "Try again",
                onClick = onRetryDiscography,
                size = NeedlerButtonSize.Medium,
                enabled = !state.busy,
                contentDescription = "Look up " + name + " in the catalogue again",
            )
        }
        // The one action a name-derived artist can take, and the action [CATALOGUE_NO_MBID] names.
        // Mutually exclusive with Try again by construction - `canRetryDiscography` excludes
        // `artistNotInCatalogue` and this requires it - so the row never offers both.
        if (state.canFindInCatalogue) {
            NeedlerSecondaryButton(
                text = if (state.searchingCatalogue) "Searching…" else "Search the catalogue",
                onClick = onFindInCatalogue,
                size = NeedlerButtonSize.Medium,
                enabled = !state.busy && !state.searchingCatalogue,
                contentDescription = "Search the MusicBrainz catalogue for artists named " + name,
            )
        }
        // Last in the row, after every control that has a name. An overflow is not a peer of the
        // actions beside it: it is where things go that have nowhere else, so it belongs where the
        // eye stops rather than interrupting the run of named actions. Third, it pushed Pull all -
        // the one filled green button on the screen - out past the dots.
        //
        // Still guarded by `canPlay`: there is nothing to queue from an artist with no owned
        // records, so the menu is absent rather than present and empty. Composition order is the
        // drawn order and the spoken order at once, and nothing in this row sets a
        // `traversalIndex`, so the move keeps them matching.
        if (state.canPlay) {
            NeedlerCrateControl(
                subject = "everything by " + name,
                expanded = crateMenuOpen,
                onExpandedChange = { crateMenuOpen = it },
                onAddToCrate = { onAddToCrate(false) },
                onPlayNext = { onAddToCrate(true) },
                enabled = !state.busy,
                emphasised = true,
                visualSize = 44.dp,
            )
        }
    }
}

/**
 * The artist's name, their line of counts, and — now — their picture.
 *
 * ## Why there was no image, and why there is one
 *
 * This header drew two `Text`s and nothing else, so every artist page opened as a
 * wall of words while `Artist.artwork` sat populated and unused — and search, one
 * tap earlier, had already shown the same artist as a round avatar. REQUIREMENTS.md
 * "Design system" gives artwork a shape vocabulary and the pack draws an artist as a
 * circle wherever one appears; a screen that is *about* one artist is the last place
 * that should be the exception.
 *
 * `:core:design`'s `NeedlerArtwork` is called directly rather than through a wrapper.
 * `:feature:search` has an `ArtistAvatar` that is this one call and nothing else, and
 * it is `internal` to that module; promoting it would put a second name on a single
 * call, and copying it here would put a third. The arguments are what matter and they
 * are all here: the circle from the theme, the MBID as the identity so the generated
 * tint is the same one search drew, and no content description, because the heading
 * beside it already says the name and TalkBack reading "K" before "Khruangbin" is
 * noise. REQUIREMENTS.md "Accessibility" asks every *control* to carry a description;
 * this is decoration, and marking it as such is the correct treatment rather than an
 * omission.
 *
 * ## The one count, not two
 *
 * The section header below used to repeat this line's album count verbatim — "6
 * albums" in the subtitle and "6 albums" again a hundred pixels down, built from the
 * same expression. [ArtistUiState.subtitle] keeps it, because it says more: it scopes
 * the figure with what is owned against what is still pullable. The section header
 * now carries only its title.
 *
 * `state.artist` is null on the catalogue-search path, where the mirror holds no row
 * and the screen is drawn from the name in the route. The artwork is still drawn
 * there — as the letter placeholder, which is what `NeedlerArtwork` falls back to
 * with no model — because that is the artist least likely to have a picture and the
 * screen it lands on is the one that most needs not to look broken.
 */
@Composable
private fun ArtistHeader(state: ArtistUiState) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.step7),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerArtwork(
            model = state.artist?.artwork,
            // The route's MBID rather than `artist.mbid`, so an artist with no mirror
            // row still gets a stable tint — and the same one search gave them.
            identity = state.mbid?.value ?: state.displayName,
            name = state.displayName,
            contentDescription = null,
            modifier = Modifier.size(NeedlerTheme.sizes.artworkDetail),
            shape = NeedlerTheme.shapes.circle,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                // `state.artist?.name.orEmpty()` drew a blank heading twice over: for an
                // artist the server named with nothing, and - far more often - for an artist
                // reached from catalogue search, who has no mirror row at all. `displayName`
                // falls back to the name the caller passed and then to "Unknown artist", so
                // this line is never empty. You tapped a name; the screen owes you that name.
                text = state.displayName,
                style = typography.display,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = state.subtitle,
                style = typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.semantics {
                    contentDescription = state.subtitle.replace(" · ", ", ")
                },
            )
        }
    }
}

/**
 * The owned half: one row per album in the library, each with a play button.
 *
 * The play button is here for the same reason the library's list rows gained one -
 * "same content, different capability" was the device audit's phrase - and these
 * rows draw exactly the content the library's list view draws. Both now use
 * [AlbumFormatLabel] and both put a 48dp-target play control in the trailing slot,
 * so an album row behaves the same way whichever screen it is on.
 *
 * [credit] is the artist this page is about, for the rows the catalogue gave no credit for. See
 * [discographyRowSubtitle].
 */
private fun LazyListScope.ownedRows(
    albums: List<Album>,
    credit: String?,
    busy: Boolean,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onPlayAlbum: (ReleaseGroupMbid) -> Unit,
    onAddAlbumToCrate: (ReleaseGroupMbid, Boolean) -> Unit,
) {
    items(
        count = albums.size,
        key = { index -> "owned-" + albums[index].releaseGroupMbid.value },
    ) { index ->
        val album: Album = albums[index]
        val onDevice: Boolean = album.showsOnDeviceCheck
        val title: String = LibraryFormat.albumTitle(album.title)
        val subtitle: String = discographyRowSubtitle(album, credit)
        var crateMenuOpen: Boolean by remember(album.releaseGroupMbid.value) {
            mutableStateOf(false)
        }
        NeedlerAlbumRow(
            title = title,
            subtitle = subtitle,
            onClick = { onAlbumClick(album.releaseGroupMbid) },
            showDivider = true,
            contentDescription = buildString {
                append(title)
                // Omitted when it is empty, rather than appended behind a comma: a row with
                // neither a credit nor a year would otherwise be read out as "Tweez, ".
                if (subtitle.isNotEmpty()) {
                    append(", ")
                    append(subtitle)
                }
                albumFormatSpokenLabel(album.quality, onDevice)?.let {
                    append(", ")
                    append(it)
                }
            },
            artwork = { AlbumRowArtwork(album) },
            trailing = {
                AlbumFormatLabel(quality = album.quality, onDevice = onDevice)
                NeedlerIconButton(
                    contentDescription = "Play " + title,
                    onClick = { onPlayAlbum(album.releaseGroupMbid) },
                    visualSize = 32.dp,
                ) {
                    NeedlerStrokeIcon(
                        pathData = PathPlay,
                        tint = NeedlerTheme.colors.accent,
                        size = 16.dp,
                        filled = true,
                    )
                }
                // The row's tap opens the album and its Play replaces the crate; this is the
                // third thing a user wants from a record they can see, and the only one that
                // leaves what they are listening to alone. In the trailing slot rather than
                // behind a long press, because `NeedlerAlbumRow` keeps interactive trailing
                // content reachable as its own target and the tap here is not destructive -
                // there is nothing for a long press to defend against.
                NeedlerCrateControl(
                    subject = title,
                    expanded = crateMenuOpen,
                    onExpandedChange = { crateMenuOpen = it },
                    onAddToCrate = { onAddAlbumToCrate(album.releaseGroupMbid, false) },
                    onPlayNext = { onAddAlbumToCrate(album.releaseGroupMbid, true) },
                    enabled = !busy,
                )
            },
        )
    }
}

/**
 * The catalogue's artists of this name, each opening its own artist screen.
 *
 * A row and not a link out to a browser: the destination is this same screen for a different MBID,
 * which `:app` already routes, and arriving there means a real discography with a Pull on every
 * un-owned record. That is the whole point of offering them.
 *
 * The subtitle is MusicBrainz's own disambiguation comment — "US singer-songwriter", "drummer,
 * London" — which is the only thing that tells two identically-named rows apart. `Artist`'s own KDoc
 * records why that field exists and that the mirror never writes it; these rows are catalogue rows,
 * so they are exactly the case it was added for. With no comment the row falls back to the album
 * count, and with neither it is the name alone, which is still honest: the catalogue gave us nothing
 * else to say.
 *
 * Artwork is drawn circular, as the pack draws an artist everywhere one appears, and comes from the
 * `ArtworkRef.Remote` the search response carried; see `CatalogueMappers.artist` for why a
 * letter placeholder is the correct answer when it carried none.
 *
 * ## What the tap carries, and what it does not
 *
 * The destination is this same screen for an MBID with **no `artist` row behind it** -
 * `refreshArtistDiscography` writes album rows and never an artist row - so the name has to travel
 * with the tap or the next header reads "Unknown artist" for an artist whose name is on screen
 * right now. [onOpenArtist] therefore takes the name as well, and the comment where there is one.
 *
 * The comment and not the row's subtitle is what travels. The two differ for a reason: the comment
 * is the only
 * part of the row MusicBrainz actually supplied, where the album count comes from the mirror and
 * [NOT_IN_LIBRARY] is this screen's own wording. Carrying either of those two forward would be
 * handing the next screen a fact about the library dressed as a fact about the artist, and
 * `ArtistUiState.subtitle` already has its own honest answer for a row it knows nothing about.
 */
private fun LazyListScope.namesakeRows(
    artists: List<Artist>,
    onOpenArtist: (ArtistMbid, String, String?) -> Unit,
) {
    items(
        count = artists.size,
        key = { index -> "namesake-" + artists[index].mbid.value },
    ) { index ->
        val candidate: Artist = artists[index]
        val comment: String? = candidate.disambiguation?.trim()?.takeIf { it.isNotEmpty() }
        val subtitle: String = comment
            ?: LibraryFormat.plural(candidate.ownedAlbumCount.toLong(), "album")
                .takeIf { candidate.ownedAlbumCount > 0 }
            ?: NOT_IN_LIBRARY
        NeedlerAlbumRow(
            title = candidate.name,
            subtitle = subtitle,
            onClick = { onOpenArtist(candidate.mbid, candidate.name, comment) },
            showDivider = true,
            contentDescription = candidate.name + ", " + subtitle + ", open in the catalogue",
            artwork = {
                NeedlerArtwork(
                    model = candidate.artwork,
                    identity = candidate.mbid.value,
                    name = candidate.name,
                    contentDescription = null,
                    modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
                    shape = CircleShape,
                )
            },
        )
    }
}

/**
 * The un-owned half: one row per release group, each with a Pull.
 *
 * [credit] is the artist this page is about. These are the rows the defect was reported on — the
 * catalogue sends no artist with a discography item — and [discographyRowSubtitle] records the whole
 * chain.
 */
private fun LazyListScope.catalogueRows(
    albums: List<Album>,
    credit: String?,
    busy: Boolean,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onPull: (Album) -> Unit,
) {
    items(
        count = albums.size,
        key = { index -> "catalogue-" + albums[index].releaseGroupMbid.value },
    ) { index ->
        val album: Album = albums[index]
        val title: String = LibraryFormat.albumTitle(album.title)
        NeedlerAlbumRow(
            title = title,
            subtitle = discographyRowSubtitle(album, credit),
            onClick = { onAlbumClick(album.releaseGroupMbid) },
            showDivider = true,
            artwork = { AlbumRowArtwork(album) },
            trailing = {
                // An album you do not own wears a Pull; one already on its way
                // wears the badge that says where it has got to. The two are
                // mutually exclusive and both come from AlbumState, so a row
                // cannot show a Pull for something already pulling.
                if (album.state == AlbumState.NotOwned) {
                    NeedlerPullButton(
                        onClick = { onPull(album) },
                        // The pill builds "Pull <title>" from this, so a blank title
                        // would have read out as "Pull ".
                        albumTitle = LibraryFormat.albumLabel(album.title),
                        enabled = !busy,
                    )
                } else {
                    albumBadge(album.state)?.let { NeedlerStateBadge(badge = it) }
                }
            },
        )
    }
}

/**
 * The row thumbnail, sized to the row it sits in.
 *
 * `artworkRow` (56dp), not `artworkThumbLarge` (52dp). The pack pairs each row
 * height with one artwork size — 64dp crate row to 48dp artwork, 76dp library list
 * row to 52dp, 72dp search-result row to 56dp — and these rows are the 72dp
 * search-result row, which `NeedlerAlbumRow`'s default `minHeight` gives them. Using
 * the library list's 52dp inside it produced a fourth ratio that exists nowhere in
 * the design, and on a device it reads as the thumbnails stepping between this
 * screen and the library's list view for no reason a user could name.
 *
 * The 10dp `artworkThumb` corner covers 48-56dp thumbnails, so the radius is right
 * for both and does not change with the size.
 */
@Composable
private fun AlbumRowArtwork(album: Album) {
    AlbumArtwork(
        album = album,
        modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
        shape = NeedlerTheme.shapes.artworkThumb,
        decorative = true,
    )
}

/**
 * The one row under the discography, when there is one.
 *
 * A `LazyListScope` extension rather than a composable inside the last album row, for the reason
 * `:feature:search`'s own more-row gives: it keeps its own key, so changing from "Show more" to
 * "Looking up more releases…" to nothing animates as that row changing rather than as the list
 * rebuilding under the reader's thumb.
 *
 * Drawn as a `NeedlerTextButton`, which is the control the search lane pages with, so paging looks
 * and reads the same wherever the app offers it. A disabled one already draws muted, which is exactly
 * right for the state that is a statement rather than an offer.
 *
 * The spoken description names the artist and says where the releases come from, because
 * REQUIREMENTS.md "Accessibility" makes the description the thing a TalkBack user acts on and "Show
 * more" alone, read out of a list of albums, does not say more of what. A polite live region, so the
 * answer is heard without throwing the reader back to the top of the screen.
 */
private fun LazyListScope.moreRowItem(
    row: DiscographyMoreRow?,
    name: String,
    onClick: () -> Unit,
) {
    if (row == null) return
    item(key = "discography-more") {
        val colors = NeedlerTheme.colors
        NeedlerTextButton(
            text = row.label,
            onClick = onClick,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            enabled = row.enabled,
            // The palette has one emphasis colour and no error colour, so a problem is drawn in the
            // primary text colour rather than in a red this design system does not have. The same
            // decision NoticeLine and the search lane's more-row make.
            color = if (row.isProblem) colors.textPrimary else colors.accent,
            contentDescription = row.label + ", more of " + name + "'s releases from the catalogue",
        )
    }
}

@Composable
private fun SectionSpacer() {
    Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step14))
}

/**
 * A sentence the screen owes the user, in a bordered card.
 *
 * @param detail a second line for a figure the message refers to rather than states - the
 *   crate's count and total duration after an add. It is part of the spoken reading too:
 *   REQUIREMENTS.md "Accessibility" makes the description what a TalkBack user acts on, so an
 *   "added to the crate" that did not say how big the crate now is would leave them with the
 *   same unconfirmable tap a silent screen leaves everyone else.
 */
@Composable
private fun NoticeLine(message: String, detail: String? = null) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    val spoken: String = if (detail == null) {
        message
    } else {
        message + " " + detail.replace(" · ", ", ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
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
}

@Composable
private fun ArtistSkeleton(gutter: Dp) {
    val colors = NeedlerTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter)
            .semantics(mergeDescendants = true) { contentDescription = "Loading this artist" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(26.dp)
                .clip(NeedlerTheme.shapes.progress)
                .background(colors.surface),
        )
        repeat(6) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NeedlerTheme.sizes.albumRowMinHeight),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    // The same 56dp the real rows draw, so the layout does not step when
                    // the mirror answers. See [AlbumRowArtwork].
                    modifier = Modifier
                        .size(NeedlerTheme.sizes.artworkRow)
                        .clip(NeedlerTheme.shapes.artworkThumb)
                        .background(colors.surface),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(12.dp)
                        .clip(NeedlerTheme.shapes.progress)
                        .background(colors.surface),
                )
            }
        }
    }
}

/**
 * Nothing at all: no mirror row, no name from the caller, no albums either way.
 *
 * ## The sentence this replaces was untrue
 *
 * It read "They are not in the mirror on this device, and the catalogue could not be reached to look
 * them up", and it said so unconditionally. Reached from catalogue search that is false twice over:
 * the catalogue had been reached seconds earlier - that is how search found the artist - and the
 * screen was blaming a network failure for a lookup that had in fact succeeded. Only [reason] can say
 * the catalogue was unreachable, and it is only non-null when it actually was.
 *
 * @param reason the catalogue's own explanation when there is one, from [catalogueNoticeMessage].
 *   Null means the catalogue answered and simply had nothing, which is a different sentence.
 */
@Composable
private fun ArtistNotFound(gutter: Dp, reason: String?) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = ARTIST_NOT_HERE,
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = reason ?: ARTIST_NOT_LISTED,
            style = typography.body,
            color = colors.textSecondary,
        )
    }
}

/**
 * The empty screen's heading, and the sentence this whole defect was reported as.
 *
 * Named rather than inlined so a test can assert it is **absent**. The claim is reserved for
 * `ArtistUiState.notFound`, which needs no mirror row, no name from the route and no album of
 * either kind before it will make it; an artist opened from the namesake rows fails that test on
 * the name alone, and does so on the first frame, before any discography has arrived.
 */
internal const val ARTIST_NOT_HERE: String = "That artist is not here"

/**
 * What to say when the catalogue answered and had nothing.
 *
 * It states what is true - nothing on this device is by them, and no discography came back - and it
 * names neither the network nor the mirror as the culprit, because on this path neither failed.
 */
internal const val ARTIST_NOT_LISTED: String =
    "Nothing on this device is by them, and no discography came back for them either."

internal const val CATALOGUE_OFFLINE: String =
    "The rest of this artist's discography needs a connection. What you own is listed above " +
        "and plays as usual."

/**
 * Which sentence the catalogue notice shows.
 *
 * [NeedlerError]'s KDoc says the distinctions matter to the UI, and here they genuinely do: an
 * artist the catalogue has never heard of, a server that answered 500, and a request that timed out
 * are three different things, and telling someone the same sentence for all three is what made a
 * failure on every artist indistinguishable from a failure on one.
 *
 * The error's own `diagnostic` is deliberately absent. It carries status codes and header names and
 * its KDoc says it is never shown raw to the user; it belongs in the log, which is where
 * [ArtistViewModel] now writes it.
 */
internal fun catalogueNoticeMessage(
    error: NeedlerError?,
    offline: Boolean,
    notInCatalogue: Boolean = false,
): String = when {
    // First, and ahead of `offline`, because this one does not change when the
    // connection comes back. Telling someone to try again later about a discography
    // that will never be fetchable is the failure this sentence replaces.
    notInCatalogue -> CATALOGUE_NO_MBID
    offline || error is NeedlerError.Offline -> CATALOGUE_OFFLINE
    error is NeedlerError.NotFound -> CATALOGUE_NOT_IN_CATALOGUE
    error is NeedlerError.ServerError -> CATALOGUE_SERVER_ERROR
    error is NeedlerError.RateLimited -> CATALOGUE_RATE_LIMITED
    else -> CATALOGUE_UNAVAILABLE
}

/**
 * The sentence for an artist whose id was never a MusicBrainz one.
 *
 * It says what is true — the server matched this artist by name, so there is no
 * discography to fetch — and it does not invite a retry, because there is nothing to
 * retry. The wording deliberately avoids "MBID" and "UUID": the fact that matters to
 * a listener is that their server could not identify this artist, not which flavour
 * of identifier it minted instead.
 *
 * ## Why the last clause changed
 *
 * It used to end "Everything you own by them is listed above", and the device showed what that
 * costs. The artist it says this about was the one with the most records in the user's library, and
 * the whole screen was then accurate and useless: no discography, no way to get one, and a closing
 * sentence that summarised the library rather than offering anything. The sentence now names the
 * action beside it — **Search the catalogue** — and `ArtistUiState.canFindInCatalogue` is true in
 * exactly the states this string is drawn in, so the action it names is always on the screen with
 * it. A sentence naming a control that is not there is worse than a sentence that offers nothing.
 *
 * "Under this entry" is load-bearing too: the artist may be perfectly well known to MusicBrainz, and
 * it is *this server's row for them* that cannot reach it. The old wording read as a claim about the
 * artist.
 */
internal const val CATALOGUE_NO_MBID: String =
    "Your server matched this artist by name rather than to MusicBrainz, so there is no full " +
        "discography to look up under this entry. Search the catalogue by name to find them there."

internal const val CATALOGUE_NOT_IN_CATALOGUE: String =
    "The catalogue has nothing else for this artist. What you own is listed above."

/**
 * The row that asks for the next page, when the server has not said how many releases there are.
 *
 * `source_total_count` is absent while the artist is still warming upstream, and a label that
 * invented a figure for it would be the only unsourced number on the screen.
 */
internal const val SHOW_MORE_RELEASES: String = "Show more from the catalogue"

/** The same row while the page is in flight. Not tappable; see `ArtistUiState.discographyMoreRow`. */
internal const val LOOKING_UP_MORE_RELEASES: String = "Looking up more releases…"

/**
 * The same row when the page did not arrive.
 *
 * It says to tap, because tapping asks for the same page again - and it says nothing about the list
 * already on screen, which is intact and which the user is reading. Distinct from
 * [CATALOGUE_INCOMPLETE], the sentence for a discography that is a cache rather than a short list.
 */
internal const val MORE_RELEASES_FAILED: String =
    "That page of the discography did not arrive. Tap to try it again."

/** A namesake row's subtitle when the catalogue offered no disambiguation and no album count. */
internal const val NOT_IN_LIBRARY: String = "Not in your library"

/**
 * The answer when the catalogue has nobody of this name either.
 *
 * It does not invite another tap, because the same query will get the same answer. It does say which
 * question was asked, so the user can tell this apart from the look-up having failed — see
 * [NAMESAKE_SEARCH_FAILED], which is the one that is worth retrying.
 */
internal const val NO_NAMESAKES: String =
    "MusicBrainz has no artist under this name, so there is no catalogue entry to pull from."

/** The look-up itself failed, which is worth another tap where an empty answer is not. */
internal const val NAMESAKE_SEARCH_FAILED: String =
    "That catalogue search did not get through. Tap Search the catalogue to try it again."

/**
 * What the screen says when the lookup succeeded and the catalogue holds nothing more.
 *
 * A statement about the **discography**, not about the user's library. The sentence this replaces
 * had the shape of an apology for the library being short - "what you own is listed above" - and a
 * device report named exactly that: *"down the bottom it gives you a notice saying that's all you
 * have - but why? Why not show other albums?"* The answer the user actually needs is whether the
 * list above them is complete, and this says so without commenting on how much of it they own.
 *
 * It appears only when a lookup has finished and found nothing extra, which is why it can make that
 * claim at all; [CATALOGUE_INCOMPLETE] is the opposite case and [CATALOGUE_UNAVAILABLE] the case
 * where there was no answer.
 */
internal const val CATALOGUE_COMPLETE: String =
    "That is this artist's whole discography as MusicBrainz has it. Nothing else to pull."

/**
 * The quiet line under a discography that is the cached one.
 *
 * The signal `ArtistUiState.discographyIncomplete` exists for. It does not say the catalogue is
 * unavailable - its output is on the screen above - and it does not apologise for the library. It
 * says the one thing a reader cannot work out for themselves: that the list may be short, and that
 * the control to complete it is on this screen.
 */
internal const val CATALOGUE_INCOMPLETE: String =
    "This list is the last one your server sent, so it may be short. Try again to refresh it."

internal const val CATALOGUE_SERVER_ERROR: String =
    "Your server had a problem fetching the rest of this artist's discography. What you own is " +
        "listed above."

internal const val CATALOGUE_RATE_LIMITED: String =
    "The catalogue is busy. The rest of this artist's discography will be there shortly; what " +
        "you own is listed above."

internal const val CATALOGUE_UNAVAILABLE: String =
    "The rest of this artist's discography could not be fetched from the catalogue. What you " +
        "own is listed above."
