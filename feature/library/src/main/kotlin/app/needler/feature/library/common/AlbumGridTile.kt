package app.needler.feature.library.common

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerCrateControl
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.PathPlayOutline
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album

/**
 * One album in the library grid: artwork, a play affordance, an overflow menu, and a caption that
 * names the album's state in words.
 *
 * ## Why this is not `NeedlerAlbumGridCell`
 *
 * It began as that component and three faults made it a different control, all of them found by
 * reading `screenshots/library-grid-phone.png` beside `screenshots/library-list-phone.png`:
 *
 *  * **the grid dropped what the list carried.** The cell's only state channel was a green check
 *    or nothing, so [app.needler.core.design.component.NeedlerAlbumSource.Server] and
 *    [app.needler.core.design.component.NeedlerAlbumSource.NotRetrieved] were one picture, and the
 *    format was absent entirely. Toggling grid to list changed what the user could know about a
 *    record, under a control whose label promises a different layout. The caption now carries
 *    [AlbumStateLabel] and [AlbumFormatLabel], which is what a list row carries;
 *  * **a tile had no overflow at all**, so every per-album action was list-only. The crate is
 *    reachable from a tile now, through the same [NeedlerCrateControl] the list row uses;
 *  * **the play overlay had no scrim.** The disc was drawn over
 *    [app.needler.core.design.theme.NeedlerColors.artworkScrim] at 0.35, which a pale cover
 *    swallows: a white glyph with a white rim on pale artwork is the only play affordance the grid
 *    has, and on half a shelf of real covers it is near-invisible.
 *    [app.needler.core.design.theme.NeedlerColors.artworkScrimStrong] is the pack's own darker
 *    value and is what the disc sits on here.
 *
 * None of the three can be fixed from outside the component - the state and format want a slot the
 * caption does not have, the overflow wants an overlay slot, and the scrim is a token the cell
 * chooses - and `:core:design` is owned by another change in flight. So the cell is reproduced here
 * from that module's own components and tokens, which is the arrangement `PlaylistCards` records
 * for the same reason. **The right end state is one cell in `:core:design`** taking an overlay slot
 * and a caption slot, with this file deleted; it is in the handover notes.
 *
 * ## The green check on the artwork is gone
 *
 * It said one of the three states and said it in a channel the other two could not use, so a reader
 * learning the grid learned "green disc means something, nothing means one of two other things".
 * One marker that can say all three, in the caption where the format already is, is the whole of
 * the fix; REQUIREMENTS.md "Accessibility" is why it has to be a word rather than a second colour.
 *
 * ## Play and the crate are offered only where they can work
 *
 * An album the server does not hold has no audio anywhere, so a tile for one draws neither control
 * rather than drawing them live over nothing. `screenshots/library-empty-offline-phone.png` already
 * does this correctly for `Sync now`, and this is the same rule applied to a tile.
 *
 * @param artwork the artwork slot, sized to fill the square. Pass [AlbumArtwork] with
 *   `decorative = true`: the tile already names the album.
 */
@Composable
internal fun AlbumGridTile(
    album: Album,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onAddToCrate: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes
    val title: String = LibraryFormat.albumTitle(album.title)
    val artistName: String = LibraryFormat.artistName(album.artistName)
    val playable: Boolean = album.isOwned
    var crateMenuOpen: Boolean by remember(album.releaseGroupMbid.value) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(title)
                    append(", ")
                    append(artistName)
                    append(", ")
                    append(albumStateSpokenLabel(album.state))
                    albumFormatSpokenLabel(album.quality, onDevice = false)?.let {
                        append(", ")
                        append(it)
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(NeedlerTheme.shapes.artworkGrid),
            ) {
                artwork()
            }

            if (playable) {
                NeedlerCrateControl(
                    subject = title,
                    expanded = crateMenuOpen,
                    onExpandedChange = { crateMenuOpen = it },
                    onAddToCrate = { onAddToCrate(false) },
                    onPlayNext = { onAddToCrate(true) },
                    // The same raised disc the album screen's action row uses, because a bare glyph
                    // over artwork is a glyph the cover can hide.
                    emphasised = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )

                // Shared between the 48dp target and the 32dp disc, as the pack's own cell does it:
                // the node that is touched and the node that is drawn are different sizes on
                // purpose, so the press has to be drawn on the disc or it flashes a 48dp square
                // over a 32dp circle.
                val playInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(sizes.minTouchTarget)
                        .clickable(
                            interactionSource = playInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = onPlay,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Play " + title
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(sizes.gridPlayAffordance)
                            .clip(NeedlerTheme.shapes.circle)
                            .background(colors.artworkScrimStrong)
                            .border(
                                width = 1.5.dp,
                                color = colors.artworkOutline,
                                shape = NeedlerTheme.shapes.circle,
                            )
                            .indication(playInteraction, LocalIndication.current),
                        contentAlignment = Alignment.Center,
                    ) {
                        NeedlerStrokeIcon(
                            pathData = PathPlayOutline,
                            tint = colors.textPrimary,
                            size = 14.dp,
                            strokeWidth = 2f,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = typography.bodyStrong,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistName,
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumStateLabel(state = album.state)
                AlbumFormatLabel(quality = album.quality)
            }
        }
    }
}
