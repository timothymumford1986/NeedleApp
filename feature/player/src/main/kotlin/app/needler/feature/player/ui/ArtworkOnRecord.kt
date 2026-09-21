package app.needler.feature.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.motion.NeedlerSpinningRecord
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef

/**
 * The sleeve with the record sticking out behind it, from Now Playing (07) and the sidebar (09).
 *
 * The pack builds it as three absolutely-positioned layers in a fixed box: a spinning disc offset to
 * the right, the disc's centre label, and the square sleeve on top at the left. The measurements are
 * different on the two screens - a 270 dp sleeve with the disc pushed 56 dp right on the phone, a
 * 250 dp sleeve with a *larger* 270 dp disc pushed 66 dp right in the sidebar - so they are
 * parameters rather than constants, and [phone] and [sidebar] name the two the pack actually draws.
 *
 * The disc holds still when nothing is playing, and starts no animation at all under reduced motion:
 * both are [NeedlerSpinningRecord]'s own behaviour, not something re-decided here.
 *
 * With nothing playing the sleeve is the pack's placeholder tint carrying [emptyLabel], so the
 * commonest state of this screen is a composed picture rather than a hole. With a track loaded but no
 * cover yet it is the tint alone, which is what the pack shows while an image is on its way.
 */
@Composable
fun ArtworkOnRecord(
    artwork: ArtworkRef?,
    albumTitle: String?,
    artistName: String?,
    playing: Boolean,
    metrics: ArtworkOnRecordMetrics,
    modifier: Modifier = Modifier,
    /**
     * The line drawn across the sleeve when there is nothing to play at all.
     *
     * Null when something is loaded. Missing *artwork* is not the empty state - a cover that has not
     * arrived yet, or an album the server has none for, still has a track playing - so the label is
     * driven by the crate rather than by whether there is an image.
     */
    emptyLabel: String? = null,
) {
    val colors = NeedlerTheme.colors

    Box(
        modifier = modifier
            .width(metrics.discOffsetX + metrics.discSize)
            .height(metrics.boxHeight),
    ) {
        NeedlerSpinningRecord(
            playing = playing,
            modifier = Modifier
                .offset(x = metrics.discOffsetX, y = metrics.discOffsetY)
                .size(metrics.discSize),
            labelColor = colors.textPrimary,
        )
        Box(
            modifier = Modifier
                .offset(y = metrics.artOffsetY)
                .size(metrics.artSize),
            contentAlignment = Alignment.Center,
        ) {
            PlayerArtwork(
                artwork = artwork,
                albumTitle = albumTitle,
                artistName = artistName,
                modifier = Modifier.size(metrics.artSize),
                shape = metrics.artShape,
            )
            if (artwork == null && emptyLabel != null) {
                Text(
                    text = emptyLabel,
                    style = NeedlerTheme.typography.body,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp)
                        .width(metrics.artSize),
                )
            }
        }
    }
}

/** The pack's two sets of measurements for [ArtworkOnRecord]. */
data class ArtworkOnRecordMetrics(
    val artSize: Dp,
    val discSize: Dp,
    val discOffsetX: Dp,
    val artOffsetY: Dp,
    val discOffsetY: Dp,
    val boxHeight: Dp,
    val artShape: Shape,
) {
    companion object {
        /** Now Playing on a phone: a 270 dp sleeve in a 326 dp box, disc 56 dp to the right. */
        @Composable
        fun phone(): ArtworkOnRecordMetrics = ArtworkOnRecordMetrics(
            artSize = 270.dp,
            discSize = 270.dp,
            discOffsetX = 56.dp,
            artOffsetY = 28.dp,
            discOffsetY = 28.dp,
            boxHeight = 326.dp,
            artShape = NeedlerTheme.shapes.artworkHero,
        )

        /** The tablet sidebar: a 250 dp sleeve under a 270 dp disc, in a 336 by 300 dp box. */
        @Composable
        fun sidebar(): ArtworkOnRecordMetrics = ArtworkOnRecordMetrics(
            artSize = 250.dp,
            discSize = 270.dp,
            discOffsetX = 66.dp,
            artOffsetY = 25.dp,
            discOffsetY = 15.dp,
            boxHeight = 300.dp,
            artShape = NeedlerTheme.shapes.artworkHeroTablet,
        )
    }
}
