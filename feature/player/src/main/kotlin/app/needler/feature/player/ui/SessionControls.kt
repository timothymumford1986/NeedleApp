// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
// The same note sits at the top of LibraryFormat.kt.
@file:OptIn(ExperimentalLayoutApi::class, ExperimentalTime::class)

package app.needler.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.SleepTimer
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The two controls that describe the session rather than the track: where the sound goes, and when it
 * stops.
 *
 * The pack puts the output chip here, centred under the transport - REQUIREMENTS.md "Output": "The
 * current output is always named in the player... so a user never wonders where sound is going." The
 * sleep timer is not drawn anywhere, and this is where it belongs: the same pill, the same weight, the
 * same row, because it answers the same kind of question about the same session. Giving it a row of its
 * own was tried and rejected - it cost the tablet sidebar 72 dp of crate to say one word.
 *
 * `FlowRow` centred, so the two chips sit side by side where there is room and stack, still centred,
 * where there is not: "Living room speaker" and a countdown together are wider than a phone at anything
 * above the default text scale.
 *
 * ## The expansion lives here
 *
 * Whether the timer's choices are showing is pure presentation - it is not on `PlaybackState`, it does
 * not survive a process death, and no other surface needs to know - so it is `rememberSaveable` state in
 * this composable rather than a parameter threaded through two screens and two routes. That keeps both
 * screens stateless in the sense that matters: they still render from a literal [PlayerUiState] with no
 * Hilt graph, no session and no Media3, which is what lets every state of them be screenshotted.
 * [SleepTimerChoices] is public for the screenshot that wants the panel on its own.
 *
 * ## Why the expansion asks to be scrolled to
 *
 * An inline expansion is the shape [SleepTimerChip] argues for, and it is kept - but Now Playing's
 * column is a fixed stack of artwork, metadata, scrubber, transport and chips that already measures
 * close to a phone's height, so the choices unfold *below the fold* and the device audit found them
 * "below the viewable screen". The column has scrolled since it was written, for 200% text; what it
 * never did was scroll **to** anything, so six pills appeared somewhere a thumb could not see.
 *
 * [BringIntoViewRequester] is therefore asked once per expansion, which is a request rather than a
 * layout change: whichever ancestor scrolls answers it, and on the tablet sidebar - where nothing
 * scrolls and the crate underneath gives up the height instead - it is a no-op. The alternatives were
 * both worse. Making the panel a sheet or a popup is what [SleepTimerChip] rejects and for the reasons
 * it gives. Driving the host's `ScrollState` to its maximum would work only while the chips happen to
 * be the last thing on the screen, and would mean handing a scroll state down through two screens and
 * two routes to a composable whose own KDoc explains why its state is not threaded through either.
 *
 * ## Why the request asks for more than the panel
 *
 * Because "in view" and "reachable" are not the same thing, and the first device audit of this fix
 * found out which one a bare request buys. A bring-into-view is satisfied the moment the target is
 * inside the **scrollable's viewport**, and Now Playing's viewport is the whole screen: the bar's
 * height is reserved as trailing content inside the scroll, not carved out of it, so the viewport
 * reaches the screen's last pixel row. The scroll therefore stopped with the bottom row of pills
 * ending exactly on that row - measured at 2374 of 2374 on a 1080 by 2374 phone - which is inside the
 * viewport and underneath the gesture bar, with 209 px of slack below it that the request never used.
 *
 * So the rect asked for is the panel **plus the bottom inset**: satisfying it necessarily puts the
 * pills above the bar, because the strip the bar occupies is part of what has to be brought into view.
 * It is one request, it still costs the sidebar nothing, and it needs no cooperation from either host.
 *
 * REQUIREMENTS.md "Accessibility" is why this is not cosmetic: a touch target that ends on the screen's
 * last row is a control the gesture bar eats every tap of, however well it is drawn.
 *
 * **The alternatives, rejected.** Padding the panel itself by the inset instead of extending the rect
 * would get the same scroll out of an unmodified request - and would spend the inset's height twice on
 * a phone, where it is already reserved, and once in the tablet sidebar, where it is never reserved and
 * where the only thing it can take the height from is the crate. That is the trade this file's own
 * first paragraph refuses. Moving Now Playing's `windowInsetsPadding` outside its `verticalScroll`, so
 * that the viewport ended at the safe area and a bare request landed clear, is the other one: it turns
 * the bar's strip into a band nothing may draw in, which loses the edge-to-edge artwork the pack draws,
 * and it is the padding being *inside* the scroll that supplies the extent this deeper scroll consumes -
 * measured, the content can travel 229 px and the fix needs 130 of them; outside the scroll there would
 * have been 119, and the pills would have stopped 11 px short.
 *
 * @param emphasised accent weight for Now Playing (07), quieter for the tablet sidebar (09), exactly as
 *   the pack draws the output chip in the two places.
 */
