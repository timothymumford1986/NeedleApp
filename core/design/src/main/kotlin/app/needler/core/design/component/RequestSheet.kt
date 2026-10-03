package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The sheet a **Pull** opens: what is about to be requested, the one option the request carries, and
 * the two ways out.
 *
 * ## Why a sheet exists at all
 *
 * REQUIREMENTS.md "Placing a request" says of the `monitor_artist` flag: "Expose it as a secondary
 * toggle on the request sheet, since it is cheap here and would otherwise need the deferred following
 * screens." There was no request sheet - pulling was a bare one-tap - so the flag had no home and no
 * caller anywhere in the app passed it, which meant the only artist-following the product ships was
 * unreachable. This is that home.
 *
 * A sheet is also the right shape for the action on its own terms. A pull is a write to somebody
 * else's server that may need an administrator's approval and will take minutes; the same requirements
 * say "Requesting is one tap on any un-owned album", and one tap it remains - the tap that opens this,
 * with the confirm one thumb-movement away. The alternative considered and rejected was a long-press
 * menu holding the toggle: it hides the only option behind a gesture nothing else in this app uses.
 *
 * ## Free of library types on purpose
 *
 * Everything here is a `String`, a `Boolean` or a lambda, and the artwork is a slot. `:feature:search`
 * pulls the same albums from a different screen and `:feature:library` from two, so a parameter typed
 * as an `Album` would put a domain type in `:core:design` - which has no `:core:domain` dependency by
 * design - and force every caller to own one anyway.
 *
 * [monitorArtist] is hoisted rather than remembered inside. The flag has to reach the use case, the
 * caller is what holds the state a request is built from, and a toggle whose value lived in the sheet
 * would be lost to a rotation halfway through a decision.
 *
 * ## One album or a whole artist
 *
 * [title] and [subtitle] are deliberately not `albumTitle` and `artistName`. The same sheet places two
 * different requests: one album (`POST /api/v1/requests/new`) and every un-owned release group by an
 * artist (`POST /api/v1/requests/batch`, which REQUIREMENTS.md "Placing a request" caps at 500 items
 * the caller must chunk itself). The second is what the artist screen needs, and a sheet whose
 * parameters were named for the first would have forced a second sheet for it — with a second copy of
 * the `monitor_artist` toggle, which is exactly the duplication that ends in the flag being set on one
 * path and forgotten on the other.
 *
 * @param title what is being pulled: a record's name, or "Everything by The Marías".
 * @param subtitle the line under it: the artist for one album, or what the batch amounts to.
 * @param monitorArtist the current value of the `monitor_artist` flag.
 * @param onConfirm place the request. The caller reads [monitorArtist] for the flag.
 * @param onCancel dismiss without requesting.
 * @param monitorArtistSubject the artist the toggle names, when that is not [subtitle] — an
 *   artist-wide pull puts the name in [title] and a count in [subtitle]. `null` falls back to "this
 *   artist".
 * @param confirmLabel the primary button. "Pull" for one album; an artist-wide pull says how many.
 * @param qualityNote the server's own `quality_snapshot_summary` when it sent one, which is the only
 *   version of "what will be downloaded" guaranteed to be true. Falls back to [PULL_SHEET_EXPLANATION].
 * @param busy a request is in flight, so both buttons are inert rather than tappable twice.
 * @param artwork the cover slot. Pass [NeedlerArtwork]; omit it where there is nothing to show.
 */
@Composable
fun NeedlerRequestSheet(
    title: String,
    subtitle: String?,
    monitorArtist: Boolean,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    monitorArtistSubject: String? = subtitle,
    confirmLabel: String = "Pull",
    qualityNote: String? = null,
    busy: Boolean = false,
    artwork: (@Composable () -> Unit)? = null,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NeedlerTheme.shapes.sheet)
            .background(colors.surface)
            .padding(
                start = spacing.phoneGutterWide,
                end = spacing.phoneGutterWide,
                top = spacing.step6,
                // 40dp, which is the pack's own bottom inset on a sheet (the output picker uses the
                // same). Deliberately **not** `navigationBarsPadding()`: the navigation scaffold
                // consumes the bottom safe-drawing insets on its content slot, so an inset read here
                // is already zero, and a second one was a workaround for a bug that has been fixed
                // one layer up.
                bottom = spacing.step20,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.step8),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(NeedlerTheme.shapes.progress)
                .background(colors.grabberOnSheet),
        )

        // One word, uppercased, exactly as the output picker's sheet says "PLAY ON". It was
        // "Pull this album", which is wrong the moment the same sheet asks for a whole artist -
        // and the record being pulled is named on the line below anyway.
        Text(
            text = SHEET_TITLE,
            style = typography.sheetTitle,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.step7),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            artwork?.invoke()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.step1),
            ) {
                Text(
                    text = title,
                    style = typography.rowTitle,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = typography.meta,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Text(
            text = qualityNote ?: PULL_SHEET_EXPLANATION,
            style = typography.caption,
            color = colors.textMuted,
        )

        // The secondary option, and the only field on the sheet. NeedlerToggleRow makes the whole
        // 48dp row the switch and reports the state as a Switch to TalkBack, so REQUIREMENTS.md's
        // "every control carries a content description" and its 48dp minimum are both met by the
        // component rather than re-solved here.
        NeedlerToggleRow(
            label = MONITOR_LABEL,
            checked = monitorArtist,
            onCheckedChange = onMonitorArtistChange,
            subtitle = monitorArtistSubtitle(monitorArtistSubject),
            enabled = !busy,
            showDivider = false,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
        ) {
            NeedlerSecondaryButton(
                text = CANCEL_LABEL,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !busy,
                filledSurface = false,
                contentDescription = "Close without pulling " + title,
            )
            // The pack's one filled green button is "Pull this album" on screen 05, and it is green
            // precisely because acquiring music is not playback. Keeping that tone here is what makes
            // the sheet read as the same action the row behind it offered.
            NeedlerPrimaryButton(
                text = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                tone = NeedlerButtonTone.Positive,
                size = NeedlerButtonSize.Medium,
                enabled = !busy,
                leadingIcon = { tint -> NeedlerStrokeIcon(pathData = PathPull, tint = tint, size = 18.dp) },
                contentDescription = if (monitorArtist) {
                    "Pull " + title + ", and tell me about future releases"
                } else {
                    "Pull " + title
                },
            )
        }
    }
}

