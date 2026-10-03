package app.needler.core.design.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * An album in the grid: square artwork with a play affordance, an on-device check, and two lines of
 * caption under it.
 *
 * Ported from Library (02, two columns, 163dp cells) and the tablet library (09, four columns, 160dp
 * cells). The cell sizes itself from the grid it is placed in - the artwork is a 1:1
 * `aspectRatio` - so the same composable serves both widths, which is what REQUIREMENTS.md means by
 * "one navigation model at two widths".
 *
 * Two overlays, both drawn on the artwork at an 8dp inset:
 *
 *  - **device**: a 22dp positive-green disc holding a 2.4-weight check, bottom left. The green is
 *    [NeedlerAlbumSource.Device]'s own hue, which is the colour that state carries everywhere;
 *  - **play**: a 32dp disc with a 1.5dp `rgba(242,245,238,0.85)` rim over a
 *    `rgba(13,18,10,0.35)` scrim, bottom right.
 *
 * The play affordance is drawn at the pack's 32dp but its touch target is a full 48dp, satisfying
 * REQUIREMENTS.md's minimum without changing what is on screen: a 48dp box in the artwork's bottom
 * corner centres exactly on a 32dp disc inset by 8dp, so the target grows inward and nothing moves.
 *
 * @param artwork the artwork slot, sized to fill the square. Pass [AsyncAlbumArt] with
 *   `contentDescription = null`: the cell already names the album.
 */
@Composable
fun NeedlerAlbumGridCell(
    title: String,
    artistName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDevice: Boolean = false,
    onPlayClick: (() -> Unit)? = null,
    artwork: @Composable () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizes = NeedlerTheme.sizes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("$title, $artistName")
                    // The state word itself, so the grid cell and the badge beside a list row
                    // read the same three words. REQUIREMENTS.md "Vocabulary".
                    if (onDevice) append(", " + NeedlerAlbumSource.Device.label())
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

            if (onDevice) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .size(sizes.onDeviceBadge)
                        .clip(NeedlerTheme.shapes.circle)
                        .background(colors.positive),
                    contentAlignment = Alignment.Center,
                ) {
                    NeedlerStrokeIcon(
                        pathData = PathCheck,
                        tint = colors.onPositive,
                        size = 13.dp,
                        // The pack thickens this check to 2.4 because it is so small.
                        strokeWidth = 2.4f,
                    )
                }
            }

            if (onPlayClick != null) {
                // Shared between the 48dp target and the 32dp disc, for the same reason
                // [NeedlerIconButton] shares one: the node that is touched and the node that is
                // drawn are different sizes here on purpose, so the press has to be drawn on the
                // disc rather than on the target around it or it flashes a 48dp square over a
                // 32dp circle. `indication = null` below moves the feedback onto the disc; it does
                // not take it away.
                val playInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(sizes.minTouchTarget)
                        .clickable(
                            interactionSource = playInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = onPlayClick,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Play $title"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(sizes.gridPlayAffordance)
                            .clip(NeedlerTheme.shapes.circle)
                            .background(colors.artworkScrim)
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
        }
    }
}