@Composable
fun SessionControls(
    output: OutputTarget?,
    onChooseOutput: () -> Unit,
    timer: SleepTimer,
    onChooseSleepTimer: (SleepTimerChoice) -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = true,
) {
    var expanded: Boolean by rememberSaveable { mutableStateOf(false) }
    // Only a timed stop counts down, so only a timed stop needs a clock.
    val now: Instant = rememberTickingClock(active = timer is SleepTimer.At)
    // Not saveable: a request is not state, and there is nothing to restore after a process death.
    val choicesInView: BringIntoViewRequester = remember { BringIntoViewRequester() }
    // Read back from layout rather than worked out, because nothing here can work it out: six pills
    // take one row where there is width for them, two on a phone and more again at 200% text, and
    // the rect the request asks for has to be as tall as whatever `FlowRow` settled on.
    var choicesSize: IntSize by remember { mutableStateOf(IntSize.Zero) }
    // The system bar's own height, in pixels, straight from the window.
    //
    // Not from the ancestor that already padded for it. `windowInsetsPadding` consumes the inset for
    // other *modifiers* beneath it in the tree, through a modifier local, and not for a composable
    // that asks `WindowInsets` directly - so this is the real bar even though Now Playing has
    // already reserved its height as trailing content.
    val systemBarPx: Int = WindowInsets.safeDrawing.getBottom(LocalDensity.current)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutputChip(target = output, onClick = onChooseOutput, emphasised = emphasised)
            SleepTimerChip(
                timer = timer,
                now = now,
                expanded = expanded,
                onClick = { expanded = !expanded },
                emphasised = emphasised,
            )
        }
        if (expanded) {
            SleepTimerChoices(
                selected = SleepTimerOptions.selectedFor(timer, now),
                onChoose = { choice ->
                    // Folded away on choosing: the chip beside it now says what was chosen, and leaving
                    // six pills open underneath would say it twice.
                    expanded = false
                    onChooseSleepTimer(choice)
                },
                modifier = Modifier
                    .onSizeChanged { choicesSize = it }
                    .bringIntoViewRequester(choicesInView),
            )
            // Inside the branch, so entering it is the event, and keyed on the two figures the rect
            // is made of rather than on `Unit`: the size arrives one layout pass *after* the
            // composition that opened the panel, so an effect keyed on nothing would fire once, with
            // a zero size, and ask for nothing. Neither key moves when the countdown does, so this
            // still runs once per expansion - and it correctly runs again if the pills rewrap or the
            // bar changes height underneath an open panel.
            LaunchedEffect(choicesSize, systemBarPx) {
                if (choicesSize == IntSize.Zero) return@LaunchedEffect
                choicesInView.bringIntoView(
                    Rect(
                        left = 0f,
                        top = 0f,
                        right = choicesSize.width.toFloat(),
                        bottom = (choicesSize.height + systemBarPx).toFloat(),
                    ),
                )
            }
        }
    }
}
