package app.needler.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Needler's palette, transcribed from `design/html`.
 *
 * The design pack ships a single dark theme built on a near-black olive canvas with one pale-blue
 * accent and one pale-green positive state. There is deliberately **no light theme** - do not add
 * one without a design pack that draws it.
 *
 * Every colour is named by the role it plays, never by its hue, so a repaint stays a one-file
 * change. Values in the first block come from REQUIREMENTS.md's palette table and were verified
 * against the inline styles in the pack; values in the later blocks were found only in the HTML and
 * are named here for the first time.
 */
@Immutable
data class NeedlerColors(

    // ---- Core roles (REQUIREMENTS.md "Design system" table) ---------------------------------

    /** App background. `#0d120a`. */
    val canvas: Color = Color(0xFF0D120A),
    /** Cards, inputs, sheets, nav bars and the tablet rail/sidebar. `#161d12`. */
    val surface: Color = Color(0xFF161D12),
    /** Pressed and selected rows, progress tracks, the mini-player. `#1f271b`. */
    val surfaceRaised: Color = Color(0xFF1F271B),
    /** Titles and track names. `#f2f5ee`. */
    val textPrimary: Color = Color(0xFFF2F5EE),
    /** Artists and metadata. `#a8b3a0`. */
    val textSecondary: Color = Color(0xFFA8B3A0),
    /**
     * Placeholders, timecodes, disabled text and inactive nav items. `#6f7a68`.
     *
     * This is the design value and is kept exactly as drawn. It measures **4.21:1** on [canvas],
     * 3.82:1 on [surface] and 3.42:1 on [surfaceRaised], so it fails WCAG AA (4.5:1) for normal
     * text everywhere the pack uses it.
     *
     * This failure is a deliberate, recorded product decision: the drawn value was chosen over a
     * lighter same-hue alternative (`#828f7a`, which measures 5.56:1 on [canvas] and clears AA on
     * every background the pack uses). Revisit here if that call is ever reversed.
     */
    val textMuted: Color = Color(0xFF6F7A68),
    /** Primary buttons, links, transport and the active nav item. `#aed5f2`. */
    val accent: Color = Color(0xFFAED5F2),
    /** Text and icons on [accent] fills. `#071520`. */
    val onAccent: Color = Color(0xFF071520),
    /** Progress, Ready, the on-device check and the FLAC badge. `#bbdb9b`. */
    val positive: Color = Color(0xFFBBDB9B),
    /** Text and icons on [positive] fills. `#0f1a0a`. */
    val onPositive: Color = Color(0xFF0F1A0A),
    /** Borders, dividers and row separators. `rgba(242,245,238,0.08)`. */
    val hairline: Color = Color(0xFFF2F5EE).copy(alpha = 0.08f),

    // ---- Accent states (from every screen's `a:hover` rule) ---------------------------------

    /** Hovered or focused accent, from `a:hover { color: #d3e8f8 }`. */
    val accentHover: Color = Color(0xFFD3E8F8),
    /** Pressed accent. The pack draws no separate pressed value, so it reuses the hover tint. */
    val accentPressed: Color = Color(0xFFD3E8F8),

    // ---- Inverse surface (selected segment / selected preset chip) --------------------------

    /** Fill of the selected segmented-tab pill and the selected EQ preset chip. `#f2f5ee`. */
    val inverseSurface: Color = Color(0xFFF2F5EE),
    /** Label on [inverseSurface]. `#0d120a`. */
    val onInverseSurface: Color = Color(0xFF0D120A),

    // ---- Artwork overlays (album grid cells, pull rows) -------------------------------------

    /**
     * Placeholder behind album art while it loads. The pack tints each cell from the artwork
     * itself (`#5a1f22`, `#2d3d24`, ...); those per-album tints are content, not tokens.
     */
    val artworkPlaceholder: Color = Color(0xFF1F271B),
    /** Fill of the play affordance drawn over artwork. `rgba(13,18,10,0.35)`. */
    val artworkScrim: Color = Color(0xFF0D120A).copy(alpha = 0.35f),
    /** Heavier scrim under a pull's progress ring. `rgba(13,18,10,0.62)`. */
    val artworkScrimStrong: Color = Color(0xFF0D120A).copy(alpha = 0.62f),
    /** 1.5dp outline of the play affordance. `rgba(242,245,238,0.85)`. */
    val artworkOutline: Color = Color(0xFFF2F5EE).copy(alpha = 0.85f),
    /** Drop shadow under the large now-playing artwork. `rgba(0,0,0,0.5)`. */
    val artworkShadow: Color = Color(0xFF000000).copy(alpha = 0.5f),

    // ---- Progress ---------------------------------------------------------------------------

    /** Unfilled part of a bar or scrubber on an opaque background. `#1f271b`. */
    val progressTrack: Color = Color(0xFF1F271B),
    /** Unfilled part of the ring drawn on top of artwork. `rgba(242,245,238,0.18)`. */
    val progressTrackOnArtwork: Color = Color(0xFFF2F5EE).copy(alpha = 0.18f),

    // ---- Sheets, widgets, grabbers -----------------------------------------------------------

    /** Translucent widget and lock-screen card. `rgba(22,29,18,0.92)`. */
    val surfaceTranslucent: Color = Color(0xFF161D12).copy(alpha = 0.92f),
    /** The tablet Connect card, which lets the record show through. `rgba(22,29,18,0.8)`. */
    val surfaceTranslucentSoft: Color = Color(0xFF161D12).copy(alpha = 0.8f),
    /** Grabber on a card floating over content. `rgba(242,245,238,0.35)`. */
    val grabberOnArtwork: Color = Color(0xFFF2F5EE).copy(alpha = 0.35f),
    /** Grabber on an opaque sheet, e.g. the output picker. `#1f271b`. */
    val grabberOnSheet: Color = Color(0xFF1F271B),

    // ---- The record mark ---------------------------------------------------------------------

    /** Light band of the vinyl grooves. `#171e13`. */
    val recordGrooveLight: Color = Color(0xFF171E13),
    /** Dark band of the vinyl grooves. `#101610`. */
    val recordGrooveDark: Color = Color(0xFF101610),
    /** Brighter lobe of the rotating sheen. `rgba(242,245,238,0.07)`. */
    val recordSheenBright: Color = Color(0xFFF2F5EE).copy(alpha = 0.07f),
    /** Dimmer lobe of the rotating sheen. `rgba(242,245,238,0.05)`. */
    val recordSheenDim: Color = Color(0xFFF2F5EE).copy(alpha = 0.05f),
    /** 1dp rim around the disc. `rgba(242,245,238,0.06)`. */
    val recordEdge: Color = Color(0xFFF2F5EE).copy(alpha = 0.06f),
    /** The three groove rings on the splash record. `rgba(242,245,238,0.16)`. */
    val recordRing: Color = Color(0xFFF2F5EE).copy(alpha = 0.16f),

    // ---- Backdrop -----------------------------------------------------------------------------

    /**
     * Centre of the radial backdrop on the immersive screens (14, 16, 17):
     * `radial-gradient(ellipse at 50% 30%, #1a2415 0%, #0d120a 60%)`.
     */
    val backdropGlow: Color = Color(0xFF1A2415),

    // ---- Documented accessible alternative ----------------------------------------------------

    /**
     * Destructive actions: "Remove all from device", unpinning, sign-out, delete confirmations.
     *
     * The pack draws no destructive colour, so "Remove all from device" on screen 12 rendered in
     * the same accent blue as "Connect" and "Play" - a permanent data-loss action styled exactly
     * like the primary action. This value was added on that basis.
     *
     * It is a pale, desaturated red chosen to sit in the same family as the pack's other two
     * signal colours ([accent] pale blue, [positive] pale green) rather than a saturated warning
     * red, which would be louder than anything else in the design. Measured **7.93:1** on
     * [canvas], 7.21:1 on [surface] and 6.44:1 on [surfaceRaised] - AA on all three.
     */
    val destructive: Color = Color(0xFFE8908A),

    /** Text and icons on a [destructive] fill. Measured 7.93:1. */
    val onDestructive: Color = Color(0xFF200C0A),
)

/** The one palette in the pack. Dark only. */
val NeedlerDarkColors: NeedlerColors = NeedlerColors()

internal val LocalNeedlerColors = staticCompositionLocalOf { NeedlerDarkColors }
