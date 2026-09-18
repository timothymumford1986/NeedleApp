package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The 1dp hairline that separates rows: `rgba(242,245,238,0.08)`.
 *
 * Drawn as a full-width rule under a row, which is how the pack does it (`border-bottom`) rather
 * than as a list-level separator.
 */
@Composable
fun NeedlerHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NeedlerTheme.sizes.hairlineThickness)
            .background(NeedlerTheme.colors.hairline),
    )
}

/**
 * A track in an album's track list: number, title, duration, overflow.
 *
 * Ported from album detail (04, 11) and the un-owned album (05). 52dp minimum, 16dp between parts, a
 * fixed 18dp column for the number so the titles line up, and tabular numerals on both the number
 * and the duration so neither column shifts as the digits change.
 *
 * Three states, all drawn in the pack:
 *
 *  - normal: primary title, muted number and duration;
 *  - playing: the number is replaced by the record glyph and the title turns accent (04, 11);
 *  - unavailable: the whole row is muted, as every track is on the un-owned album (05).
 *
 * @param durationSpoken how to read [duration] aloud, e.g. "3 minutes 59 seconds". TalkBack renders
 *   "3:59" poorly, so pass this where it matters.
 */
@Composable
fun NeedlerTrackRow(
    index: Int,
    title: String,
    duration: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    available: Boolean = true,
    durationSpoken: String? = null,
    onClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val titleColor = when {
        isPlaying -> colors.accent
        !available -> colors.textMuted
        else -> colors.textPrimary
    }
    val spoken = buildString {
        if (!isPlaying) append("$index. ")
        append(title)
        append(", ")
        append(durationSpoken ?: duration)
        if (isPlaying) append(", playing")
        if (!available) append(", not in your library")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NeedlerTheme.sizes.trackRowMinHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPlaying) {
            NeedlerNowPlayingIcon(tint = colors.accent)
        } else {
            Text(
                text = index.toString(),
                style = typography.trackIndex,
                color = colors.textMuted,
                modifier = Modifier.width(18.dp),
            )
        }
        Text(
            text = title,
            style = if (isPlaying) typography.rowTitle else typography.rowTitleRegular,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = duration,
            style = typography.duration,
            color = colors.textMuted,
        )
        if (onMoreClick != null) {
            NeedlerIconButton(
                contentDescription = "More actions for $title",
                onClick = onMoreClick,
                visualSize = 36.dp,
            ) {
                NeedlerMoreIcon(tint = colors.textMuted)
            }
        }
    }
}

/**
 * A settings row: a label, the current value, and a chevron.
 *
 * Ported from Settings (12) and the Equaliser's Preamp row (19). 48dp minimum with a hairline under
 * it. Rows without a chevron - Preamp, which only reports - are the [showChevron] `false` case.
 */
@Composable
fun NeedlerSettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (value == null) label else "$label, $value"
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = typography.body,
                color = if (enabled) colors.textPrimary else colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(text = value, style = typography.bodySmall, color = colors.textSecondary)
            }
            if (showChevron) {
                NeedlerChevronRightIcon(tint = colors.textMuted)
            }
        }
        if (showDivider) NeedlerHairline()
    }
}

/**
 * A toggle row: a label and a switch.
 *
 * Ported from Settings (12) and "Equaliser on" (19). The whole row is the toggle, not just the
 * 48x28dp switch - the pack puts the control on the right but there is no reason to make the target
 * that small, and the row is already 48dp tall.
 *
 * @param subtitle the second line some rows carry, e.g. "Keeps gapless albums intact" (20).
 */
@Composable
fun NeedlerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = if (subtitle == null) label else "$label. $subtitle"
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = typography.body,
                    color = if (enabled) colors.textPrimary else colors.textMuted,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = typography.caption,
                        color = colors.textMuted,
                    )
                }
            }
            NeedlerSwitch(checked = checked, enabled = enabled)
        }
        if (showDivider) NeedlerHairline()
    }
}

/**
 * The switch from Settings (12) and the Equaliser (19): a 48x28dp track with a 24dp thumb.
 *
 * On: an accent track with an [app.needler.core.design.theme.NeedlerColors.onAccent] thumb. Off: a
 * raised-surface track with a secondary thumb.
 *
 * It draws only - it has no click handling and no semantics of its own, because in the pack it is
 * always inside a row that owns both. Use [NeedlerToggleRow] unless you are building something the
 * pack does not draw.
 */
