package app.needler.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.needler.core.design.component.NeedlerIconButton
import app.needler.core.design.component.NeedlerLinearProgress
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.PathClose
import app.needler.core.design.theme.NeedlerTheme

/**
 * The update notice: one line, one action, one dismissal, and nothing at all the rest of the time.
 *
 * Stateless. It takes a [UpdateBannerUiState] and two callbacks and knows nothing about GitHub, the
 * package installer or Hilt — the same split `LibraryScreen` and `ConnectScreen` have from their
 * Routes, and for the same reason: every state this can be in can then be rendered from a literal.
 *
 * ## Subtle, deliberately
 *
 * The design pack draws no update surface, because when it was drawn there was no update mechanism.
 * So this is built from the pack's existing vocabulary rather than invented: the raised surface and
 * hairline of the bottom bar, the accent-coloured inline text action from Settings' "Sync now", the
 * 4dp progress track from the pull rows, and the close glyph from the search field. Nothing new is
 * added to `:core:design`, and nothing here reaches for a colour or a size that is not already a
 * token.
 *
 * Three decisions make it quiet rather than merely small:
 *
 *  * **It is absent, not empty.** With nothing to say the composable emits no node, so it costs no
 *    height, no layout pass and no line of blank chrome above the navigation bar. This is what lets
 *    the navigation scaffold treat it as a third slot beside `miniPlayer` and `sidebar` without the
 *    common case paying for it.
 *  * **One line, clipped.** `maxLines = 1` with an ellipsis, so a long version name pushes nothing
 *    around. The bar's height is the action's touch target and never more.
 *  * **No colour.** It uses the ordinary raised surface, not the accent and certainly not the
 *    destructive red. Nothing is wrong. There is simply a newer version.
 *
 * ## Accessibility
 *
 * The row is a polite live region, so TalkBack announces the line when it appears — and when it
 * changes to "Installing 1.2.3" — without interrupting whatever is being read. Polite, not
 * assertive: an update announcement has no claim to cut across anything.
 *
 * The progress bar carries its own `contentDescription` and reports its value through the range
 * info `NeedlerLinearProgress` sets, so the percentage is spoken rather than painted into a label.
 * The dismiss control is an icon with no text, so its content description is mandatory, which is
 * why `NeedlerIconButton` takes it as a non-null first parameter.
 *
 * @param onAction the single affordance: Update, Allow, or Retry, depending on
 *   [UpdateBannerUiState.actionLabel]. One callback rather than three, because from the listener's
 *   side it is one button that always means "carry on with this".
 * @param onDismiss hide this version's notice. Not "remind me later": there is no later, and no
 *   setting to find. A newer release will speak for itself.
 */
@Composable
fun UpdateBanner(
    state: UpdateBannerUiState,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The absent case, and the reason this can be a scaffold slot at all.
    if (!state.visible) return

    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val sizes = NeedlerTheme.sizes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // The pack separates stacked chrome with a hairline rather than a shadow; the bottom nav
        // bar and the mini player above it do exactly this.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sizes.hairlineThickness)
                .background(colors.hairline),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The bar keeps one height whether or not it has controls in it. The states that
                // have neither an action nor a dismissal — an install already handed to the
                // platform — would otherwise collapse to the height of a line of 12sp text and the
                // chrome would visibly jump underneath whatever the listener was reading.
                .defaultMinSize(minHeight = sizes.minTouchTarget)
                .padding(start = spacing.step8, end = spacing.step2),
            horizontalArrangement = Arrangement.spacedBy(spacing.step4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.message,
                style = NeedlerTheme.typography.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            val actionLabel = state.actionLabel
            if (actionLabel != null) {
                NeedlerTextButton(
                    text = actionLabel,
                    onClick = onAction,
                    // The label alone is ambiguous out of context — TalkBack reading "Update" gives
                    // no hint of what is being updated — so the description names the version.
                    contentDescription = "$actionLabel Needler ${state.versionName}",
                )
            }

            if (state.dismissible) {
                NeedlerIconButton(
                    contentDescription = "Dismiss the update notice",
                    onClick = onDismiss,
                    visualSize = sizes.minTouchTarget,
                ) {
                    NeedlerStrokeIcon(
                        pathData = PathClose,
                        tint = colors.textMuted,
                        size = spacing.step8,
                    )
                }
            }
        }

        if (state.showProgress) {
            NeedlerLinearProgress(
                progress = state.progress,
                // Accent, not the positive green. The pack reserves green for acquiring music, and
                // this is the app fetching itself.
                color = colors.accent,
                contentDescription = "Update download progress",
            )
        }
    }
}
