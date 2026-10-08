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
 *
 * Four values are **not** the pack's, and each says so where it is declared: [componentBorder] and
 * [skeleton] split a role off a pack token that measured 1.20:1 and 1.10:1 respectively, [textMuted]
 * reverses a recorded decision to keep a value below WCAG AA, and [destructive] / [onDestructive]
 * fill a gap the pack left. Every one of them carries its measured ratios in its own KDoc, and
 * `StateBadgeLabelTest` asserts those ratios as numbers so a repaint fails there rather than on a
 * device.
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
     * Placeholders, timecodes, disabled text and inactive nav items. `#828f7a`.
     *
     * ## The decision recorded here has been reversed
     *
     * This was `#6f7a68`, the pack's drawn value, kept deliberately although it measured **4.21:1**
     * on [canvas], 3.82:1 on [surface] and 3.42:1 on [surfaceRaised] - below WCAG AA's 4.5:1 for
     * normal text on every background the pack uses it on. REQUIREMENTS.md "Accessibility" recorded
     * that as "a recorded product decision, not an outstanding risk", and wrote down the costed
     * alternative "so that reversing the decision later is a one-line change rather than a
     * re-investigation". This is that line.
     *
     * The reversal is the alternative exactly as it was costed: `#828f7a`, the same hue a step
     * lighter, measuring **5.56:1** on [canvas], 5.05:1 on [surface] and 4.51:1 on [surfaceRaised].
     * It clears AA on all three, including the raised surface, which is the background that decided
     * the value - 4.51:1 is 0.01 of margin and there is nothing dimmer in this hue that clears it.
     *
     * What moved the call was the breadth of the role rather than any one screen. This colour is
     * the product's whole third tier of body copy - timecodes a listener reads while a track plays,
     * the subtitle under a settings row, the explanation under an empty state - and a tier of prose
     * that fails AA is not a styling choice, it is text some readers cannot read. The pack's own
     * argument for the darker value was that it sits quietly; at 5.56:1 against a near-black canvas
     * it still does, and `textSecondary` above it is at 8.68:1, so the three tiers remain three.
     *
     * ## The rejected alternative
     *
     * Keep `#6f7a68` and lift only the places a reviewer measured. That is how a palette acquires
     * a fourth grey that means "the legible one", and the next surface to need tertiary copy picks
     * whichever of the two it copied from.
     */
    val textMuted: Color = Color(0xFF828F7A),

    /**
     * An inactive control: a disabled button's label, an unavailable transport mode. `#6f7a68`.
     *
     * ## Why this is a role and not a fourth grey
     *
     * [textMuted]'s own first line used to list four jobs, and one of them was not a text tier.
     * Three of them - placeholders, timecodes, inactive nav items - are *prose a reader must read*,
     * which is why that token moved to `#828f7a` to clear AA. The fourth, a disabled control, has
     * the opposite requirement: it must read as inoperable, and the way it does that is by being
     * dimmer than the live state beside it.
     *
     * Raising the one token served the prose and broke the control. Measured, on the transport's
     * shuffle and repeat - the three-appearance control `TransportRowTest` exists for:
     *
     * | Pair | At `#6f7a68` | At `#828f7a` |
     * |---|---|---|
     * | off ([textSecondary]) vs unavailable | **2.06:1** | 1.56:1 |
     *
     * The device audit that produced that test found off and unavailable drawn in *one* colour, so
     * "a live control looked inoperable". Moving the token recovered a quarter of the separation the
     * fix had won. The same narrowing applies to every disabled button label in the product.
     *
     * ## Why the dim value is correct here and not for prose
     *
     * WCAG 2.2 exempts inactive components explicitly: 1.4.3 does not apply to "text or images of
     * text that are part of an inactive user interface component", and 1.4.11 carves out the same
     * for non-text contrast. A disabled control is the one place in this palette where the dim value
     * is not a compromise - it is the point. `#6f7a68` measures 4.03:1 on [canvas], which clears the
     * 3:1 of 1.4.11 anyway, and the state is carried to TalkBack by the component's own `enabled`
     * flag rather than by the colour.
     *
     * So this is the pack's drawn value kept for the role that wanted it, while the role that could
     * not use it moved. The split is the same one [componentBorder] and [skeleton] make in this
     * file: one token was doing two jobs with incompatible rules.
     */
    val disabled: Color = Color(0xFF6F7A68),
    /** Primary buttons, links, transport and the active nav item. `#aed5f2`. */
    val accent: Color = Color(0xFFAED5F2),
    /** Text and icons on [accent] fills. `#071520`. */
    val onAccent: Color = Color(0xFF071520),
    /** Progress, Ready, the on-device check and the FLAC badge. `#bbdb9b`. */
    val positive: Color = Color(0xFFBBDB9B),
    /** Text and icons on [positive] fills. `#0f1a0a`. */
    val onPositive: Color = Color(0xFF0F1A0A),
    /**
     * Dividers and row separators. `rgba(242,245,238,0.08)`.
     *
     * **Separators only. A control's own boundary is [componentBorder].** This used to be both, and
     * that is the defect being split apart here: composited over [canvas] it resolves to
     * `rgb(31,36,28)` and measures **1.20:1**, which REQUIREMENTS.md "Accessibility" records and
     * carries "as an open question rather than quietly patched". As a rule between two rows that is
     * fine - WCAG 1.4.11 is about the information needed to identify a component, and a list
     * separator carries none. As the only edge of a button it is not fine, and
     * [componentBorder] is why.
     */
    val hairline: Color = Color(0xFFF2F5EE).copy(alpha = 0.08f),

    // ---- Measured repairs (not pack values; see "Documented accessible alternative" below) ----
    //
    // Two roles the pack drew with a value that cannot carry them, each split out so the pack's own
    // token keeps the job it is good at. Both are stated as ratios because REQUIREMENTS.md
    // "Accessibility" asks for measurements rather than assurances.

    /**
     * The visible boundary of an outlined control: buttons, pills, inputs, the segmented track.
     * `#6e7867`.
     *
     * ## What this fixes
     *
     * Every outlined control in the product drew its edge in [hairline], which composites to
     * `rgb(31,36,28)` on [canvas] and measures **1.20:1** - against the 3:1 WCAG 1.4.11 asks of
     * "user interface components and their boundaries". A reviewer's pixel probe put the drawn
     * border at `rgb(30,35,28)` and 1.18:1, which is the same line after PNG rounding. So `Stop`,
     * `Retry`, `Clear done`, `Refresh` and `Try again` were pills with no visible pill, beside a
     * filled [accent] `Play` that reads as a button instantly. The controls carrying the
     * consequences were the invisible ones.
     *
     * ## Why a token of its own rather than a lighter [hairline]
     *
     * Because the two have different jobs and different thresholds. [hairline] separates stacked
     * surfaces - row from row, bar from content - and REQUIREMENTS.md deliberately keeps it as the
     * pack drew it, because "the hairline is what separates almost every surface in the pack from
     * the one behind it" and lifting it repaints the whole design. A control boundary is the one
     * use of it that WCAG puts a number on. Splitting the role is what lets the number be met
     * without touching the pack's surfaces.
     *
     * ## Why it is opaque
     *
     * An alpha value cannot hold a ratio, because the ratio depends on what is behind it. At 0.08
     * the same token measures 1.20:1 on [canvas], 1.24:1 on [surface] and 1.25:1 on
     * [surfaceRaised]; the alpha that would clear 3:1 on [canvas] (about 0.34) still fails on
     * [surfaceRaised], because the border and the background lighten together. An opaque value is
     * the only kind that can be measured once.
     *
     * Measured **4.10:1** on [canvas], 3.73:1 on [surface] and 3.33:1 on [surfaceRaised] - past 3:1
     * on all three, which is the full set of backgrounds an outlined control is drawn on. It is a
     * step dimmer than [textMuted] on purpose: a boundary that measured the same as the product's
     * faintest text would read as a word rather than as an edge.
     *
     * ## The rejected alternative
     *
     * Reuse [textMuted], which `toMaterialColorScheme` already maps to Material's `outline` and
     * which now clears 3:1 on all three. Rejected because the two roles then move together: the
     * next revision of the body-copy grey silently repaints every border in the product, and a
     * border is the one of the two that has a hard floor to hold.
     */
    val componentBorder: Color = Color(0xFF6E7867),

    /**
     * The grey blocks a list draws while it is loading. `#343f2b`.
     *
     * ## What this fixes
     *
     * Every skeleton in the product was drawn in [surface], which measures **1.10:1** on [canvas] -
     * `rgb(22,29,18)` on `rgb(13,18,10)`. A skeleton that cannot be seen is a blank screen with the
     * cost of a layout pass: the one thing it exists to say, that content is coming and this is the
     * shape of it, is the thing it was not saying.
     *
     * Measured **1.71:1** on [canvas] and 1.55:1 on [surface]. The band is deliberate and both ends
     * of it matter: below about 1.5:1 the block disappears, and past about 2:1 it starts to read as
     * a filled card with content in it, so a loading list looks like a loaded list of empty rows.
     *
     * ## Why not [surfaceRaised]
     *
     * It is the obvious candidate - the next step up the pack's three surfaces, already drawn - and
     * it measures 1.23:1, which is 0.13 of the way to the problem. The pack's surface ladder is
     * built to be quiet between neighbours; a skeleton needs to be read against the canvas, which
     * is a different question from how two cards sit next to each other.
     */
    val skeleton: Color = Color(0xFF343F2B),

    // ---- Accent states (from every screen's `a:hover` rule) ---------------------------------

    /** Hovered or focused accent, from `a:hover { color: #d3e8f8 }`. */
    val accentHover: Color = Color(0xFFD3E8F8),

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

    /** The tablet Connect card, which lets the record show through. `rgba(22,29,18,0.8)`. */
    val surfaceTranslucentSoft: Color = Color(0xFF161D12).copy(alpha = 0.8f),
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
     * Destructive actions and the failed state: "Remove all from device", unpinning, sign-out,
     * delete confirmations, and the `Failed` badge.
     *
     * The pack draws no destructive colour, so "Remove all from device" on screen 12 rendered in
     * the same accent blue as "Connect" and "Play" - a permanent data-loss action styled exactly
     * like the primary action. This value was added on that basis.
     *
     * ## It is the error colour as well, and that is a reversal
     *
     * `NeedlerStateBadge` used to carry a comment saying this was "reserved for data the user is
     * about to lose; a pull that did not land has cost them nothing but time", and drew `Failed` in
     * [textSecondary] - the same grey as the artist line under every title in the same list. So
     * `Ready` was green, `Pulling` was blue, and the one state asking the user to do something was
     * the only state wearing no colour at all. Cost is the wrong axis: a badge's colour reports a
     * state, and the state is that the record did not arrive.
     *
     * The palette already had this and nothing else: `toMaterialColorScheme` maps Material's
     * `error` to it, which is the same claim made in a different vocabulary, and REQUIREMENTS.md
     * "Design system" lists exactly one value outside the [accent] / [positive] pair. Inventing an
     * eleventh token for "failed" would have put two reds in a palette with one of everything else.
     *
     * The hue is not the signal here either. Against [textSecondary] it measures 1.09:1 by
     * luminance, for the same reason [accent] and [positive] measure 1.01:1 against each other, so
     * `Failed` also draws at `FontWeight.Bold` where the rest of the badges draw at 600 - the
     * second channel the end states cannot get from a glyph, since the pack gives them none.
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
