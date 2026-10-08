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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import app.needler.core.design.theme.wrapsAtWords

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
 * How a row divides its width, when it stops dividing it, and how big its controls are.
 *
 * ## The defect this exists for
 *
 * Four reviewers found the same mechanism on four surfaces: a fixed-width trailing element wins the
 * width fight against the title, and the title then breaks mid-word. `Death's Dateless Night` drew
 * as `Deat` / `h's ...`, `Mordechai` as `M` / `...`, `Submarine` as `Sub` / `m...`.
 *
 * It is a property of `Row` rather than a mistake at any one call site. A `Row` measures its
 * **unweighted** children first, at the full width available, and gives the weighted ones whatever
 * is left. Every row in this file put its text in a `Modifier.weight(1f)` column and its badge,
 * chip, duration or glyph in no weight at all - so the trailing element took the width it asked
 * for, however much that was, and the title received the remainder. With
 * `NeedlerAlbumBadge.NeedsAttention` at its old 196dp that remainder was 74dp of the 266dp a 390dp
 * phone row has to share, which is about ten characters of `rowTitle`. Shortening the badge, which
 * `StateBadgeLabelTest` records, fixed the worst instance; it did not change the rule that produces
 * the next one.
 *
 * ## The rule
 *
 * The title column is weighted and the trailing block is weighted too, with
 * `Modifier.weight(..., fill = false)`. Two weighted children split the space in a fixed ratio
 * regardless of what either asks for, and `fill = false` lets the trailing block still be narrower
 * than its share when its content is small - which is the usual case, so most rows are drawn
 * exactly as before. What changes is the worst case: the title can no longer be squeezed below
 * [TITLE_WEIGHT] of the row whatever the trailing block holds.
 *
 * Word-level wrapping is the other half, and it lives in
 * [app.needler.core.design.theme.wrapsAtWords].
 *
 * ## Why it stacks instead past [STACK_ABOVE_FONT_SCALE]
 *
 * Because a ratio cannot save two text blocks side by side at 200%. REQUIREMENTS.md
 * "Accessibility" requires text to "scale to 200% without clipping"; at that scale a 390dp phone
 * row has roughly 266dp to share and the title alone wants more than all of it, so dividing that
 * width 62/38 only decides which of the two columns is unreadable. Past the threshold the trailing
 * block is drawn **below** the title instead and takes the row's full width, which trades row
 * height - of which a scrolling list has an unlimited supply - for row width, of which it has
 * 266dp.
 *
 * The threshold is 1.3 rather than 2.0 because the damage starts long before 200%: Android's
 * largest ordinary font setting is 1.3, and the accessibility sizes above it go to 2.0. So the
 * stack begins at the first setting a user has to go looking for, not at the last one.
 *
 * Only the two rows with a *status block* beside a two-line text column stack: [NeedlerAlbumRow]
 * and [NeedlerQueueRow]. A settings value, a duration and a selected check are short, single runs
 * and the ratio is enough for them; moving a chevron below the label it belongs to would be a
 * different row rather than the same row at a bigger size.
 *
 * ## Why the controls scale and the touch floor does not
 *
 * A device reviewer found that the `...` and the heart "stay at default size while everything
 * around them doubles, so the only small targets left are the ones a large-text user must hit".
 * They were fixed `Dp`, which is correct for the pack and wrong under scaling: a row grows with its
 * text and its controls then shrink relative to everything beside them. [controlSize] scales them
 * on the same signal the text uses. The 48dp floor REQUIREMENTS.md "Accessibility" requires is
 * unaffected - it is a floor, applied by [NeedlerIconButton], and scaling only ever moves a control
 * up from it.
 *
 * ## The rejected alternative
 *
 * `BoxWithConstraints` in each row, measuring the trailing slot and deciding from the real width.
 * It is more accurate and it costs a subcomposition on every row of every list, which is the one
 * place in a Compose application where that bill is paid per item per scroll. The two weights and
 * the one threshold are arithmetic.
 */
object NeedlerRowLayout {

    /**
     * The share of a row's divisible width the title column is guaranteed. 0.62.
     *
     * Of the 266dp a 390dp phone row shares, that is 165dp - about 22 characters of `rowTitle` at
     * the 7.4dp-per-character average `StateBadgeLabelTest` calibrated off the committed renders,
     * over two lines. `Death's Dateless Night` is 22 characters.
     */
    const val TITLE_WEIGHT: Float = 0.62f

    /**
     * The most of a row's divisible width a trailing block may take. 0.38.
     *
     * A ceiling, not an allocation: with `fill = false` a trailing block narrower than this is
     * drawn at its own width and the title keeps the difference.
     */
    const val TRAILING_WEIGHT: Float = 1f - TITLE_WEIGHT

