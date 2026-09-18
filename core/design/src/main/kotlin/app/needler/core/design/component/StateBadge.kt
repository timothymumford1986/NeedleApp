package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.design.theme.tabularNumerals

/**
 * The state of an album, as the pack badges it.
 *
 * REQUIREMENTS.md fixes one label per state, and screens 03 and 10 show four of them on the same
 * list. The three extra members are the Pulls screen's own states (06) plus the two states the
 * server model derives client-side.
 *
 * | State | Badge | Colour |
 * | --- | --- | --- |
 * | `Owned` | In library | secondary |
 * | `Pinned` | On device | positive |
 * | `Acquiring` | Pulling, with percentage | positive |
 * | `PendingApproval` | Waiting | secondary |
 * | `Failed` | no source found | muted |
 * | (pull, searching) | Searching | secondary |
 * | (pull, parked) | Needs attention on the server | secondary |
 * | (pull, complete) | Ready | positive |
 *
 * `NotOwned` has no badge at all - it gets [NeedlerPullButton] instead.
 */
sealed interface NeedlerAlbumBadge {
    /** Owned by the server, streams on demand. Secondary colour with a check (03, 10). */
    data object InLibrary : NeedlerAlbumBadge

    /** Cached locally, plays without a network. Positive green with the phone-and-check (03, 13). */
    data object OnDevice : NeedlerAlbumBadge

    /**
     * The server is acquiring it. Positive green with the download arrow (03, 10).
     *
     * @param percent 0-100 when the server has reported progress, `null` while it has not.
     */
    data class Pulling(val percent: Int? = null) : NeedlerAlbumBadge

    /** Requested by a `user` role and waiting for an admin. Secondary with a clock. */
    data object Waiting : NeedlerAlbumBadge

    /** `queued` with no search job: the server is still looking for sources (06). */
    data object Searching : NeedlerAlbumBadge

    /** `queued` with a search job but no candidate: a manual pick is parked on the server. */
    data object NeedsAttention : NeedlerAlbumBadge

    /** The pull landed and the album is playable. Positive green with a check (06). */
    data object Ready : NeedlerAlbumBadge

    /** No usable source was found. Muted, and the pack gives it no icon (06). */
    data object NoSource : NeedlerAlbumBadge
}

/**
 * The album state badge: an icon and a label, in the colour the state carries.
 *
 * 13sp/600 with a 16dp glyph and 6dp between them, as drawn on screens 03, 06, 10 and 13. There is
 * no chip, no fill and no border - the colour is the whole treatment.
 *
 * The icon and label merge into one accessibility node, so TalkBack says "Pulling, 62 percent"
 * rather than reading a decorative glyph and then a number.
 */
@Composable
fun NeedlerStateBadge(
    badge: NeedlerAlbumBadge,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    val tint: Color = when (badge) {
        NeedlerAlbumBadge.OnDevice,
        NeedlerAlbumBadge.Ready,
        is NeedlerAlbumBadge.Pulling -> colors.positive

        NeedlerAlbumBadge.InLibrary,
        NeedlerAlbumBadge.Waiting,
        NeedlerAlbumBadge.Searching,
        NeedlerAlbumBadge.NeedsAttention -> colors.textSecondary

        // The pack renders this one as muted metadata rather than as a coloured badge. It uses the
        // AA-compliant muted value because "no source found" is the only explanation the user gets.
        NeedlerAlbumBadge.NoSource -> colors.textMuted
    }

    val label = badge.label()
    val percent = (badge as? NeedlerAlbumBadge.Pulling)?.percent

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = badge.accessibleLabel()
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (badge) {
            NeedlerAlbumBadge.InLibrary, NeedlerAlbumBadge.Ready ->
                NeedlerCheckIcon(tint = tint)

            NeedlerAlbumBadge.OnDevice ->
                NeedlerOnDeviceIcon(tint = tint)

            is NeedlerAlbumBadge.Pulling ->
                NeedlerPullIcon(tint = tint)

            NeedlerAlbumBadge.Waiting,
            NeedlerAlbumBadge.Searching,
            NeedlerAlbumBadge.NeedsAttention ->
                NeedlerClockIcon(tint = tint)

            NeedlerAlbumBadge.NoSource -> Unit
        }
        Text(text = label, style = typography.metaStrong, color = tint, maxLines = 2)
        if (percent != null) {
            Text(
                text = "$percent%",
                // Tabular so the row does not twitch as the percentage counts up.
                style = typography.metaStrong.tabularNumerals(),
                color = tint,
            )
        }
    }
}

