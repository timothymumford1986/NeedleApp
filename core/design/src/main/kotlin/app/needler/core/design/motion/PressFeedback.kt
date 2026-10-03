package app.needler.core.design.motion

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import app.needler.core.design.theme.NeedlerColors
import app.needler.core.design.theme.NeedlerTheme
import kotlinx.coroutines.launch

/**
 * The press feedback a Needler control draws, and the one thing reduced motion changes about it.
 *
 * ## What this fixes
 *
 * REQUIREMENTS.md "Motion" requires that animation be suppressed when the system animator duration
 * scale is zero - "Remove animations" in Accessibility settings - and [LocalReducedMotion] has
 * implemented that check since the splash was written. Press feedback never consulted it. Under
 * [app.needler.core.design.theme.NeedlerTheme] the default `LocalIndication` is Material 3's
 * `RippleNodeFactory` (observed, by reading it back inside the theme in a Robolectric composition),
 * which expands and fades on its own clock on every tap on every control in the application whatever
 * the user has asked the system for. It was the last animation in the product running outside this
 * package's policy.
 *
 * ## The press state appears, it just stops arriving
 *
 * Under reduced motion the pressed state is drawn **instantly and held** for as long as the finger is
 * down, rather than growing into place. It is not removed, and that distinction is the whole decision:
 * a control that answers a press with nothing at all is worse than one that answers it abruptly,
 * because the user is then left guessing whether the tap registered - and the population this setting
 * exists for is the one least able to afford that guess. "Suppress the animation" is read here as
 * suppressing the *motion*, not the information the motion was carrying.
 *
 * The alternative considered and rejected was `indication = null` under reduced motion. It is a
 * two-character change that reads as though it honours the requirement, and it silences every control
 * in the product for the users who asked only that things stop moving.
 *
 * ## Why this is an `Indication` and not a drawn overlay at each call site
 *
 * Because `Modifier.clickable`, `Modifier.selectable` and `Modifier.indication` all resolve an
 * [Indication] from `LocalIndication` when they are not handed one, so expressing the policy as an
 * [Indication] is what lets [app.needler.core.design.theme.NeedlerTheme] install it once and reach
 * **every** interaction modifier in the application, including the three dozen list rows and scrims
 * that never went near a shared component. A per-call-site overlay would have been the same drawing
 * code copied into the five components that do use
 * [app.needler.core.design.component.needlerPressSurface] and absent from everything else - and the
 * device audit's finding about press indication was precisely that thirty-six interaction modifiers
 * had been written by hand and one of them had been thought about.
 */
@Composable
fun needlerPressIndication(surface: Color = Color.Transparent): Indication {
    val reducedMotion: Boolean = LocalReducedMotion.current
    val colors: NeedlerColors = NeedlerTheme.colors
    val instant: Indication? = remember(reducedMotion, surface, colors) {
        instantPressIndicationFor(reducedMotion, surface, colors)
    }
    // `LocalIndication.current` is read only when it is going to be returned, so a theme that
    // installs the result of this function cannot read its own output back.
    return instant ?: LocalIndication.current
}

/**
 * The decision, with nothing composable about it: the indication to substitute, or null to keep
 * whatever the platform was going to draw.
 *
 * Split out so the policy can be asserted in a plain JVM test. `:core:design` carries no Robolectric
 * rig - its five test classes are all pure - and adding one to a module every other module depends on
 * is a larger change than the thing being checked. The composed half is one expression above it.
 *
 * @param surface the fill the press will be drawn over, which decides whether the overlay is pale or
 *   dark. [Color.Transparent] is the honest default: a control with no fill of its own sits on the
 *   near-black olive canvas.
 */
internal fun instantPressIndicationFor(
    reducedMotion: Boolean,
    surface: Color,
    colors: NeedlerColors,
): Indication? {
    if (!reducedMotion) return null
    return InstantPressIndication(if (surface.isLight()) colors.canvas else colors.textPrimary)
}

/**
 * Whether a fill wants a dark press overlay rather than a pale one.
 *
 * The app is dark-only, so a transparent or surface-filled control takes the pale overlay. The two
 * fills that do not are the accent `#aed5f2` and the positive `#bbdb9b` - the pack's only pale fills,
 * worn by Play and by Pull - where a pale overlay would be invisible.
 *
 * Alpha is tested first because [Color.Transparent] is black with no alpha: it would read as dark by
 * luminance, when what will actually be seen through it is the canvas, which is also dark. Same
 * answer, different reason, and the explicit test is what stops a translucent pale scrim from being
 * classified by a colour nobody can see.
 *
 * The threshold is the midpoint of relative luminance rather than a list naming the two pale tokens,
 * so a token added to the palette later cannot get the wrong overlay by not being on the list.
 */
private fun Color.isLight(): Boolean = alpha > 0.5f && luminance() > 0.5f

/**
 * A flat overlay for as long as a press is held: no enter animation, no exit animation, no ripple
 * origin, and no animation clock involved at any point.
 *
 * ## The alpha is the ripple's own destination
 *
 * [PRESSED_STATE_LAYER_ALPHA] is the state-layer opacity Material settles a pressed control at, so
 * this draws the colour the animated indication would have arrived at and skips the arrival. That is
 * deliberate over picking a value that reads well on its own: a user turning "Remove animations" on
 * and off should see the same pressed control either way, differing only in how it got there.
 *
 * [app.needler.core.design.theme.NeedlerColors.surfaceRaised] is the palette's own "pressed and
 * selected rows" value and is **not** used here. It is an opaque colour for a row that draws its own
 * background, and an indication has to work over the accent and positive fills too, where an opaque
 * olive would replace the control rather than press it.
 */
internal data class InstantPressIndication(internal val overlay: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        InstantPressNode(interactionSource, overlay)

    internal companion object {
        /** Material's pressed state-layer opacity, which is where its ripple comes to rest. */
        const val PRESSED_STATE_LAYER_ALPHA: Float = 0.12f
    }
}

/**
 * The node [InstantPressIndication] creates: collect presses, draw a rectangle while one is held.
 *
 * ## Presses are counted, not latched
 *
 * [InteractionSource.interactions] is a stream of press, release and cancel events, and more than one
 * press can be outstanding at once - a second finger landing on a control while the first is still
 * down, which a toggle under a fast tap sequence produces routinely. A boolean would be cleared by the
 * first release and leave the still-held second press undrawn, so the count is kept and the overlay is
 * drawn while it is positive.
 */
private class InstantPressNode(
    private val interactionSource: InteractionSource,
    private val overlay: Color,
) : Modifier.Node(), DrawModifierNode {

    private var pressed = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val wasDrawn: Boolean = pressed > 0
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release -> pressed--
                    is PressInteraction.Cancel -> pressed--
                    else -> Unit
                }
                // Floored rather than trusted. A node attached while a press was already outstanding
                // sees the release without having seen the press, and a negative count would then
                // swallow the next genuine one.
                if (pressed < 0) pressed = 0
                if (wasDrawn != (pressed > 0)) invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // Drawn over the content and inside whatever the chain has already clipped to, which is how
        // the overlay takes the control's shape without being told what that shape is.
        if (pressed > 0) {
            drawRect(color = overlay, alpha = InstantPressIndication.PRESSED_STATE_LAYER_ALPHA)
        }
    }
}
