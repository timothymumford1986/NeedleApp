package app.needler.widget.internal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * The design system, restated in Glance's vocabulary.
 *
 * `:core:design` owns Needler's palette, typography and shape scale, and this file is not a second
 * copy of them - it is the subset the widgets actually draw, transcribed because a Glance
 * composable cannot see any of it. Glance runs on its own runtime and emits `RemoteViews`; it has no
 * `MaterialTheme`, no `CompositionLocal` from `androidx.compose.ui`, and a `androidx.compose.ui.text.TextStyle`
 * is not the same type as a [androidx.glance.text.TextStyle]. `widget/build.gradle.kts` states the
 * consequence plainly: the duplication is Glance's, not ours to avoid.
 *
 * Every value below is cited to the inline styles in `design/html/15-Widget.html` and
 * `design/html/18-TabletWidget.html`, which draw the same card at 350dp and 420dp. Where the two
 * artboards differ the difference is width alone, so nothing here is branched on form factor.
 *
 * ## What Glance cannot carry across, and what was done instead
 *
 * **Typefaces.** REQUIREMENTS.md "Design system" puts Space Grotesk on the wordmark and numerals and
 * Hanken Grotesk on body text, and `:core:design`'s `Type.kt` fetches both as downloadable Google
 * Fonts. A widget is rendered by the launcher from `RemoteViews`, which can only name a font the
 * *system* already has, so a downloadable font provider cannot reach it. The widgets therefore draw
 * in the platform's own family. Bundling the two families as `res/font` assets would fix it and was
 * deliberately not done: it is roughly 300 KB of font binaries in an APK that REQUIREMENTS.md
 * distributes as a GitHub download, for four short strings.
 *
 * **Tracking and tabular numerals.** The pack sets `letter-spacing: 0.1em` on the eyebrow and
 * `font-variant-numeric: tabular-nums` on both timecodes. Glance's [TextStyle] has neither field.
 * The eyebrow is written pre-uppercased so it still reads as a label; the timecodes are given a
 * fixed-width slot instead (see `WidgetDimensions.timecodeWidth`), which buys the thing tabular
 * numerals were there for - the bar between them does not jump as the digits change.
 *
 * **Ellipsis.** The pack sets `text-overflow: ellipsis` on the title and the artist line. Glance can
 * cap a text at one line but cannot ask for an ellipsis, so a long title is clipped rather than
 * trailed off. It is a visible difference from the drawing and there is no API for it.
 */
internal object WidgetColours {

    /** `NeedlerColors.textPrimary`, `#f2f5ee`: the track title. */
    val textPrimary: ColorProvider = ColorProvider(Color(0xFFF2F5EE))

    /** `NeedlerColors.textSecondary`, `#a8b3a0`: the eyebrow and the artist line. */
    val textSecondary: ColorProvider = ColorProvider(Color(0xFFA8B3A0))

    /**
     * `NeedlerColors.textMuted`, `#6f7a68`: the two timecodes, exactly as drawn.
     *
     * `:core:design` records that this value measures 4.21:1 on the canvas and so fails WCAG AA for
     * normal text, and that keeping it is a deliberate product decision rather than an oversight.
     * The same decision is inherited here rather than quietly re-taken: a widget that used the
     * accessible alternative would be the one surface in the product drawing a different grey.
     */
    val textMuted: ColorProvider = ColorProvider(Color(0xFF6F7A68))

    /** `NeedlerColors.accent`, `#aed5f2`: the transport disc and the elapsed bar. */
    val accent: ColorProvider = ColorProvider(Color(0xFFAED5F2))

    // The colours used only as fills behind a view live in res/values/widget_colours.xml instead,
    // because a <shape> drawable is the only way to get the pack's corner radii on minSdk 26.
    // See widget_card.xml for why cornerRadius() is not used.
}

/**
 * Every measurement on the card, in the pack's own numbers.
 *
 * These are read straight off the two artboards. The one thing worth knowing is how they add up:
 * `14 + 64 + 12 + 124 + 14` is 228dp of fixed furniture, so the title and artist lines get whatever
 * the widget's width leaves over - 122dp on the phone's 350dp card, which is exactly what the pack
 * draws. That sum is why `widget_now_playing_info.xml` refuses to advertise a minWidth below 250dp.
 */