/** The label the pack puts on this state. */
fun NeedlerAlbumBadge.label(): String = when (this) {
    NeedlerAlbumBadge.InLibrary -> "In library"
    NeedlerAlbumBadge.OnDevice -> "On device"
    is NeedlerAlbumBadge.Pulling -> "Pulling"
    NeedlerAlbumBadge.Waiting -> "Waiting"
    NeedlerAlbumBadge.Searching -> "Searching"
    NeedlerAlbumBadge.NeedsAttention -> "Needs attention on the server"
    NeedlerAlbumBadge.Ready -> "Ready"
    NeedlerAlbumBadge.NoSource -> "no source found"
}

/** The same, spoken: a full phrase, with the percentage read out rather than shown as a symbol. */
fun NeedlerAlbumBadge.accessibleLabel(): String = when (this) {
    is NeedlerAlbumBadge.Pulling ->
        if (percent == null) "Pulling" else "Pulling, $percent percent"
    NeedlerAlbumBadge.NoSource -> "No source found"
    else -> label()
}

/**
 * The Pull action, offered on any album the server does not own.
 *
 * A 40dp accent pill with the download arrow, from the search results on screens 03 and 10. The
 * album title goes into the content description so a screen reader hears which album a lone "Pull"
 * button would acquire.
 */
@Composable
fun NeedlerPullButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    albumTitle: String? = null,
    enabled: Boolean = true,
) {
    NeedlerPrimaryButton(
        text = "Pull",
        onClick = onClick,
        modifier = modifier,
        size = NeedlerButtonSize.Small,
        enabled = enabled,
        leadingIcon = { tint -> NeedlerPullIcon(tint = tint) },
        contentDescription = albumTitle?.let { "Pull $it" },
    )
}

/**
 * The format badge: FLAC, MP3 320.
 *
 * A 6dp-cornered outline in the positive green with Space Grotesk 11sp/700 at `0.08em`, from Now
 * Playing (07) and the tablet sidebar (09). Screen 13 draws the same information as a green label
 * with the on-device glyph instead; that variant is a [NeedlerStateBadge] plus a text run, not this.
 */
@Composable
fun NeedlerFormatBadge(
    format: String,
    modifier: Modifier = Modifier,
    tint: Color = NeedlerTheme.colors.positive,
) {
    val shape = NeedlerTheme.shapes.formatBadge
    Box(
        modifier = modifier
            .clip(shape)
            .border(NeedlerTheme.sizes.hairlineThickness, tint, shape)
            .defaultMinSize(minHeight = 24.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = format,
            style = NeedlerTheme.typography.badge,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * The count badge on the Pulls nav item.
 *
 * An 18dp positive-green pill with the count in Space Grotesk 11sp/700, from every screen's nav.
 * REQUIREMENTS.md calls this "the reliable channel" for pull state, so it is a shared component
 * rather than something each nav host draws for itself.
 */
@Composable
fun NeedlerCounterBadge(
    count: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (count <= 0) return
    val colors = NeedlerTheme.colors
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = NeedlerTheme.sizes.counterBadge,
                minHeight = NeedlerTheme.sizes.counterBadge,
            )
            .clip(NeedlerTheme.shapes.pill)
            .background(colors.positive)
            .padding(horizontal = 5.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = NeedlerTheme.typography.counter,
            color = colors.onPositive,
            maxLines = 1,
        )
    }
}
