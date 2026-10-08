package app.needler.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.NeedlerAlbumBadge
import app.needler.core.design.component.NeedlerAlbumRow
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerStateBadge
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathChevronLeft
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.DownloadedAlbum

/**
 * The albums-on-this-device screen: everything on this device, largest first, each one removable.
 *
 * The design pack has no artboard for it - screen 12 drew this list inline - so every layout decision
 * here is an inference. The inference it now makes is the **Pulls** screen (06): a large
 * left-aligned title, a count under it, and rows of artwork, title, subtitle and one labelled
 * trailing control. It used to be the Licences and Diagnostics header over a hand-built row, and an
 * audit of the whole screenshot set found the result read as a different app - a small centred-looking
 * title bar, hairlines no other list draws, no artwork where every Pulls row has some, and a bare red
 * glyph where every other screen labels its controls.
 *
 * ## What moved, and what did not
 *
 * The rows are the same rows, in the same order, with the same per-album removal. Settings still
 * draws the largest few in place - see `StorageSectionState.INLINE_DOWNLOADED_ALBUMS` - and sends the
 * overflow here, so this screen is reached by a user whose library has outgrown that section rather
 * than by everyone. Nothing is capped or hidden on either side of that split: REQUIREMENTS.md
 * "Storage, and why there is no budget" leaves no storage limit in the product, which makes this list
 * the user's only lever on a full device, and [DownloadsUiState] records the alternatives weighed and
 * why each lost.
 *
 * ## The one green thing, and the one red thing
 *
 * REQUIREMENTS.md "Vocabulary" fixes three words and `NeedlerAlbumSource` pins a hue to each:
 * `positive` green means on this device, `accent` blue means on the server. This is *the* device
 * screen and it had no green on it at all - its only colour was the `destructive` red of fourteen
 * delete glyphs, which made the column the user least wants to hit the first thing the eye reaches.
 * The hues now say what they say everywhere else: one green **Device** badge in the header names the
 * tier the whole list is in, the row controls are neutral and labelled, and red appears once, on the
 * notice that reports bytes which have actually left the device.
 *
 * The badge carries the word as well as the hue, which is not decoration: `#bbdb9b` and `#aed5f2`
 * measure 1.01:1 against each other, so to a red-green colour-blind reader the hue is no signal at
 * all and the word is the whole message.
 *
 * ## The notice is on this screen, above the list, and offers a way back
 *
 * A removal reports what it freed, and that sentence has to be where the person who tapped remove is
 * looking. Inside Settings the notice is drawn under "Clear cached music", which for a row near the
 * bottom of a long list is well above the screen - so the one piece of feedback REQUIREMENTS.md
 * insists on ("a 'remove' that leaves the usage figure unchanged is the one thing that would make
 * this whole screen untrustworthy") was easy to miss. It is now drawn outside the scrolling list
 * entirely, so it cannot be scrolled past whichever row was tapped, and it carries **Undo**.
 * [DownloadsNotice] records why undo rather than a confirmation prompt.
 *
 * ## Accessibility
 *
 * REQUIREMENTS.md "Accessibility": "Every control carries a content description and transport
 * controls are at least 48 dp." Each row is one merged node naming the album, the artist and what it
 * occupies, so TalkBack reads it once rather than as three fragments; the remove control is a
 * separate target whose description names the album and the bytes it would free, because "remove" on
 * its own in a list of forty identical buttons says nothing. The notice is an assertive live region
 * because it reports a deletion and offers the only way back from one.
 *
 * Stateless, so every state it can be in - a full list, an empty device, one row being removed, the
 * notice a removal leaves - renders from a literal [DownloadsUiState] with nothing behind it.
 * [DownloadsRoute] is the stateful half.
 *
 * @param onRemove removes one album and deletes its bytes there and then. No confirmation, which is
 *   the behaviour this list already had inside Settings and which `DestructiveSettingsAction` argues
 *   for explicitly; the notice afterwards carries the Undo instead.
 * @param onUndo re-pins the album the notice names, which downloads it again.
 * @param onDismissNotice clears the notice, because it holds a control and an offer the user has
 *   declined should not stay on screen waiting to be hit.
 * @param onBack pops back to Settings.
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onRemove: (DownloadedAlbum) -> Unit,
    onUndo: (DownloadedAlbum) -> Unit,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (wide) TABLET_CONTENT_MAX_WIDTH else Dp.Unspecified)
                .fillMaxSize(),
        ) {
            DownloadsHeader(summary = state.summary, gutter = gutter, onBack = onBack)

            val notice: DownloadsNotice? = state.notice
            if (notice != null) {
                DownloadsNoticeCard(
                    notice = notice,
                    undoing = state.undoing,
                    gutter = gutter,
                    onUndo = onUndo,
                    onDismiss = onDismissNotice,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = gutter,
                    end = gutter,
                    top = spacing.step4,
                    bottom = spacing.step16,
                ),
            ) {
                if (state.isEmpty) {
                    item(key = "empty") { DownloadsEmptyState(onBack = onBack) }
                } else {
                    // Only over a list. The policy is about what does and does not leave this list,
                    // and over an empty one it is a paragraph with nothing to apply to - which is
                    // what `downloads-empty-phone.png` used to be.
                    item(key = "explainer") {
                        Text(
                            text = EXPLAINER,
                            style = NeedlerTheme.typography.caption,
                            color = colors.textMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = NeedlerTheme.spacing.step3),
                        )
                    }
                    items(
                        items = state.albums,
                        key = { album -> album.releaseGroupMbid.value },
                    ) { album ->
                        DownloadedAlbumListRow(
                            album = album,
                            rowState = state.rowState(album),
                            enabled = state.canRemove,
                            onRemove = { onRemove(album) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The screen's name, what is on the device, and the way back to Settings.
 *
 * Laid out as screen 06 lays out `PULLS`: the title at [app.needler.core.design.theme.NeedlerTypography.screenTitle]
 * on its own line, left-aligned at the gutter, with the count under it. The back control sits above
 * it rather than beside it, so the title starts at the leading edge instead of 44dp in - on a tablet
 * the old arrangement left the chevron floating a quarter of the way across the window with nothing
 * to its left, which is how a sub-screen comes to look like a modal.
 *
 * The chevron's row is inset by [BACK_GLYPH_INSET] less than everything below it, because
 * `NeedlerIconButton` centres a 24dp glyph in a 44dp box: subtracting the difference puts the glyph
 * itself on the gutter, which is the line the title and every row start on.
 *
 * @param summary `14 albums · 5.5 GB`, or empty while loading and on an empty device. It is drawn
 *   beside the **Device** badge rather than ending in "on this device", because the badge is the
 *   vocabulary's own word for that and saying it twice is how a screen ends up with two names for one
 *   state.
 */
