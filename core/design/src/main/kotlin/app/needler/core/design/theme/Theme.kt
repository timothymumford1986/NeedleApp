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
import app.needler.core.design.motion.LocalReducedMotion
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
 * @param reducedMotion whether to suppress animation. Defaults to the platform setting via
 *   [rememberSystemReducedMotion]; pass it explicitly in tests and screenshot harnesses.
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
            content = content,
        )
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
 * The pack draws **no error colour** - even "Remove all from device" is accent-coloured - so
 * `error` is mapped to the accent rather than inventing a red. A feature that genuinely needs a
 * destructive colour needs a design decision, not a guess here.
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
    error = accent,
    onError = onAccent,
    errorContainer = surfaceRaised,
    onErrorContainer = accent,
    outline = textMuted,
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
