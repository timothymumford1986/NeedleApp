package app.needler.core.design.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii, transcribed from `border-radius` in the pack.
 *
 * REQUIREMENTS.md summarises these as "10 px for small chips, 14 px for inputs and cards, 16 to
 * 18 px for buttons and sheets, 999 px for pills and 2 px for progress bars". The HTML agrees, and
 * adds a few artwork-specific radii that are listed separately below.
 */
@Immutable
data class NeedlerShapes(
    /** Small chips and list thumbnails. 10dp. */
    val small: RoundedCornerShape = RoundedCornerShape(10.dp),
    /** Text fields, cards and medium buttons. 14dp. */
    val medium: RoundedCornerShape = RoundedCornerShape(14.dp),
    /** Primary buttons, search fields, the mini-player. 16dp. */
    val large: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** Nav-rail items and larger cards. 18dp. */
    val extraLarge: RoundedCornerShape = RoundedCornerShape(18.dp),
    /** Pills, segmented tabs, badges, circular controls. Effectively 999dp. */
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
    /** Progress bars and scrubber tracks. 2dp. */
    val progress: RoundedCornerShape = RoundedCornerShape(2.dp),
    /** A bottom sheet, rounded on its top edge only. 28dp. */
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    /** A free-standing card: the tablet Connect card. 28dp. */
    val card: RoundedCornerShape = RoundedCornerShape(28.dp),
    /** A home-screen widget card. 22dp. */
    val widget: RoundedCornerShape = RoundedCornerShape(22.dp),
    /** Thumbnail artwork in a row (48-56dp). 10dp. */
    val artworkThumb: RoundedCornerShape = RoundedCornerShape(10.dp),
    /** Lock-screen artwork (64dp). 12dp. */
    val artworkCompact: RoundedCornerShape = RoundedCornerShape(12.dp),
    /** Grid-cell artwork (160-163dp). 14dp. */
    val artworkGrid: RoundedCornerShape = RoundedCornerShape(14.dp),
    /** Album-detail artwork (120dp). 16dp. */
    val artworkDetail: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** Now-playing artwork (270dp) on phone. 20dp. */
    val artworkHero: RoundedCornerShape = RoundedCornerShape(20.dp),
    /** Now-playing artwork (250dp) in the tablet sidebar. 18dp. */
    val artworkHeroTablet: RoundedCornerShape = RoundedCornerShape(18.dp),
    /** The FLAC / MP3 320 format badge. 6dp. */
    val formatBadge: RoundedCornerShape = RoundedCornerShape(6.dp),
    /** Anything fully round: transport buttons, the on-device check, the record. */
    val circle: Shape = CircleShape,
)

/**
 * Spacing scale, derived from the gaps the pack actually uses.
 *
 * Counting every `gap:` in the 21 screens gives a 2dp-stepped scale up to 24dp and then 28, 32, 36,
 * 40. `step1` through `step20` are that scale, in dp; the named values below are the recurring
 * layout measurements, which are easier to read at a call site than a step number.
 */
@Immutable
data class NeedlerSpacing(
    val none: Dp = 0.dp,
    /** 2dp - title to subtitle inside a row. The single most common gap in the pack. */
    val step1: Dp = 2.dp,
    /** 4dp - icon to label in a nav item. */
    val step2: Dp = 4.dp,
    /** 6dp - section header to first row; badge icon to badge label. */
    val step3: Dp = 6.dp,
    /** 8dp - between buttons in a row; label to field. */
    val step4: Dp = 8.dp,
    /** 10dp - artwork to caption in a grid cell; wordmark glyph to word. */
    val step5: Dp = 10.dp,
    /** 12dp - search icon to input; nav-rail items. */
    val step6: Dp = 12.dp,
    /** 14dp - artwork to text in a list row. */
    val step7: Dp = 14.dp,
    /** 16dp - track index to title; card padding. */
    val step8: Dp = 16.dp,
    /** 18dp - between fields on Connect; header to content. */
    val step9: Dp = 18.dp,
    /** 20dp - phone grid row gap. */
    val step10: Dp = 20.dp,
    /** 22dp - between blocks on album detail. */
    val step11: Dp = 22.dp,
    /** 24dp - between blocks on Now Playing and the tablet sidebar. */
    val step12: Dp = 24.dp,
    /** 28dp - between sections on a settings-style screen. */
    val step14: Dp = 28.dp,
    /** 32dp - between the header block and the form on Connect. */
    val step16: Dp = 32.dp,
    /** 36dp. */
    val step18: Dp = 36.dp,
    /** 40dp. */
    val step20: Dp = 40.dp,

    /** Phone content inset: 20dp on list screens. */
    val phoneGutter: Dp = 20.dp,
    /** Phone content inset: 24dp on Connect, album detail and Settings. */
    val phoneGutterWide: Dp = 24.dp,
    /** Tablet content inset. 40dp. */
    val tabletGutter: Dp = 40.dp,
    /** Tablet sidebar inset. 32dp. */
    val tabletSidebarGutter: Dp = 32.dp,
    /** Gap between sections on a scrolling screen. 18dp. */
    val sectionGap: Dp = 18.dp,
    /** Gap between a section header and its first row. 6dp. */
    val sectionHeaderGap: Dp = 6.dp,
    /** Phone album grid: 20dp between rows, 16dp between columns. */
    val gridRowGapPhone: Dp = 20.dp,
    val gridColumnGapPhone: Dp = 16.dp,
    /** Tablet album grid: 24dp between rows, 20dp between columns. */
    val gridRowGapTablet: Dp = 24.dp,
    val gridColumnGapTablet: Dp = 20.dp,
)

