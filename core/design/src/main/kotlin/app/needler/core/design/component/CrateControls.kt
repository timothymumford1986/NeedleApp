package app.needler.core.design.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * Getting something into the crate, from wherever in the library the user is standing.
 *
 * REQUIREMENTS.md "Queue" describes the crate as "a Playing row, an Up next list with a count and
 * total duration, drag-to-reorder handles, and a Clear action", living "on the device" and
 * persisting "across restarts". Everything in that sentence was built except a way to put a record
 * into it: Play and Shuffle replace the crate on every surface, so lining up a second album meant
 * waiting for the first to finish. A queue nothing can be added to is a now-playing list.
 *
 * ## Two actions, not one
 *
 * "Add to the crate" appends; "Play next" inserts after the Playing row. They are different wants -
 * one is "when this finishes", the other is "straight after this track" - and
 * `PlaybackController.enqueue` already distinguishes them with its `playNext` flag, whose KDoc
 * calls them "the product's two actions". Shipping only the append would leave half of a capability
 * the player boundary already offers unreachable, and "play next" cannot be reconstructed from the
 * append by a caller, because it needs the current queue index and no feature module may know it.
 *
 * Two actions is also why this is a menu rather than a pair of buttons. One button each would put
 * five controls in the album screen's action row, where the pack draws three and Play would stop
 * being visibly the primary. The album screen's old overflow menu was removed for holding a single
 * item that duplicated a link beside it - see the note in `AlbumScreen`'s top bar - and that
 * reasoning is kept here rather than reversed: a menu earns its place when it holds actions with
 * nowhere else to live, and these two have nowhere else to live on any surface that opens it.
 *
 * ## The vocabulary is fixed
 *
 * "The crate", never "queue", in everything the user reads or hears - the same vocabulary
 * [NeedlerQueueRow] already speaks when it offers "Move up in the crate".
 *
 * @param subject what is being added, already guarded against blankness by the caller: an album
 *   title, a track title, an artist's name. It is spoken in every description this control carries,
 *   so "Add to the crate" is never ambiguous about *what* on a screen full of rows.
 * @param emphasised draws the control as a surface pill rather than as a bare glyph, for the album
 *   and artist action rows. A transparent glyph between two filled buttons reads as a gap that
 *   happens to have dots in it; in a list row, where every trailing control is a bare glyph, the
 *   pill would be the thing that looked out of place. Same control, same target, two surfaces.
 */
@Composable
fun NeedlerCrateControl(
    subject: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddToCrate: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    visualSize: Dp = NeedlerRowLayout.controlSize(32.dp),
) {
    val colors = NeedlerTheme.colors
    Box(modifier = modifier) {
        NeedlerIconButton(
            // Says what the control leads to rather than what it is. "More actions" is what the
            // playlists list says, and the device audit reads these labels aloud: a user who
            // cannot see the glyph has no other way to learn that this is where the crate lives.
            contentDescription = "Crate actions for " + subject +
                ". Add to the crate, or play next.",
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            visualSize = visualSize,
            background = if (emphasised) colors.surfaceRaised else Color.Transparent,
            shape = NeedlerTheme.shapes.pill,
        ) {
            NeedlerMoreIcon(
                tint = if (emphasised) colors.textSecondary else colors.textMuted,
                // Scaled with the text, like the heart and the track row's own overflow glyph: a
                // row that doubles in height must not keep a 32dp target. See `NeedlerRowLayout`.
                size = NeedlerRowLayout.controlSize(if (emphasised) 20.dp else 18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            CrateMenuItem(
                label = ADD_TO_CRATE,
                description = "Add " + subject + " to the end of the crate",
                enabled = enabled,
                onTap = {
                    onExpandedChange(false)
                    onAddToCrate()
                },
            )
            CrateMenuItem(
                label = PLAY_NEXT,
                description = "Play " + subject + " next, after the track playing now",
                enabled = enabled,
                onTap = {
                    onExpandedChange(false)
                    onPlayNext()
                },
            )
        }
    }
}

/**
 * One item of the crate menu.
 *
 * `DropdownMenuItem`'s own minimum height is Material's 48dp, which is also REQUIREMENTS.md
 * "Accessibility"'s minimum touch target, so the item keeps its default size rather than being
 * padded to reach it. The description is set here rather than left to the label, because the label
 * alone - "Add to the crate" - does not say which row the menu was opened from, and a menu floats
 * away from the row that anchored it.
 *
 * The semantics action is declared alongside the description for the reason given on
 * [needlerRowActions]: clearing the subtree is what replaces the label with the fuller reading, and
 * a cleared item that kept no action of its own would be inert to TalkBack.
 */
@Composable
private fun CrateMenuItem(
    label: String,
    description: String,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    DropdownMenuItem(
        enabled = enabled,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label = label) {
                onTap()
                true
            }
        },
        text = {
            Text(
                text = label,
                style = NeedlerTheme.typography.body,
                color = if (enabled) colors.textPrimary else colors.disabled,
            )
        },
        onClick = onTap,
    )
}

