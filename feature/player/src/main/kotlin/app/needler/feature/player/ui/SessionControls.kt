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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            )
        }
    }
}
