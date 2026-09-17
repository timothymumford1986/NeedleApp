package app.needler.core.design.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
// Aliased: the downloadable-font builder shares its simple name with the platform one above.
import androidx.compose.ui.text.googlefonts.Font as DownloadableFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography for Needler, transcribed from the inline styles in `design/html/*.html`.
 *
 * Two families, both from Google Fonts:
 *
 *  - **Space Grotesk** (500, 700) carries the wordmark, screen titles, numerals and badges.
 *  - **Hanken Grotesk** (400-700) carries all body text.
 *
 * Two rules from the pack are load-bearing rather than decorative:
 *
 *  - The wordmark is uppercase at `0.04em` tracking ([NeedlerTypography.wordmark]).
 *  - Elapsed and remaining times, track durations, percentages and EQ gains use **tabular
 *    numerals** so they do not jitter as digits change. Every numeric style below carries
 *    `fontFeatureSettings = "tnum"`; see [TABULAR_NUMERALS] and [tabularNumerals].
 */
@Immutable
data class NeedlerTypography(

    // ---- Space Grotesk: wordmark ------------------------------------------------------------

    /** `NEEDLER` on the phone Connect screen. 28sp/700, `0.04em`. Render uppercase. */
    val wordmark: TextStyle,
    /** `NEEDLER` in the tablet Connect card. 24sp/700, `0.04em`. Render uppercase. */
    val wordmarkCompact: TextStyle,

    // ---- Space Grotesk: display and titles ---------------------------------------------------

    /** "Point me at your Dropped Needle." on phone Connect. 34sp/700, `-0.01em`, line 1.05. */
    val display: TextStyle,
    /** The same line in the tablet Connect card. 28sp/700, line 1.1. */
    val displayCompact: TextStyle,
    /** LIBRARY / SEARCH / PULLS / SETTINGS. 30sp/700, `0.02em`, line 1.0. Render uppercase. */
    val screenTitle: TextStyle,
    /** EQUALISER / CROSSFADE on a pushed screen. 22sp/700, `0.02em`. Render uppercase. */
    val screenTitleCompact: TextStyle,
    /** "PLAY ON" on the output sheet. 20sp/700, `0.02em`. Render uppercase. */
    val sheetTitle: TextStyle,
    /** Album title on album detail. 22sp/700, `-0.01em`, line 1.15. */
    val albumTitle: TextStyle,
    /** Track title on Now Playing. 26sp/700, line 1.1. */
    val nowPlayingTitle: TextStyle,
    /** Track title in the tablet player sidebar. 22sp/700, line 1.1. */
    val sidebarTitle: TextStyle,

    // ---- Space Grotesk: numerals, headers, badges --------------------------------------------

    /** Large numeral readout, e.g. "4 s" on Crossfade. 28sp/700, tabular. */
    val numeralDisplay: TextStyle,
    /** Initial in an artist avatar. 20sp/700. */
    val avatarInitial: TextStyle,
    /** NOW / EARLIER / IN THE CRATE / SERVER. 12sp/700, `0.14em`. Render uppercase. */
    val sectionHeader: TextStyle,
    /** PREVIEW, inside a card. 12sp/700, `0.1em`. Render uppercase. */
    val overline: TextStyle,
    /** Format badge, e.g. FLAC. 11sp/700, `0.08em`. */
    val badge: TextStyle,
    /** The count inside the Pulls nav badge. 11sp/700, tabular. */
    val counter: TextStyle,

    // ---- Hanken Grotesk: body -----------------------------------------------------------------

    /** Text typed into a field. 17sp/400. */
    val bodyLarge: TextStyle,
    /** List row title, e.g. a track name in the crate. 16sp/600. */
    val rowTitle: TextStyle,
    /** Track name in an album track list. 16sp/500. */
    val rowTitleRegular: TextStyle,
    /** Settings row label. 15sp/500. */
    val body: TextStyle,
    /** Album title in a grid cell or list row. 15sp/600. */
    val bodyStrong: TextStyle,
    /** Header metadata, e.g. "176 albums - 42 GB". 14sp/400. */
    val bodySmall: TextStyle,
    /** SERVER / USERNAME / PASSWORD field labels. 13sp/600, `0.04em`. Render uppercase. */
    val fieldLabel: TextStyle,
    /** Artist and secondary metadata under a title. 13sp/400. */
    val meta: TextStyle,
    /** State badge label, chip and pill label. 13sp/600. */
    val metaStrong: TextStyle,
    /** Legal text and helper copy. 12sp/400, line 1.45. */
    val caption: TextStyle,
    /** Elapsed and remaining time. 12sp/400, tabular. */
    val timecode: TextStyle,
    /** Track duration in a list. 13sp/400, tabular. */
    val duration: TextStyle,
    /** Track number in a list. 14sp/400, tabular, centred. */
    val trackIndex: TextStyle,
    /** Bottom-nav and nav-rail item label. 11sp/600, `0.02em`. */
    val navLabel: TextStyle,
    /** EQ per-band gain readout. 10sp/400, tabular. */
    val micro: TextStyle,
) {
    companion object {
        /**
         * OpenType feature that selects tabular (fixed-advance) figures.
         *
         * Compose exposes font features as the raw CSS-style string on
         * [TextStyle.fontFeatureSettings], so the `FontFeatureSetting("tnum")` the pack relies on
         * is spelled `"tnum"` here.
         */
        const val TABULAR_NUMERALS: String = "tnum"
    }
}

