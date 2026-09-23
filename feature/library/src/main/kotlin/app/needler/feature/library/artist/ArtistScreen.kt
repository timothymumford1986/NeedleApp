package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPullButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.feature.library.common.AlbumArtwork
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.albumBadge
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
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    widthSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
    onPull: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass == WindowWidthSizeClass.Expanded
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutter

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gutter - 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeedlerIconButton(contentDescription = "Back", onClick = onBack) {
                NeedlerStrokeIcon(
                    pathData = PathChevronLeft,
                    tint = colors.textPrimary,
                    size = 24.dp,
                )
            }
        }

        when {
            state.loading -> ArtistSkeleton(gutter = gutter)
            state.notFound -> ArtistNotFound(gutter = gutter)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    bottom = spacing.step12,
                ),
            ) {
                item(key = "header") { ArtistHeader(state) }

                if (state.notice != null) {
                    item(key = "notice") { NoticeLine(message = state.notice.message) }
                }

                if (state.ownedAlbums.isNotEmpty()) {
                    item(key = "owned-header") {
                        SectionSpacer()
                        NeedlerSectionHeader(
                            title = "In your library",
                            trailing = LibraryFormat.plural(
                                state.ownedAlbums.size.toLong(),
                                "album",
                            ),
                        )
                    }
                    ownedRows(
                        albums = state.ownedAlbums,
                        onAlbumClick = onAlbumClick,
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
}

@Composable
private fun ArtistHeader(state: ArtistUiState) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = state.artist?.name.orEmpty(),
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

private fun LazyListScope.ownedRows(
    albums: List<Album>,
    onAlbumClick: (ReleaseGroupMbid) -> Unit,
) {
    items(
        count = albums.size,
        key = { index -> "owned-" + albums[index].releaseGroupMbid.value },
    ) { index ->
        val album: Album = albums[index]
        val format: String? = LibraryFormat.quality(album.quality)
        val onDevice: Boolean = album.showsOnDeviceCheck
        NeedlerAlbumRow(
            title = album.title,
            subtitle = LibraryFormat.albumRowSubtitle(album),
            onClick = { onAlbumClick(album.releaseGroupMbid) },
            showDivider = true,
            contentDescription = buildString {
                append(album.title)
                append(", ")
                append(LibraryFormat.albumRowSubtitle(album))
                if (format != null) {
                    append(", ")
                    append(format)
                }
                if (onDevice) append(", on device")
            },
            artwork = { AlbumRowArtwork(album) },
            trailing = {
                if (format != null) {
                    Text(
                        text = format,
                        style = NeedlerTheme.typography.caption,
                        color = if (onDevice) {
                            NeedlerTheme.colors.positive
                        } else {
                            NeedlerTheme.colors.textMuted
                        },
                        maxLines = 1,
                    )
                }
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
        NeedlerAlbumRow(
            title = album.title,
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
                        albumTitle = album.title,
                        enabled = !busy,
                    )
                } else {
                    albumBadge(album.state)?.let { NeedlerStateBadge(badge = it) }
                }
            },
        )
    }
}

@Composable
private fun AlbumRowArtwork(album: Album) {
    AlbumArtwork(
        album = album,
        modifier = Modifier.size(NeedlerTheme.sizes.artworkThumbLarge),
        shape = NeedlerTheme.shapes.artworkThumb,
        decorative = true,
    )
}

@Composable
private fun SectionSpacer() {
    Spacer(modifier = Modifier.height(NeedlerTheme.spacing.step14))
}

@Composable
private fun NoticeLine(message: String) {
    val colors = NeedlerTheme.colors
    val shape = NeedlerTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(NeedlerTheme.sizes.hairlineThickness, colors.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = message,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
        )
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
                    modifier = Modifier
                        .size(NeedlerTheme.sizes.artworkThumbLarge)
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

@Composable
private fun ArtistNotFound(gutter: Dp) {
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
            text = "They are not in the mirror on this device, and the catalogue could not be " +
                "reached to look them up.",
            style = typography.body,
            color = colors.textSecondary,
        )
    }
}

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
internal fun catalogueNoticeMessage(error: NeedlerError?, offline: Boolean): String = when {
    offline || error is NeedlerError.Offline -> CATALOGUE_OFFLINE
    error is NeedlerError.NotFound -> CATALOGUE_NOT_IN_CATALOGUE
    error is NeedlerError.ServerError -> CATALOGUE_SERVER_ERROR
    error is NeedlerError.RateLimited -> CATALOGUE_RATE_LIMITED
    else -> CATALOGUE_UNAVAILABLE
}

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
