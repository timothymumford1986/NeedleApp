// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
// The same note sits at the top of LibraryFormat.kt.
@file:OptIn(ExperimentalLayoutApi::class, ExperimentalTime::class)

package app.needler.feature.player.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.SleepTimer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay

/**
 * The sleep timer's chip and its choices, for Now Playing and the tablet sidebar.
 *
 * REQUIREMENTS.md "Player features" lists the timer with the design reference "Not drawn" and the whole
 * requirement in five words: "End of track or a duration". Everything behind it already existed -
 * `SleepTimer` in the domain, `SleepTimerDecision` in the player service, and a coordinator that stops
 * playback and disarms the timer when it fires - and nothing could turn it on, because
 * `PlaybackController` declared no setter and the repository's was a no-op. These are the controls that
 * close that loop; [SessionControls] places them.
 *
 * ## Why they look like the output chip
 *
 * With nothing drawn to copy, the nearest thing in the pack is the "Play on" chip directly beside it: a
 * hairline pill with a glyph, a short label and a state a listener wants to read without hunting for it.
 * So the sleep timer is the same pill at the same two weights - accent on 07, secondary in the sidebar
 * on 09 - and the choices unfold beneath it as the same preset pills screen 19 uses for the equaliser.
 * Not a sheet and not a menu: screen 21 is a sheet precisely because `:app` has to host one and pass the
 * dismissal in, and a menu in a popup would have to invent its own surface, elevation and scrim to look
 * like anything in this design.
 */