@Composable
private fun DownloadsHeader(summary: String, gutter: Dp, onBack: () -> Unit) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.step2, bottom = spacing.step3),
    ) {
        Row(modifier = Modifier.padding(start = gutter - BACK_GLYPH_INSET, end = gutter)) {
            NeedlerIconButton(
                contentDescription = "Back to Settings",
                onClick = onBack,
                visualSize = 44.dp,
            ) {
                NeedlerStrokeIcon(PathChevronLeft, tint = colors.textPrimary, size = 24.dp)
            }
        }
        Column(
            modifier = Modifier.padding(start = gutter, end = gutter),
            verticalArrangement = Arrangement.spacedBy(spacing.step3),
        ) {
            Text(
                text = DOWNLOADS_TITLE.uppercase(),
                style = typography.screenTitle,
                color = colors.textPrimary,
                // No maxLines: a title that clips at 200% text is the failure REQUIREMENTS.md
                // "Accessibility" names outright. TextOverflow is named anyway so the intent is on
                // the record rather than left to the default.
                overflow = TextOverflow.Clip,
                modifier = Modifier.semantics { heading() },
            )
            if (summary.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.step4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeedlerStateBadge(badge = NeedlerAlbumBadge.OnDevice)
                    Text(
                        text = summary,
                        style = typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = summary + " on this device"
                        },
                    )
                }
            }
        }
    }
}

/**
 * What the last removal did, and the one tap that undoes it.
 *
 * Drawn **outside** the scrolling list, directly under the header, for the reason the screen's KDoc
 * gives: a user who removed the fortieth row has to be told at the fortieth row, and a notice that
 * lives in the list's first item is a notice they have scrolled past. It is a card rather than a line
 * of body text because it now carries controls, and because the plain line it used to be sat under
 * the policy paragraph in the same muted caption style as the policy paragraph.
 *
 * [DownloadsNotice.destructive] is the only thing on this screen drawn in
 * [app.needler.core.design.theme.NeedlerColors.destructive], and it marks exactly one event: bytes
 * that have left the device. A removal that freed nothing and a removal that failed are not drawn in
 * it - nothing was lost in either - which is what keeps the colour meaning one thing.
 *
 * Assertive rather than polite: the user has just deleted something, the sentence says what, and the
 * offer to put it back is on this node. A polite region waits for a gap in speech, and the thing it
 * would be waiting to announce is the only route back from an irreversible action.
 */
