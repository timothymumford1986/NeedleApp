package app.needler.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Needler's palette, as much of it as a watch has any use for.
 *
 * ## Why these are literals and not `:core:design`
 *
 * There is no Wear screen in the design pack - it covers phone and tablet only - and
 * `wear/build.gradle.kts` deliberately does not depend on `:core:design`. That is not an oversight
 * to route around: `:core:design` is built on `androidx.compose.material3`, the phone and tablet
 * Material 3, and the convention plugin goes out of its way to keep that artifact off this module's
 * classpath so the watch cannot accidentally render phone components. Depending on it would drag the
 * whole of Material 3, Coil and the adaptive libraries onto a watch to reuse nine colour values.
 *
 * So the values are transcribed, with the same names and the same roles they carry in
 * `app.needler.core.design.theme.NeedlerColors`. A repaint of the design system means editing this
 * file too, and that duplication is the deliberate price of the module boundary. Anything here that
 * is not a straight copy is called out below.
 *
 * ## What the watch leaves behind
 *
 * The pack's typography is Space Grotesk and Hanken Grotesk, pulled at runtime through
 * `ui-text-google-fonts`. The watch uses the device's own family instead. Downloadable fonts on a
 * watch mean another provider dependency, a fetch that may have to come over Bluetooth, and a
 * fallback flash on a screen an inch across - for a difference nobody can see at that size. Sizes and
 * weights below still follow the pack's scale.
 *
 * Likewise the pack's shape tokens: only two matter here, the 12dp artwork radius that screen 14
 * (lock screen) uses for its 64dp cover, and the full circle the transport buttons have always been.
 */
object NeedlerWearColours {

    /** App background. `#0d120a`. */
    val canvas: Color = Color(0xFF0D120A)

    /** Pressed and selected rows; here, the artwork placeholder and the secondary button fill. `#1f271b`. */
    val surfaceRaised: Color = Color(0xFF1F271B)

    /** Titles and track names. `#f2f5ee`. */
    val textPrimary: Color = Color(0xFFF2F5EE)

    /** Artists and metadata. `#a8b3a0`. */
    val textSecondary: Color = Color(0xFFA8B3A0)

    /**
     * Placeholders and disabled text. `#6f7a68`.
     *
     * `NeedlerColors.textMuted` carries a long note recording that this value fails WCAG AA on every
     * background the pack uses, and that keeping it is a recorded product decision. That decision was
     * made about a phone held at arm's length. On a watch it is used only for the "connecting" line,
     * which is transient and never the only thing on screen; anything a user must read to act on is
     * [textSecondary] or [textPrimary].
     */
    val textMuted: Color = Color(0xFF6F7A68)

    /** Primary buttons and the transport. `#aed5f2`. */
    val accent: Color = Color(0xFFAED5F2)

    /** Text and icons on [accent] fills. `#071520`. */
    val onAccent: Color = Color(0xFF071520)

    /** Borders and dividers. `rgba(242,245,238,0.08)`. */
    val hairline: Color = Color(0xFFF2F5EE).copy(alpha = 0.08f)

    /** Light band of the vinyl grooves on the record placeholder. `#171e13`. */
    val recordGrooveLight: Color = Color(0xFF171E13)

    /** Dark band of the vinyl grooves. `#101610`. */
    val recordGrooveDark: Color = Color(0xFF101610)
}

/**
 * The watch theme: Wear's own `MaterialTheme`, coloured from [NeedlerWearColours].
 *
 * Note the import - `androidx.wear.compose.material3.MaterialTheme`, not the phone one. The two have
 * the same name and completely different colour schemes, and Wear's is the one built for an OLED
 * screen that is off most of the time.
 *
 * Only six roles are set. The rest of Wear's scheme keeps its defaults, which is safe because this
 * app draws its own surfaces and its own transport rather than leaning on stock components - see
 * `WearTransportIcons.kt` for why the buttons are hand-drawn. The scheme is set at all so that
 * anything stock which does appear, now or later, inherits something close to the pack rather than
 * Wear's default purple.
 *
 * Dark only, and not because a watch prefers it: the design pack has no light theme at all, so there
 * is nothing to switch to. The same rule as `NeedlerTheme`.
 */
@Composable
fun NeedlerWearTheme(content: @Composable () -> Unit) {
    val scheme: ColorScheme = remember {
        ColorScheme(
            primary = NeedlerWearColours.accent,
            onPrimary = NeedlerWearColours.onAccent,
            background = NeedlerWearColours.canvas,
            onBackground = NeedlerWearColours.textPrimary,
            onSurface = NeedlerWearColours.textPrimary,
            onSurfaceVariant = NeedlerWearColours.textSecondary,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        content()
    }
}