/**
 * A row whose tap plays something, given a long press that does not.
 *
 * ## What this fixes
 *
 * Long-pressing a track row played that track and replaced the crate. `Modifier.clickable` fires on
 * the release however long the hold was, so a gesture a user tries speculatively - the gesture that
 * in every other music player opens exactly this menu - silently discarded their queue. Observed on
 * the device going from Alan Walker's *Different World* to Late Night Alumni's *Empty Streets*: one
 * long press, the crate gone, nothing said about it.
 *
 * So the gesture is claimed. A long press now opens the crate menu, which is what the user was
 * reaching for, and because [combinedClickable] consumes the long press the tap-to-play no longer
 * fires behind it.
 *
 * ## Why it replaces the row's own click rather than wrapping it
 *
 * The row components own both their gesture and their spoken reading: [NeedlerAlbumRow] and
 * [NeedlerTrackRow] each put `clickable` and a merging `semantics` on the same node. A second
 * clickable wrapped around either leaves two gesture handlers - the tap fires twice, once per node -
 * and two accessibility nodes, of which the outer carries the actions and no name. The fix is to
 * pass `onClick = null` to the row, so it only draws, and to apply this modifier to that same row:
 * `clearAndSetSemantics` then flattens the subtree to one node carrying the reading, the tap and the
 * long press together.
 *
 * The alternative - adding `onLongClick` to [NeedlerAlbumRow] and [NeedlerTrackRow] themselves - is
 * a smaller change at each call site, and was rejected for its blast radius: those two rows are
 * drawn by every list in the application, and a new gesture parameter on both would change how all
 * of them handle a long press at once. This modifier changes the rows whose tap is destructive and
 * nothing else.
 *
 * ## Two consequences the caller has to honour
 *
 * [description] is the **whole** reading, because the row's own is cleared: anything the row would
 * have appended - the ", playing" suffix, the spoken duration - has to be in the string passed here
 * or it is lost. And nothing inside the row stays reachable as its own accessibility target, so a
 * row using this must not also carry an interactive `trailing` slot or an `onMoreClick`. Put the
 * crate control beside the row as a sibling instead, which is what the album screen already does
 * with the per-track star.
 *
 * @param description the complete spoken reading of the row.
 * @param tapLabel what the tap does, for TalkBack's "double tap to …" - e.g. "Play".
 * @param longPressLabel what the long press does, which is the half of this a user cannot discover
 *   by looking.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.needlerRowActions(
    description: String,
    onTap: () -> Unit,
    tapLabel: String,
    onLongPress: () -> Unit,
    longPressLabel: String,
): Modifier = this
    .combinedClickable(
        role = Role.Button,
        onClickLabel = tapLabel,
        onLongClickLabel = longPressLabel,
        onLongClick = onLongPress,
        onClick = onTap,
    )
    // Both actions are declared again here rather than left to `combinedClickable`'s own semantics.
    // They cost nothing where they duplicate it, and they are the difference between a reachable row
    // and an inert one if the clearing node and the clickable one are ever collapsed in the other
    // order: TalkBack activates a row through the semantics action, not through the gesture.
    .clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        onClick(label = tapLabel) {
            onTap()
            true
        }
        onLongClick(label = longPressLabel) {
            onLongPress()
            true
        }
    }

/** The menu's first item, and the words every surface uses for it. */
const val ADD_TO_CRATE: String = "Add to the crate"

/** The menu's second item. */
const val PLAY_NEXT: String = "Play next"

/** What a long press does, for the TalkBack hint on a row that offers one. */
const val CRATE_LONG_PRESS_LABEL: String = "Crate actions"
