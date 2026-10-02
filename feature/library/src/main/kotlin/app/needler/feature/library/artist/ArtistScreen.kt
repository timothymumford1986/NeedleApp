@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerPullButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathPlay
import app.needler.core.design.component.PathPull
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.AlbumFormatLabel
import app.needler.feature.library.common.FavouriteButton
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheet
import app.needler.feature.library.common.RequestSheetState
import app.needler.feature.library.common.albumBadge
import app.needler.feature.library.common.albumFormatSpokenLabel
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
 * ## Play, Shuffle and a star
 *
 * This screen had none of the three. The album screen had Play, Shuffle and Pull
 * local, which the device audit noted made the omission starker rather than
 * smaller. Play and Shuffle act on every playable track of every owned album, in
 * the order this screen lists them; the star is the same binary favourite an album
 * and a track now carry, and `getStarred2` returns starred artists alongside both.
 *
 * Pull local is deliberately **not** here. Pinning is per album - `PinRepository`
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
                    FavouriteButton(
                        isFavourite = state.isFavourite,
                        name = state.spokenName,
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
                            busy = state.busy,
                            onAlbumClick = onAlbumClick,
                            onPull = onPull,
                        )
                    }

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
            // Beside the two controls that replace the crate, because this is the one that
            // does not. An artist with records in the library is the commonest place to want
            // "after what I am listening to", and until now the screen could only interrupt.
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
 */
private fun LazyListScope.ownedRows(
    albums: List<Album>,
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
        var crateMenuOpen: Boolean by remember(album.releaseGroupMbid.value) {
            mutableStateOf(false)
        }
        NeedlerAlbumRow(
            title = title,
            subtitle = LibraryFormat.albumRowSubtitle(album),
            onClick = { onAlbumClick(album.releaseGroupMbid) },
            showDivider = true,
            contentDescription = buildString {
                append(title)
                append(", ")
                append(LibraryFormat.albumRowSubtitle(album))
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

private fun LazyListScope.catalogueRows(
    albums: List<Album>,
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
            subtitle = LibraryFormat.albumRowSubtitle(album),
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
            text = "That artist is not here",
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
 */
internal const val CATALOGUE_NO_MBID: String =
    "Your server matched this artist by name rather than to MusicBrainz, so there is no full " +
        "discography to look up. Everything you own by them is listed above."

internal const val CATALOGUE_NOT_IN_CATALOGUE: String =
    "The catalogue has nothing else for this artist. What you own is listed above."

internal const val CATALOGUE_SERVER_ERROR: String =
    "Your server had a problem fetching the rest of this artist's discography. What you own is " +
        "listed above."

internal const val CATALOGUE_RATE_LIMITED: String =
    "The catalogue is busy. The rest of this artist's discography will be there shortly; what " +
        "you own is listed above."

internal const val CATALOGUE_UNAVAILABLE: String =
    "The rest of this artist's discography could not be fetched from the catalogue. What you " +
        "own is listed above."