@Composable
private fun DownloadsNoticeCard(
    notice: DownloadsNotice,
    undoing: Boolean,
    gutter: Dp,
    onUndo: (DownloadedAlbum) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    val edge = if (notice.destructive) colors.destructive else colors.hairline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = gutter, end = gutter, bottom = spacing.step3)
            .clip(NeedlerTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = NeedlerTheme.sizes.hairlineThickness,
                color = edge,
                shape = NeedlerTheme.shapes.medium,
            )
            .padding(horizontal = spacing.step6, vertical = spacing.step5),
        verticalArrangement = Arrangement.spacedBy(spacing.step1),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = notice.spoken
            },
            verticalArrangement = Arrangement.spacedBy(spacing.step1),
        ) {
            Text(
                text = notice.headline,
                style = typography.body,
                color = if (notice.destructive) colors.destructive else colors.textPrimary,
            )
            Text(text = notice.detail, style = typography.caption, color = colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.step6)) {
            val undo: DownloadedAlbum? = notice.undo
            if (undo != null) {
                NeedlerTextButton(
                    text = "Undo",
                    onClick = { onUndo(undo) },
                    enabled = !undoing,
                    // "Undo" alone would promise the bytes back from nowhere. They are gone; what
                    // this does is ask the server for them again, and the label says so.
                    contentDescription = "Undo: put " + undo.title +
                        " back on this device by downloading it again",
                )
            }
            NeedlerTextButton(
                text = "Dismiss",
                onClick = onDismiss,
                color = colors.textSecondary,
                contentDescription = "Dismiss this message",
            )
        }
    }
}

/**
 * A device with nothing on it.
 *
 * A large bold heading and one paragraph, which is the shape every empty state in `:feature:pulls`
 * uses. What it replaces said the list was empty twice in two registers - a summary line reading
 * "Nothing is downloaded to this device." over a muted caption reading "Nothing is on this device
 * yet." - under a policy paragraph about a list that was not there.
 *
 * It names both ways music gets here, because a blank screen with no explanation reads as a fault
 * rather than as an empty state. One of the two is a switch on the screen the reader just came from,
 * so the way to it is a control rather than a sentence: quoting a setting's label and leaving the
 * reader to find it is the thing being fixed, not the quotation marks around it.
 */
@Composable
private fun DownloadsEmptyState(onBack: () -> Unit) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.step12),
        verticalArrangement = Arrangement.spacedBy(spacing.step6),
    ) {
        Text(
            text = EMPTY_HEADING,
            style = typography.displayCompact,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = EMPTY_BODY, style = typography.body, color = colors.textSecondary)
        NeedlerTextButton(
            text = "Back to Settings",
            onClick = onBack,
            contentDescription = "Back to Settings, where “Keep pulled albums on the device” is",
        )
    }
}

/**
 * One downloaded album on this screen: artwork, title, artist and size, and a labelled control.
 *
 * [NeedlerAlbumRow] rather than a hand-built row, which is the component ported from the Pulls list
 * and the search results. That buys the three things this list was missing against every other list
 * in the app: a thumbnail, a trailing slot that reads as a control rather than as an icon, and no
 * hairline, since the pack separates rows by their artwork and their height and not by rules.
 *
 * The size is in the subtitle beside the artist rather than in a column of its own, because
 * REQUIREMENTS.md "Storage, and why there is no budget" requires that "a size shown against an album
 * is the bytes actually on disk, not what the server says the album weighs" - it has to be *read*,
 * next to the name, by someone deciding which album to give up, and a right-aligned column of figures
 * beside a right-aligned column of controls is two things competing for the same corner.
 *
 * ## Why the control changes word instead of the list changing colour
 *
 * While a removal runs, this row's control reads **Removing** and every other row's reads **Remove**
 * and does not respond. The screen used to grey all fourteen controls out together with nothing to
 * say which album was going, which for an action that deletes files is the worst feedback available:
 * the user can see the screen has stopped and cannot see what it has stopped to do.
 */