    /** Past this text scale a trailing status block is drawn below the title rather than beside it. */
    const val STACK_ABOVE_FONT_SCALE: Float = 1.3f

    /**
     * The most [controlSize] will grow a control, whatever the system reports. 2.0.
     *
     * Android's own accessibility sizes stop at 2.0, but `fontScale` is a `Float` an OEM skin or a
     * display-size setting can push past it, and a 36dp glyph at 3x is a 108dp target in a row that
     * holds two lines of text.
     */
    const val MAX_CONTROL_SCALE: Float = 2f

    /** Whether a trailing status block should be drawn below the title at the current text scale. */
    val stacksTrailing: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalDensity.current.fontScale >= STACK_ABOVE_FONT_SCALE

    /**
     * [base] grown by the current text scale, so a row control keeps its proportion to the text
     * beside it.
     *
     * Never smaller than [base]: the pack's sizes are the floor, and a `fontScale` below 1 is a
     * user asking for more text on screen rather than for smaller buttons.
     */
    @Composable
    @ReadOnlyComposable
    fun controlSize(base: Dp): Dp =
        base * LocalDensity.current.fontScale.coerceIn(1f, MAX_CONTROL_SCALE)
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
            style = (if (isPlaying) typography.rowTitle else typography.rowTitleRegular)
                .wrapsAtWords(),
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(NeedlerRowLayout.TITLE_WEIGHT),
        )
        // The duration and the overflow glyph together, capped at
        // `NeedlerRowLayout.TRAILING_WEIGHT`: "11:48" at 200% is 26sp of tabular figures, and
        // unweighted it took that width off the title.
        Row(
            modifier = Modifier.weight(NeedlerRowLayout.TRAILING_WEIGHT, fill = false),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = duration,
                style = typography.duration,
                color = colors.textMuted,
                maxLines = 1,
            )
            if (onMoreClick != null) {
                NeedlerIconButton(
                    contentDescription = "More actions for $title",
                    onClick = onMoreClick,
                    visualSize = NeedlerRowLayout.controlSize(36.dp),
                ) {
                    NeedlerMoreIcon(
                        tint = colors.textMuted,
                        size = NeedlerRowLayout.controlSize(18.dp),
                    )
                }
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
                style = typography.body.wrapsAtWords(),
                color = if (enabled) colors.textPrimary else colors.disabled,
                modifier = Modifier.weight(NeedlerRowLayout.TITLE_WEIGHT),
            )
            // The value and the chevron share the trailing cap. A settings value is one short run
            // and not a status block, so it narrows with the ratio rather than dropping below the
            // label; see `NeedlerRowLayout`.
            Row(
                modifier = Modifier.weight(NeedlerRowLayout.TRAILING_WEIGHT, fill = false),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (value != null) {
                    Text(
                        text = value,
                        style = typography.bodySmall.wrapsAtWords(),
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showChevron) {
                    NeedlerChevronRightIcon(
                        tint = colors.textMuted,
                        size = NeedlerRowLayout.controlSize(16.dp),
                    )
                }
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
                    style = typography.body.wrapsAtWords(),
                    color = if (enabled) colors.textPrimary else colors.disabled,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = typography.caption.wrapsAtWords(),
                        color = colors.textMuted,
                    )
                }
            }
            // The switch keeps the pack's 48x28dp: it is the one row control that is already a
            // whole-row target, so growing it would only take width off the label it labels.
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
        !enabled -> colors.disabled
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
 * An album, artist or song row: artwork, title, subtitle, and what the state needs on the right.
 *
 * Ported from the search results (03, 10), the Pulls list (06) and the library list view (13). The
 * pack varies the height and the artwork size between those screens, so both are parameters; the
 * defaults are the search-result row.
 *
 * The [trailing] slot is where a [NeedlerStateBadge], a [NeedlerPullButton] or a Retry pill goes.
 * Anything interactive placed there stays a separate accessibility target.
 *
 * ## What the trailing slot may and may not take
 *
 * It is capped at [NeedlerRowLayout.TRAILING_WEIGHT] of the row's divisible width and, past
 * [NeedlerRowLayout.STACK_ABOVE_FONT_SCALE], drawn below the title instead of beside it.
 * `NeedlerRowLayout` has the whole reasoning and the measurements; what matters at this signature is
 * that the slot's content is laid out in a `Row` of its own, so a `Modifier.weight` written inside
 * it divides that inner row rather than this one. No caller did that, which is why the slot could be
 * wrapped without a signature change.
 *
 * ## The playing row
 *
 * [isPlaying] draws this row the way the pack draws a playing track everywhere else it appears: the
 * title in the accent colour with the record glyph beside it, as on the crate's Playing row (08)
 * and the album track list (04, 11). The library's Songs tab is the row that needed it - without
 * it, the track the app is playing is the only one on screen with no sign that it is.
 *
 * Unlike [NeedlerQueueRow], which draws the glyph only when its [trailing] slot is empty, this row
 * draws the glyph *and* [trailing], the glyph first so the trailing column still lines up down the
 * list. The crate has nothing in that slot, so there the two can never collide; the Songs tab puts
 * the track's duration there, and a row that dropped its duration only while playing would lose
 * information and make the list twitch as playback moves. The glyph scales with the text through
 * [NeedlerRowLayout.controlSize] and the title column holds [NeedlerRowLayout.TITLE_WEIGHT], so
 * both still fit at 200%, where the trailing block has moved under the title anyway.
 *
 * Drawing it is the point, not decoration. The accent title alone would make colour the only visual
 * channel for this state, which is no signal at all to a reader who cannot separate the accent blue
 * from the primary off-white. The glyph is the second channel; the ", playing" in the spoken
 * description is the third.
 *
 * @param contentDescription replaces the default "title, subtitle" reading outright, for rows that
 *   carry more than their two lines - a format badge, an on-device check. The ", playing" suffix is
 *   appended by *this row* to whichever reading is in use, default or replacement, so callers must
 *   never append it themselves: TalkBack would then say it twice.
 * @param artwork the artwork slot. Pass [AsyncAlbumArt] with `contentDescription = null`, since the
 *   row already names the album.
 */