/** Returns a copy of this style with tabular numerals switched on. */
fun TextStyle.tabularNumerals(): TextStyle =
    copy(fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS)

/** The two families Needler draws with, already resolved to a [FontFamily]. */
@Immutable
data class NeedlerFontFamilies(
    /** Space Grotesk, weights 500 and 700, with a system fallback. */
    val display: FontFamily,
    /** Hanken Grotesk, weights 400-700, with a system fallback. */
    val body: FontFamily,
)

private const val GOOGLE_FONTS_AUTHORITY = "com.google.android.gms.fonts"
private const val GOOGLE_FONTS_PACKAGE = "com.google.android.gms"
private const val GOOGLE_FONTS_CERTS = "com_google_android_gms_fonts_certs"

/**
 * Resolves the Google Fonts provider certificate array by name at runtime.
 *
 * The array is contributed either by `androidx.compose.ui:ui-text-google-fonts` or by a
 * `res/values/font_certs.xml` in the application module. Resolving it by name rather than through a
 * generated `R` reference keeps this module compiling whichever of those supplies it, and lets us
 * degrade gracefully when neither does.
 *
 * @return the array's resource id, or `0` when it is absent.
 */
private fun googleFontsCertificates(context: Context): Int =
    runCatching {
        context.resources.getIdentifier(GOOGLE_FONTS_CERTS, "array", context.packageName)
    }.getOrDefault(0)

/**
 * The system fallback stack.
 *
 * Downloadable fonts can fail - no Play Services, no network on first run, a provider the OEM
 * stripped - so every family ends in device fonts that are always present. `sans-serif-medium`
 * stands in for the 500 and 600 weights, `sans-serif` for 400 and 700.
 */
private fun systemFallback(): List<Font> = listOf(
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold),
)

/**
 * Builds the two families, wiring them to Google Fonts when the provider certificates are present
 * and falling back to [systemFallback] when they are not.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberNeedlerFontFamilies(): NeedlerFontFamilies {
    val context = LocalContext.current
    return remember(context) {
        val certificates = googleFontsCertificates(context)
        if (certificates == 0) {
            val fallback = FontFamily(systemFallback())
            return@remember NeedlerFontFamilies(display = fallback, body = fallback)
        }
        val provider = GoogleFont.Provider(
            providerAuthority = GOOGLE_FONTS_AUTHORITY,
            providerPackage = GOOGLE_FONTS_PACKAGE,
            certificates = certificates,
        )
        val spaceGrotesk = GoogleFont("Space Grotesk")
        val hankenGrotesk = GoogleFont("Hanken Grotesk")
        NeedlerFontFamilies(
            display = FontFamily(
                DownloadableFont(googleFont = spaceGrotesk, fontProvider = provider, weight = FontWeight.Medium),
                DownloadableFont(googleFont = spaceGrotesk, fontProvider = provider, weight = FontWeight.Bold),
                *systemFallback().toTypedArray(),
            ),
            body = FontFamily(
                DownloadableFont(googleFont = hankenGrotesk, fontProvider = provider, weight = FontWeight.Normal),
                DownloadableFont(googleFont = hankenGrotesk, fontProvider = provider, weight = FontWeight.Medium),
                DownloadableFont(googleFont = hankenGrotesk, fontProvider = provider, weight = FontWeight.SemiBold),
                DownloadableFont(googleFont = hankenGrotesk, fontProvider = provider, weight = FontWeight.Bold),
                *systemFallback().toTypedArray(),
            ),
        )
    }
}

/**
 * Assembles [NeedlerTypography] over a pair of families.
 *
 * Sizes, weights, tracking and line heights are the values in the pack. Line heights the HTML gives
 * as a ratio are multiplied out; where the HTML sets none, the style leaves it unspecified so text
 * keeps scaling with the user's font-size preference.
 */
fun needlerTypography(families: NeedlerFontFamilies): NeedlerTypography {
    val display = families.display
    val body = families.body
    return NeedlerTypography(
        wordmark = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = 0.04.em,
        ),
        wordmarkCompact = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 0.04.em,
        ),
        display = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.01).em,
        ),
        displayCompact = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            lineHeight = 31.sp,
        ),
        screenTitle = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 30.sp,
            letterSpacing = 0.02.em,
        ),
        screenTitleCompact = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            letterSpacing = 0.02.em,
        ),
        sheetTitle = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 0.02.em,
        ),
        albumTitle = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 25.sp,
            letterSpacing = (-0.01).em,
        ),
        nowPlayingTitle = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            lineHeight = 29.sp,
        ),
        sidebarTitle = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 24.sp,
        ),
        numeralDisplay = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
        avatarInitial = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        ),
        sectionHeader = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.14.em,
        ),
        overline = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.1.em,
        ),
        badge = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.08.em,
        ),
        counter = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
        bodyLarge = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 17.sp,
        ),
        rowTitle = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        ),
        rowTitleRegular = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        ),
        body = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
        ),
        bodyStrong = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        ),
        fieldLabel = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.04.em,
        ),
        meta = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
        ),
        metaStrong = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        ),
        caption = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        timecode = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
        duration = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
        trackIndex = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
        navLabel = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.02.em,
        ),
        micro = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 10.sp,
            fontFeatureSettings = NeedlerTypography.TABULAR_NUMERALS,
        ),
    )
}

internal val LocalNeedlerTypography = staticCompositionLocalOf {
    needlerTypography(
        NeedlerFontFamilies(display = FontFamily.SansSerif, body = FontFamily.SansSerif),
    )
}