@Composable
fun NeedlerSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val sizes = NeedlerTheme.sizes
    val trackColor = if (checked) colors.accent else colors.surfaceRaised
    val thumbColor = when {
        !enabled -> colors.textMuted
        checked -> colors.onAccent
        else -> colors.textSecondary
    }
    val inset: Dp = (sizes.switchHeight - sizes.switchThumb) / 2
    Box(
        modifier = modifier
            .size(width = sizes.switchWidth, height = sizes.switchHeight)
            .clip(NeedlerTheme.shapes.pill)
            .background(trackColor),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(
                    x = if (checked) sizes.switchWidth - sizes.switchThumb - inset else inset,
                )
                .size(sizes.switchThumb)
                .clip(NeedlerTheme.shapes.circle)
                .background(thumbColor),
        )
    }
}

/**
 * An album or artist row: artwork, title, subtitle, and whatever the state needs on the right.
 *
 * Ported from the search results (03, 10), the Pulls list (06) and the library list view (13). The
 * pack varies the height and the artwork size between those screens, so both are parameters; the
 * defaults are the search-result row.
 *
 * The [trailing] slot is where a [NeedlerStateBadge], a [NeedlerPullButton] or a Retry pill goes.
 * Anything interactive placed there stays a separate accessibility target.
 *
 * @param artwork the artwork slot. Pass [AsyncAlbumArt] with `contentDescription = null`, since the
 *   row already names the album.
 */
@Composable
fun NeedlerAlbumRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = NeedlerTheme.sizes.albumRowMinHeight,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    contentDescription: String? = null,
    artwork: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription ?: "$title, $subtitle"
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            artwork?.invoke()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = typography.rowTitle,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = typography.meta,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke(this)
        }
        if (showDivider) NeedlerHairline()
    }
}

/**
 * A row in the crate: drag handle, artwork, title, subtitle.
 *
 * Ported from the queue (08) and the tablet sidebar's crate (09). 64dp minimum, 48dp artwork, and
 * the playing row's title in the accent colour with the record glyph on the right.
 *
 * REQUIREMENTS.md: "TalkBack must reach the crate's reordering through an accessible action, not only
 * by dragging." [onMoveUp] and [onMoveDown] are that: when either is supplied the row gains a custom
 * accessibility action, so reordering works from the TalkBack local context menu without any drag.
 * The visible handle is still there for everyone else, and the feature module owns the drag gesture.
 */
@Composable
fun NeedlerQueueRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    showHandle: Boolean = true,
    onClick: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    artwork: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val actions = buildList {
        if (onMoveUp != null) {
            add(CustomAccessibilityAction("Move up in the crate") { onMoveUp(); true })
        }
        if (onMoveDown != null) {
            add(CustomAccessibilityAction("Move down in the crate") { onMoveDown(); true })
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NeedlerTheme.sizes.queueRowMinHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("$title, $subtitle")
                    if (isPlaying) append(", playing")
                }
                if (actions.isNotEmpty()) customActions = actions
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showHandle) {
            NeedlerDragHandleIcon(tint = colors.textMuted)
        }
        artwork?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = typography.rowTitle,
                color = if (isPlaying) colors.accent else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = typography.meta,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPlaying && trailing == null) {
            NeedlerNowPlayingIcon(tint = colors.accent)
        }
        trailing?.invoke(this)
    }
}

/**
 * A row in the output picker (21): icon, name, connection kind, and a check when it is the one in
 * use.
 *
 * 60dp minimum with a hairline under it. The chosen row is drawn entirely in the accent colour and
 * marked as selected, so TalkBack says "Living room speaker, Bluetooth, connected, selected".
 *
 * @param unavailableReason set when a Cast target cannot be reached, which REQUIREMENTS.md asks be
 *   explained in the picker rather than failing after the user picks a speaker.
 */
@Composable
fun NeedlerOutputRow(
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    unavailableReason: String? = null,
    leadingIcon: (@Composable (tint: Color) -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val tint = when {
        !enabled -> colors.textMuted
        selected -> colors.accent
        else -> colors.textPrimary
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.outputRowMinHeight)
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("$name, $detail")
                        if (unavailableReason != null) append(". $unavailableReason")
                    }
                    this.selected = selected
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke(if (selected) colors.accent else colors.textSecondary)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = name, style = typography.rowTitle, color = tint, maxLines = 2)
                Text(
                    text = unavailableReason ?: detail,
                    style = typography.caption,
                    color = colors.textMuted,
                    maxLines = 2,
                )
            }
            if (selected) NeedlerCheckIcon(tint = colors.accent, size = 20.dp)
        }
        NeedlerHairline()
    }
}