@Composable
fun NeedlerAlbumRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    minHeight: Dp = NeedlerTheme.sizes.albumRowMinHeight,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    contentDescription: String? = null,
    artwork: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    // Resolved out here rather than inside `semantics`, so that the parameter and the
    // `SemanticsPropertyReceiver` extension of the same name cannot be confused for one another.
    val spoken = buildString {
        append(contentDescription ?: "$title, $subtitle")
        if (isPlaying) append(", playing")
    }
    // The trailing block moves below the title past `NeedlerRowLayout.STACK_ABOVE_FONT_SCALE`. The
    // reading does not change with it: the row is one merged semantics node either way, so a
    // screen-reader user hears the same sentence at every text scale.
    val stacked: Boolean = NeedlerRowLayout.stacksTrailing
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
                .semantics(mergeDescendants = true) { this.contentDescription = spoken }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            artwork?.invoke()
            Column(
                modifier = Modifier.weight(NeedlerRowLayout.TITLE_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = typography.rowTitle.wrapsAtWords(),
                    color = if (isPlaying) colors.accent else colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = typography.meta.wrapsAtWords(),
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Past the threshold the trailing block is drawn here, under the title, at the
                // row's full width. See `NeedlerRowLayout`: at 200% a 390dp row cannot hold two
                // text columns side by side, and a list has height to spend where it has no width.
                if (stacked && trailing != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = trailing,
                    )
                }
            }
            if (isPlaying) {
                NeedlerNowPlayingIcon(
                    tint = colors.accent,
                    size = NeedlerRowLayout.controlSize(18.dp),
                )
            }
            if (!stacked && trailing != null) {
                Row(
                    modifier = Modifier.weight(NeedlerRowLayout.TRAILING_WEIGHT, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
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
    val stacked: Boolean = NeedlerRowLayout.stacksTrailing

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
            // Not scaled, and it is the one row control that is not. The handle is a marking rather
            // than a target - the drag gesture belongs to the feature module and covers the row -
            // so growing it would take width off the title without giving anyone a bigger thing to
            // hit.
            NeedlerDragHandleIcon(tint = colors.textMuted)
        }
        artwork?.invoke()
        Column(
            modifier = Modifier.weight(NeedlerRowLayout.TITLE_WEIGHT),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = typography.rowTitle.wrapsAtWords(),
                color = if (isPlaying) colors.accent else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = typography.meta.wrapsAtWords(),
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (stacked && trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
        if (isPlaying && trailing == null) {
            NeedlerNowPlayingIcon(
                tint = colors.accent,
                size = NeedlerRowLayout.controlSize(18.dp),
            )
        }
        if (!stacked && trailing != null) {
            Row(
                modifier = Modifier.weight(NeedlerRowLayout.TRAILING_WEIGHT, fill = false),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing,
            )
        }
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
        !enabled -> colors.disabled
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
                modifier = Modifier.weight(NeedlerRowLayout.TITLE_WEIGHT),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = typography.rowTitle.wrapsAtWords(),
                    color = tint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = unavailableReason ?: detail,
                    style = typography.caption.wrapsAtWords(),
                    color = colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                NeedlerCheckIcon(
                    tint = colors.accent,
                    size = NeedlerRowLayout.controlSize(20.dp),
                )
            }
        }
        NeedlerHairline()
    }
}