/**
 * [NeedlerRequestSheet] over a dimmed backdrop, filling whatever it is placed in.
 *
 * Drop this into a `Box` over a screen and the screen is behind it, dimmed, with a tap outside the
 * sheet dismissing it. A Material `ModalBottomSheet` was the alternative and is what `:app` uses for
 * the output picker; it is not used here for two reasons. It renders in a window of its own, so a
 * Robolectric screenshot of a screen captures everything except the sheet - and these screens are
 * tested by rendering them. And a pull is offered from a list row on three different screens, none of
 * which is a navigation destination the host could own the sheet for.
 *
 * The scrim is [app.needler.core.design.theme.NeedlerColors.artworkScrimStrong], the pack's only heavy
 * scrim value. Its name comes from where the pack first uses it - under a pull's progress ring - and
 * not from a restriction on where it may be used.
 *
 * ## Why the scrim draws no press indication
 *
 * It used to. The scrim is a `fillMaxSize` node, so the ripple `clickable` gives it by default was a
 * ripple the size of the window: tapping outside the sheet to dismiss it flashed the whole screen,
 * artwork and all, on the way out. A press indication exists to say *which* control was hit, and a
 * control that is the entire screen has nothing to disambiguate - so the feedback carried no
 * information and spent a full-screen repaint saying it.
 *
 * `indication = null` with an interaction source of its own is what
 * [app.needler.core.design.motion.NeedlerSplash] already does for the same kind of surface - a
 * full-bleed box whose whole job is to swallow one tap - and this follows it rather than inventing a
 * second answer. Nothing visible is lost: the sheet's own Cancel button is the drawn affordance and
 * the scrim is the shortcut beside it.
 *
 * The dismissal stays announced, and moves to where the platform expects it. `Role.Button` with a
 * content description announced the scrim as a button named "Close the pull options for X", which
 * puts a screen-sized control in the traversal order in front of the sheet it sits behind;
 * `onClickLabel` names the action without claiming the role, which is again what the splash does.
 * The rejected alternative was keeping the role and suppressing only the indication - it works, and
 * it leaves a screen-sized button that a screen reader has to step past to reach the two buttons the
 * user came for.
 */
@Composable
fun NeedlerRequestSheetOverlay(
    title: String,
    subtitle: String?,
    monitorArtist: Boolean,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    monitorArtistSubject: String? = subtitle,
    confirmLabel: String = "Pull",
    qualityNote: String? = null,
    busy: Boolean = false,
    artwork: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val scrimInteraction: MutableInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeedlerTheme.colors.artworkScrimStrong)
                .clickable(
                    interactionSource = scrimInteraction,
                    // A window-sized ripple says nothing and repaints everything. See the KDoc.
                    indication = null,
                    onClickLabel = SCRIM_DISMISS_LABEL + title,
                    onClick = onCancel,
                ),
        )
        NeedlerRequestSheet(
            title = title,
            subtitle = subtitle,
            monitorArtist = monitorArtist,
            onMonitorArtistChange = onMonitorArtistChange,
            onConfirm = onConfirm,
            onCancel = onCancel,
            monitorArtistSubject = monitorArtistSubject,
            confirmLabel = confirmLabel,
            // The sheet must swallow taps that land on it and hit nothing. Compose hit-testing
            // walks past a node with no pointer-input modifier, so without this a press on the
            // toggle's own label - or on the gap between the two buttons - would reach the scrim
            // underneath and dismiss the sheet mid-decision. `detectTapGestures` with an empty
            // body consumes; `clickable` would do it too but would also put a nameless button in
            // the accessibility tree over the whole sheet.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(Unit) { detectTapGestures { } },
            qualityNote = qualityNote,
            busy = busy,
            artwork = artwork,
        )
    }
}

/**
 * What the dismiss scrim's tap does, for TalkBack's "double tap to ...".
 *
 * Public so the wording can be asserted without rendering, and because it is the only reading the
 * scrim has now that it no longer announces itself as a button.
 */
const val SCRIM_DISMISS_LABEL: String = "Close the pull options for "

/** The subtitle under the monitor toggle, which names the artist when there is one to name. */
private fun monitorArtistSubtitle(artistName: String?): String =
    "Needler tells you when " + (artistName ?: "this artist") + " releases something new."

/**
 * What the server will do, when it has not said so itself.
 *
 * REQUIREMENTS.md "Design pack discrepancies": screen 05 says "Dropped Needle picks the best Soulseek
 * source", and the server also supports Usenet, so the copy names no source at all.
 */
const val PULL_SHEET_EXPLANATION: String =
    "Dropped Needle looks for the best source it can reach and imports it into your library. " +
        "FLAC first, then MP3 320."

private val SHEET_TITLE: String = "Pull".uppercase()

private const val MONITOR_LABEL: String = "Follow this artist"

private const val CANCEL_LABEL: String = "Not now"
