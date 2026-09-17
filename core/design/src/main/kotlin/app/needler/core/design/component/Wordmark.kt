package app.needler.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The logo mark: a record seen face-on, as a ring with a spindle dot.
 *
 * The pack draws it at 34dp on phone Connect (2.5dp ring, 8dp dot), 30dp in the tablet Connect card
 * (2.5dp, 7dp) and 40dp at the top of the tablet nav rail (3dp, 12dp). Ring and dot scale with
 * [size] by those proportions unless given explicitly.
 *
 * Decorative on its own: use [NeedlerWordmark] where the mark stands for the app's name.
 */
@Composable
fun NeedlerLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    color: Color = NeedlerTheme.colors.textPrimary,
    ringWidth: Dp = size * (2.5f / 34f),
    dotSize: Dp = size * (8f / 34f),
) {
    Canvas(modifier = modifier.size(size).clearAndSetSemantics {}) {
        val ringPx = ringWidth.toPx()
        val radius = (this.size.minDimension - ringPx) / 2f
        drawCircle(
            color = color,
            radius = radius,
            style = Stroke(width = ringPx),
        )
        drawCircle(color = color, radius = dotSize.toPx() / 2f)
    }
}

/**
 * The wordmark: the logo mark beside NEEDLER.
 *
 * Space Grotesk 700, uppercase, at `0.04em` tracking - the one typographic rule REQUIREMENTS.md
 * spells out for the wordmark. 28sp on the phone Connect screen, 24sp in the tablet Connect card.
 *
 * The mark and the word merge into a single node labelled "Needler", so a screen reader announces
 * the app's name once rather than describing a circle.
 */
@Composable
fun NeedlerWordmark(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = NeedlerTheme.typography.wordmark,
    markSize: Dp = 34.dp,
    color: Color = NeedlerTheme.colors.textPrimary,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Needler"
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeedlerLogoMark(size = markSize, color = color)
        Text(
            text = "NEEDLER",
            style = textStyle,
            color = color,
            maxLines = 1,
        )
    }
}
