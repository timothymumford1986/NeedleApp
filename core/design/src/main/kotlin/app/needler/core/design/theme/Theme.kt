package app.needler.core.design.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.foundation.LocalIndication
import app.needler.core.design.motion.LocalReducedMotion
import app.needler.core.design.motion.needlerPressIndication
import app.needler.core.design.motion.rememberSystemReducedMotion

/**
 * The Needler theme.
 *
 * Installs the palette, typography, shapes, spacing, control metrics and the reduced-motion signal,
 * and configures Material 3 from the same tokens so standard Material components inherit the design
 * rather than fighting it.
 *
 * Dark only. The design pack has no light theme, so there is no `darkTheme` parameter to get wrong
 * and no dynamic colour: an olive-and-pale-blue record player does not want the wallpaper's opinion.
 *
 * ## Why the press indication is installed here and not in each component
 *
 * REQUIREMENTS.md "Motion" asks that animation be suppressed when the system animator duration scale
 * is zero, and press feedback was the one animation in the product that never asked. `MaterialTheme`
 * provides `LocalIndication` as Material 3's ripple, and an interaction modifier that is handed no
 * indication of its own resolves that one - so the three dozen hand-written `clickable` modifiers on
 * list rows, scrims and glyphs were all rippling on Material's clock. One substitution at the theme
 * reaches every one of them; a substitution per component would have reached the five that share
 * `needlerPressSurface` and missed the rest.
 *
 * It is provided **inside** [MaterialTheme] rather than beside the Needler locals above, because
 * `MaterialTheme` provides `LocalIndication` itself and would otherwise overwrite this on the way in.
 * The order is load-bearing, which is why it is stated. [needlerPressIndication] reads the ripple back
 * out of that same local, so under normal motion nothing is substituted at all.
 *
 * @param reducedMotion whether to suppress animation. Defaults to the platform setting via
 *   [rememberSystemReducedMotion]; pass it explicitly in tests and screenshot harnesses. It is also
 *   what decides the press indication above.
 */
@Composable
fun NeedlerTheme(
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors = NeedlerDarkColors
    val families = rememberNeedlerFontFamilies()
    val typography = remember(families) { needlerTypography(families) }
    val shapes = remember { NeedlerShapes() }
    val spacing = remember { NeedlerSpacing() }
    val sizes = remember { NeedlerSizes() }

    val materialColors = remember(colors) { colors.toMaterialColorScheme() }
    val materialTypography = remember(typography) { typography.toMaterialTypography() }
    val materialShapes = remember(shapes) { shapes.toMaterialShapes() }

    CompositionLocalProvider(
        LocalNeedlerColors provides colors,
        LocalNeedlerTypography provides typography,
        LocalNeedlerShapes provides shapes,
        LocalNeedlerSpacing provides spacing,
        LocalNeedlerSizes provides sizes,
        LocalReducedMotion provides reducedMotion,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = materialTypography,
            shapes = materialShapes,
        ) {
            CompositionLocalProvider(
                LocalIndication provides needlerPressIndication(),
                content = content,
            )
        }
    }
}

/** Access to Needler's tokens from inside [NeedlerTheme]. */
object NeedlerTheme {
    val colors: NeedlerColors
        @Composable @ReadOnlyComposable get() = LocalNeedlerColors.current

    val typography: NeedlerTypography
        @Composable @ReadOnlyComposable get() = LocalNeedlerTypography.current

    val shapes: NeedlerShapes
        @Composable @ReadOnlyComposable get() = LocalNeedlerShapes.current

    val spacing: NeedlerSpacing
        @Composable @ReadOnlyComposable get() = LocalNeedlerSpacing.current

    val sizes: NeedlerSizes
        @Composable @ReadOnlyComposable get() = LocalNeedlerSizes.current

    /** Shorthand for [LocalReducedMotion], so a screen can branch without a second import. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/**
 * Maps Needler's roles onto Material 3's.
 *
 * `primary` is the accent, `secondary`/`tertiary` the positive green, and the three surface levels
 * map to `background`, `surface` and `surfaceVariant`.
 *
 * The pack draws no error colour - "Remove all from device" was accent-coloured, identical to
 * "Connect" - so [NeedlerColors.destructive] was added as a product decision and `error` maps to
 * it. See that property for the value and its measured contrast.
 *
 * `outline` is [NeedlerColors.componentBorder] and `outlineVariant` is [NeedlerColors.hairline],
 * which is the WCAG 1.4.11 split those two properties already describe: Material draws `outline`
 * round controls, where 3:1 is required, and `outlineVariant` as dividers, where nothing is. The
 * mapping used to send `outline` to [NeedlerColors.textMuted], which cleared 3:1 by accident
 * because it is a text colour.
 */
internal fun NeedlerColors.toMaterialColorScheme() = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accent,
    onPrimaryContainer = onAccent,
    inversePrimary = accentHover,
    secondary = positive,
    onSecondary = onPositive,
    secondaryContainer = surfaceRaised,
    onSecondaryContainer = textPrimary,
    tertiary = positive,
    onTertiary = onPositive,
    tertiaryContainer = surfaceRaised,
    onTertiaryContainer = textPrimary,
    background = canvas,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textSecondary,
    surfaceTint = accent,
    inverseSurface = inverseSurface,
    inverseOnSurface = onInverseSurface,
    error = destructive,
    onError = onDestructive,
    errorContainer = surfaceRaised,
    onErrorContainer = destructive,
    outline = componentBorder,
    outlineVariant = hairline,
    scrim = artworkShadow,
)

/** Maps Needler's named styles onto Material 3's scale, so Material components pick up the pack. */
internal fun NeedlerTypography.toMaterialTypography() = Typography(
    displayLarge = display,
    displayMedium = displayCompact,
    displaySmall = nowPlayingTitle,
    headlineLarge = screenTitle,
    headlineMedium = screenTitleCompact,
    headlineSmall = albumTitle,
    titleLarge = sheetTitle,
    titleMedium = rowTitle,
    titleSmall = bodyStrong,
    bodyLarge = bodyLarge,
    bodyMedium = body,
    bodySmall = meta,
    labelLarge = metaStrong,
    labelMedium = fieldLabel,
    labelSmall = navLabel,
)

/** Maps Needler's radii onto Material 3's five shape slots. */
internal fun NeedlerShapes.toMaterialShapes() = Shapes(
    extraSmall = progress,
    small = small,
    medium = medium,
    large = large,
    extraLarge = extraLarge,
)