internal object WidgetDimensions {

    /** `padding: 14px` on the card. */
    val cardPadding: Dp = 14.dp

    /** The cover square: `width: 64px; height: 64px`. */
    val artwork: Dp = 64.dp

    /** `border-radius: 12px` on the cover. Applied to the bitmap itself; see `WidgetArtwork`. */
    val artworkCorner: Dp = 12.dp

    /** `gap: 12px` between the cover and the text column. */
    val artworkGap: Dp = 12.dp

    /** The record mark beside the wordmark: `width: 12px; height: 12px`. */
    val recordMark: Dp = 12.dp

    /** `gap: 6px` between the record mark and the wordmark. */
    val eyebrowGap: Dp = 6.dp

    /** Previous and Next: `width: 40px; height: 40px`, transparent. */
    val secondaryButton: Dp = 40.dp

    /** Play/Pause: `width: 44px; height: 44px`, the accent disc. */
    val primaryButton: Dp = 44.dp

    /** Every transport glyph is drawn at `width="22" height="22"`. */
    val transportIcon: Dp = 22.dp

    /** `gap: 12px` between the now-playing row and the position row. */
    val rowGap: Dp = 12.dp

    /**
     * The slot each timecode gets.
     *
     * The pack lets the labels size themselves, which it can afford because CSS has tabular
     * numerals and Glance does not. A fixed slot is the substitute: it keeps the bar between them
     * still while the digits change, and it is wide enough for `1:02:03` at 11sp, which is the
     * longest timecode `WidgetFormat.timecode` can print for anything a person would call a track.
     */
    val timecodeWidth: Dp = 42.dp

    /** `gap: 10px` either side of the position bar. */
    val positionGap: Dp = 10.dp

    /** The position bar: `height: 4px`. Its radius lives in the drawable. */
    val barHeight: Dp = 4.dp

    /**
     * Below this width the position row is dropped.
     *
     * A user can resize a widget far below the size it advertises. At 250dp the fixed furniture
     * already eats 228dp, and a position bar squeezed into what is left is a smear rather than a
     * reading. Dropping the row keeps the title legible, which is the part of the card that is
     * actually load-bearing.
     */
    val positionRowMinWidth: Dp = 260.dp

    /** Below this height there is no room for a second row at all. */
    val positionRowMinHeight: Dp = 104.dp

    /**
     * Below this width Previous and Next are dropped, leaving only the primary button.
     *
     * 80dp of transport is the difference between a title with room to breathe and a title showing
     * three characters. Play/Pause survives because it is the one control that is worth a widget.
     */
    val skipButtonsMinWidth: Dp = 220.dp
}

/**
 * The four text styles the card uses.
 *
 * Sizes are the pack's `font-size` values unchanged. `font-weight: 600` has no Glance equivalent -
 * [FontWeight] offers Normal, Medium and Bold only - so the title takes Medium, which is the nearest
 * of the three and the one that does not make a 16sp line on a dark card look shouty.
 */
internal object WidgetText {

    /** `NEEDLER` beside the record mark: `11px/700`, uppercase, `letter-spacing: 0.1em`. */
    val eyebrow: TextStyle = TextStyle(
        color = WidgetColours.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )

    /** The track title: `16px/600`. */
    val title: TextStyle = TextStyle(
        color = WidgetColours.textPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
    )

    /** `The Marías · Submarine`: `13px`, `color: #a8b3a0`. */
    val subtitle: TextStyle = TextStyle(
        color = WidgetColours.textSecondary,
        fontSize = 13.sp,
    )

    /** `1:16`, the elapsed side: `11px`, `color: #6f7a68`. */
    val timecode: TextStyle = TextStyle(
        color = WidgetColours.textMuted,
        fontSize = 11.sp,
    )

    /**
     * `3:20`, the length side.
     *
     * Identical but right-aligned, because both timecodes sit in a fixed-width slot (see
     * `WidgetDimensions.timecodeWidth`) and the pack draws the length hard against the card's
     * padding rather than floating in the middle of a box.
     */
    val timecodeEnd: TextStyle = TextStyle(
        color = WidgetColours.textMuted,
        fontSize = 11.sp,
        textAlign = TextAlign.End,
    )
}