@Composable
private fun DownloadedAlbumListRow(
    album: DownloadedAlbum,
    rowState: DownloadedRowState,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val sizeLabel: String = SettingsFormat.bytes(album.sizeBytes)
    val removing: Boolean = rowState == DownloadedRowState.Removing
    NeedlerAlbumRow(
        title = album.title,
        subtitle = album.artistName + " · " + sizeLabel,
        contentDescription = album.title + ", " + album.artistName + ", " + sizeLabel +
            " on this device" + if (removing) ", being removed" else "",
        artwork = {
            AsyncAlbumArt(
                model = ArtworkRef.Catalogue(album.releaseGroupMbid),
                // The row already names the album; a second reading would have TalkBack say it twice.
                contentDescription = null,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
            )
        },
        trailing = {
            NeedlerPillButton(
                text = if (removing) "Removing" else "Remove",
                onClick = onRemove,
                enabled = enabled && !removing,
                contentDescription = if (removing) {
                    "Removing " + album.title + " from this device"
                } else {
                    "Remove " + album.title + " from this device, freeing " + sizeLabel
                },
            )
        },
    )
}

/**
 * The row Settings still draws inline, unchanged.
 *
 * `SettingsScreen` draws the largest few downloaded albums in place and sends the overflow to this
 * screen, and in that context the row sits in a column of `NeedlerSettingsRow`s which all carry
 * hairlines - so it keeps its hairline and its glyph, and changing it would redraw a screen this file
 * does not own. [DownloadedAlbumListRow] is this screen's own row.
 *
 * Not a `NeedlerSettingsRow`: that row's whole width is the tap target, and here the tap target
 * deletes files.
 */
@Composable
internal fun DownloadedAlbumRow(
    album: DownloadedAlbum,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val sizeLabel: String = SettingsFormat.bytes(album.sizeBytes)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.listRowMinHeight)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Merged so TalkBack reads the album once, as a whole, and then finds the
                    // remove button as a separate target rather than three fragments and a button.
                    .semantics(mergeDescendants = true) {
                        contentDescription =
                            album.title + ", " + album.artistName + ", " + sizeLabel + " on this device"
                    },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = album.title,
                    style = typography.rowTitle,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artistName,
                    style = typography.meta,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = sizeLabel, style = typography.bodySmall, color = colors.textSecondary)
            NeedlerIconButton(
                contentDescription = "Remove " + album.title + " from this device, freeing " + sizeLabel,
                onClick = onRemove,
                enabled = enabled,
                visualSize = 36.dp,
            ) {
                NeedlerStrokeIcon(
                    pathData = PathClose,
                    tint = if (enabled) colors.destructive else colors.disabled,
                    size = 18.dp,
                )
            }
        }
        NeedlerHairline()
    }
}

/**
 * The screen's own title.
 *
 * Shortened from "Albums on this device", which is still the Settings row that opens this screen. At
 * `screenTitle` on the leading edge of a 390dp phone there is room for one line of about fifteen
 * characters, and the longer form wrapped to two - a sub-screen whose title takes more vertical space
 * than its first two rows. The word dropped is the one the row the user just tapped already said, and
 * what is left is the vocabulary's own word for the tier: REQUIREMENTS.md "Vocabulary" fixes
 * `Device` for music that is on this device.
 */
private const val DOWNLOADS_TITLE: String = "On this device"

/** `NeedlerIconButton` centres a 24dp glyph in a 44dp box; this is the difference, halved. */
private val BACK_GLYPH_INSET: Dp = 10.dp

/**
 * Why the list is ordered the way it is, and why nothing ever leaves it on its own.
 *
 * Two facts from REQUIREMENTS.md "Storage, and why there is no budget", said once, where the person
 * acting on them is standing: "Downloaded albums have no limit at all" and nothing evicts one, so the
 * only thing that frees these bytes is a tap here; and the order is by size because the question the
 * screen answers is "what is actually taking up the room".
 */
private const val EXPLAINER: String =
    "Largest first. Nothing here is ever removed automatically, however full the device gets, so " +
        "removing an album is the only thing that frees the room it is using."

/** The heading over an empty device, at the size every other empty state in the app uses. */
private const val EMPTY_HEADING: String = "Nothing on this device"

/**
 * What the screen says with nothing on the device.
 *
 * It names both ways music gets here. The second is a switch in Settings, and the empty state puts a
 * control next to this paragraph rather than leaving the reader to go and find a label it has only
 * quoted. The quotation marks are the typographic pair, which is what every other quoted label in the
 * app uses.
 */
private const val EMPTY_BODY: String =
    "“Pull to device” on an album downloads it, and “Keep pulled albums on the " +
        "device” in Settings downloads anything this device pulls."