/**
 * Control metrics from the pack.
 *
 * REQUIREMENTS.md notes that "the fixed 54 to 56 px control heights in the design pack will need
 * care to honour" 200% text scaling. These values are therefore **minimums**: components apply them
 * with `Modifier.defaultMinSize`, never `Modifier.height`, so a row grows when the text inside it
 * does. The only fixed sizes are genuinely graphical: artwork, the record, badge dots.
 */
@Immutable
data class NeedlerSizes(
    /** Connect text field. 54dp on phone, 52dp on tablet. */
    val textFieldMinHeight: Dp = 54.dp,
    /** Search field. 52dp. */
    val searchFieldMinHeight: Dp = 52.dp,
    /** Connect's Connect button. 56dp. */
    val primaryButtonMinHeight: Dp = 56.dp,
    /** Play / Shuffle / Local on album detail; Pull this album. 52dp. */
    val buttonMinHeight: Dp = 52.dp,
    /** Pills: Clear done, Retry, EQ presets, sort. 36dp. */
    val pillMinHeight: Dp = 36.dp,
    /** Small pill: Play on a finished pull, the output selector. 32dp. */
    val pillSmallMinHeight: Dp = 32.dp,
    /** Pull action pill in a search result. 40dp. */
    val pullButtonMinHeight: Dp = 40.dp,
    /** Settings row, toggle row. 48dp. */
    val listRowMinHeight: Dp = 48.dp,
    /** Track row on album detail. 52dp. */
    val trackRowMinHeight: Dp = 52.dp,
    /** Queue / crate row. 64dp. */
    val queueRowMinHeight: Dp = 64.dp,
    /** Album row in search results. 72dp. */
    val albumRowMinHeight: Dp = 72.dp,
    /** Album row in the library list view. 76dp. */
    val albumListRowMinHeight: Dp = 76.dp,
    /** Output picker row. 60dp. */
    val outputRowMinHeight: Dp = 60.dp,
    /** Segmented tab control, outer. 36dp. */
    val segmentedHeight: Dp = 36.dp,
    /** Segmented tab control, inner pill. 28dp. */
    val segmentedItemHeight: Dp = 28.dp,
    /** Padding inside the segmented control that reveals the track. 3dp. */
    val segmentedPadding: Dp = 3.dp,

    /**
     * The smallest a control may be for touch, per REQUIREMENTS.md's accessibility section:
     * "Every control carries a content description and transport controls are at least 48 dp."
     *
     * Where the pack draws something smaller - the 32dp play affordance on a grid cell, the 36dp
     * more-actions button on a track row - the visual size is kept and the touch target is expanded
     * to this.
     */
    val minTouchTarget: Dp = 48.dp,

    /** Secondary transport button (shuffle, queue). 44-48dp. */
    val transportSmall: Dp = 48.dp,
    /** Previous / next. 56dp on Now Playing, 48dp in the tablet sidebar. */
    val transportMedium: Dp = 56.dp,
    /** Play / pause on Now Playing. 80dp. */
    val playButtonLarge: Dp = 80.dp,
    /** Play / pause in the tablet sidebar. 68dp. */
    val playButtonMedium: Dp = 68.dp,
    /** Play / pause in the mini-player and on the lock screen. 44dp visual. */
    val playButtonSmall: Dp = 44.dp,

    /** Track thickness of a progress bar or scrubber. 4dp. */
    val progressTrackThickness: Dp = 4.dp,
    /** Scrubber thumb on Now Playing. 16dp. */
    val scrubberThumb: Dp = 16.dp,
    /** Scrubber thumb in the tablet sidebar. 14dp. */
    val scrubberThumbSmall: Dp = 14.dp,
    /** Progress ring over artwork. 34dp across, 4dp stroke. */
    val progressRing: Dp = 34.dp,
    val progressRingStroke: Dp = 4.dp,

    /** Artwork thumbnail in a crate row. 48dp. */
    val artworkThumb: Dp = 48.dp,
    /** Artwork thumbnail in a library list row. 52dp. */
    val artworkThumbLarge: Dp = 52.dp,
    /** Artwork thumbnail in a search or pull row. 56dp. */
    val artworkRow: Dp = 56.dp,
    /** Artwork on album detail. 120dp. */
    val artworkDetail: Dp = 120.dp,

    /** The on-device check badge on a grid cell. 22dp. */
    val onDeviceBadge: Dp = 22.dp,
    /** The play affordance on a grid cell. 32dp visual. */
    val gridPlayAffordance: Dp = 32.dp,
    /** Nav badge: 18dp tall, at least 18dp wide. */
    val counterBadge: Dp = 18.dp,
    /** The switch in a toggle row: 48x28dp with a 24dp thumb. */
    val switchWidth: Dp = 48.dp,
    val switchHeight: Dp = 28.dp,
    val switchThumb: Dp = 24.dp,
    /** Hairline border and divider thickness. 1dp. */
    val hairlineThickness: Dp = 1.dp,
    /** Bottom nav: 84dp tall with 56dp items and a 24dp bottom inset. */
    val navBarHeight: Dp = 84.dp,
    val navItemMinHeight: Dp = 56.dp,
    /** Tablet nav rail: 96dp wide with 64dp items. */
    val navRailWidth: Dp = 96.dp,
    val navRailItemSize: Dp = 64.dp,
    /** Mini-player. 64dp. */
    val miniPlayerMinHeight: Dp = 64.dp,
    /** Tablet player sidebar. 400dp. */
    val sidebarWidth: Dp = 400.dp,
)

internal val LocalNeedlerShapes = staticCompositionLocalOf { NeedlerShapes() }
internal val LocalNeedlerSpacing = staticCompositionLocalOf { NeedlerSpacing() }
internal val LocalNeedlerSizes = staticCompositionLocalOf { NeedlerSizes() }