@Composable
fun SleepTimerChip(
    timer: SleepTimer,
    now: Instant,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasised: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val armed: Boolean = timer.isArmed
    // Armed reads in the transport's own "on" colour. Unarmed is textSecondary rather than the dim
    // grey: `#6f7a68` is what the pack draws placeholders and inactive controls in - the `disabled`
    // token - and an unarmed timer is neither. It is a live control, and at 3.82:1 on the surface it
    // sits on the dim value was the least findable thing on the sheet.
    val tint: Color = when {
        armed && emphasised -> colors.accent
        armed -> colors.textPrimary
        else -> colors.textSecondary
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
            .clip(NeedlerTheme.shapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .border(
                width = NeedlerTheme.sizes.hairlineThickness,
                color = colors.hairline,
                shape = NeedlerTheme.shapes.pill,
            )
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                // The state first and the action second, because the state is the part someone reaching
                // for this control in the dark actually wants read back to them.
                contentDescription = PlayerFormat.spokenSleepTimer(timer, now) + ". " +
                    if (expanded) "Hide the choices" else "Change the sleep timer"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerMoonIcon(tint = tint, size = if (emphasised) 16.dp else 14.dp)
        Text(
            text = PlayerFormat.sleepTimerLabel(timer, now),
            style = if (emphasised) {
                NeedlerTheme.typography.metaStrong
            } else {
                NeedlerTheme.typography.caption
            },
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The six choices, as the pack's preset pills.
 *
 * Stateless and public, so the panel can be screenshotted without an expansion state and so the two
 * screens share one row of buttons rather than each listing the durations themselves.
 *
 * `FlowRow` rather than `Row`: six pills do not fit one line in a 400 dp sidebar, and at 200% text scale
 * they do not fit one line anywhere. REQUIREMENTS.md asks that text scale to 200% without clipping.
 */
@Composable
fun SleepTimerChoices(
    selected: SleepTimerChoice?,
    onChoose: (SleepTimerChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (choice in SleepTimerOptions.offered) {
            NeedlerPillButton(
                text = SleepTimerOptions.label(choice),
                onClick = { onChoose(choice) },
                selected = choice == selected,
                contentDescription = SleepTimerOptions.spokenLabel(choice),
            )
        }
    }
}

/**
 * Wall-clock time, re-read once a second, and only while something is counting down.
 *
 * The countdown is the one thing the player draws that changes with nobody doing anything. It is read
 * here, inside the label, rather than carried on
 * [app.needler.core.domain.playback.PlaybackState]: that flow emits when a person changed something, and
 * a "minutes remaining" field on it would make it emit once a second for as long as a timer is armed -
 * which is exactly the cost `PlaybackController` splits `observeProgress` off to avoid. One small text
 * node recomposing is not the same as the artwork, the titles, the crate and the transport recomposing.
 *
 * [active] false starts no coroutine at all, so an unarmed timer - which is almost always - costs
 * nothing and leaves the screen able to settle. REQUIREMENTS.md's accessibility section is the reason
 * that matters: a surface that invalidates forever never reaches idle for assistive technology. An armed
 * timer does have to keep counting, which is why [TICK_MS] is as slow as the value on screen allows.
 */
@Composable
internal fun rememberTickingClock(active: Boolean): Instant {
    val state = produceState(initialValue = systemNow(), active) {
        if (!active) return@produceState
        while (true) {
            value = systemNow()
            delay(TICK_MS)
        }
    }
    return state.value
}

/**
 * Half a minute.
 *
 * The label is drawn to the minute, so a one-second tick would invalidate the node sixty times to change
 * it once - and REQUIREMENTS.md's accessibility section is the reason that matters: every invalidation is
 * a content-changed event, and a surface that produces a stream of them never reaches idle for assistive
 * technology. Half the resolution of the value shown is fast enough that no stale figure is ever visible,
 * and slow enough that an armed timer costs the accessibility tree two events a minute.
 */
private const val TICK_MS: Long = 30_000L

/**
 * Wall-clock now.
 *
 * `System.currentTimeMillis()` through `Instant.fromEpochMilliseconds`, which is how the rest of this
 * codebase asks the time - `PlaybackCoordinator` and `DefaultFavouriteRepository` both take a
 * `nowMillis: () -> Long` defaulted to it. One way of reading the clock across the project is worth more
 * than a shorter expression here.
 */
private fun systemNow(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

/**
 * The choices the sleep timer offers.
 *
 * REQUIREMENTS.md fixes two of them - "End of track or a duration" - and leaves the durations open, so
 * these four are a product decision in the presentation layer, where the equaliser's preset curves also
 * live ("Gain curves belong to the presentation layer, not the domain"). The domain models an instant; it
 * has no opinion about which durations a person is offered, and giving it one would mean editing a model
 * to change a row of buttons.
 *
 * @param minutes the duration this choice arms, or null for the two choices that are not durations.
 */
enum class SleepTimerChoice(val minutes: Int?) {
    OFF(minutes = null),
    END_OF_TRACK(minutes = null),
    MINUTES_15(minutes = 15),
    MINUTES_30(minutes = 30),
    MINUTES_45(minutes = 45),
    MINUTES_60(minutes = 60),
}

/** Labels, and the arithmetic between a tapped pill and a [SleepTimer]. */
object SleepTimerOptions {

    /** The order the pills are drawn in: off, the end of this track, then the durations, ascending. */
    val offered: List<SleepTimerChoice> = listOf(
        SleepTimerChoice.OFF,
        SleepTimerChoice.END_OF_TRACK,
        SleepTimerChoice.MINUTES_15,
        SleepTimerChoice.MINUTES_30,
        SleepTimerChoice.MINUTES_45,
        SleepTimerChoice.MINUTES_60,
    )

    /** The durations alone, ascending, for recovering a choice from an armed instant. */
    private val durations: List<SleepTimerChoice> = offered.filter { it.minutes != null }

    /** The label on the pill. */
    fun label(choice: SleepTimerChoice): String = when (choice) {
        SleepTimerChoice.OFF -> "Off"
        SleepTimerChoice.END_OF_TRACK -> "End of track"
        SleepTimerChoice.MINUTES_15 -> "15 min"
        SleepTimerChoice.MINUTES_30 -> "30 min"
        SleepTimerChoice.MINUTES_45 -> "45 min"
        SleepTimerChoice.MINUTES_60 -> "1 hr"
    }

    /** The same, said rather than printed: TalkBack reads "15 min" as "fifteen min". */
    fun spokenLabel(choice: SleepTimerChoice): String = when (choice) {
        SleepTimerChoice.OFF -> "Sleep timer off"
        SleepTimerChoice.END_OF_TRACK -> "Stop at the end of this track"
        SleepTimerChoice.MINUTES_15 -> "Stop in 15 minutes"
        SleepTimerChoice.MINUTES_30 -> "Stop in 30 minutes"
        SleepTimerChoice.MINUTES_45 -> "Stop in 45 minutes"
        SleepTimerChoice.MINUTES_60 -> "Stop in 1 hour"
    }

    /**
     * The timer a tapped pill arms, against the clock at the moment of the tap.
     *
     * A duration becomes an instant here rather than later, because an instant is what [SleepTimer.At]
     * holds - and it holds an instant so that nothing has to count down. See [SleepTimer] for why a
     * counter is the wrong shape for this.
     */
    fun timerFor(choice: SleepTimerChoice, now: Instant): SleepTimer = when (choice) {
        SleepTimerChoice.OFF -> SleepTimer.Off
        SleepTimerChoice.END_OF_TRACK -> SleepTimer.EndOfTrack
        else -> SleepTimer.At(now + (choice.minutes ?: 0).minutes)
    }

    /**
     * Which pill to light up for an armed timer.
     *
     * Exact for [SleepTimer.Off] and [SleepTimer.EndOfTrack]. For [SleepTimer.At] it is an inference,
     * deliberately: the domain stores the moment to stop, not the pill that was pressed, and that is the
     * right trade - a stored duration would have to be counted down by something, and a counter that
     * runs through a pause is the bug `SleepTimerDecision` exists to avoid.
     *
     * So the shortest offered duration that still covers what is left wins: 30 minutes chosen shows 30
     * for its whole half hour and only moves to a shorter pill when the remaining time genuinely fits
     * inside one. An instant that has already passed selects nothing, because it is about to be
     * disarmed.
     */
    fun selectedFor(timer: SleepTimer, now: Instant): SleepTimerChoice? = when (timer) {
        SleepTimer.Off -> SleepTimerChoice.OFF
        SleepTimer.EndOfTrack -> SleepTimerChoice.END_OF_TRACK
        is SleepTimer.At -> {
            val left: Duration = timer.instant - now
            if (!left.isPositive()) {
                null
            } else {
                durations.firstOrNull { choice -> left <= (choice.minutes ?: 0).minutes }
                    ?: durations.last()
            }
        }
    }
}
