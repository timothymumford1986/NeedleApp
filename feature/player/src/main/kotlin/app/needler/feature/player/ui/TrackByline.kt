@file:OptIn(ExperimentalLayoutApi::class)

package app.needler.feature.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme

/**
 * The line under the title: the artist, then the album.
 *
 * ## Why it is two nodes instead of one string
 *
 * It was one string, and that made the artist in the player dead text while the same artist on album
 * detail (screen 04) was a blue link to their page. Same content, two capabilities, and the player was
 * the weaker one - a listener who wants the rest of a record they are hearing had to leave the player,
 * find the album, and tap the artist there.
 *
 * The artist half is therefore its own node: accent-coloured and clickable when there is somewhere to go,
 * and plain secondary text when there is not. Both halves keep [PlayerFormat.DOT]'s separator between
 * them, so the line still reads exactly as the pack draws it - `The Marias · Submarine` - and
 * [app.needler.feature.player.PlayerUiState.subtitle] still exists for the surfaces that want the whole
 * line as one string, the mini player among them.
 *
 * The colour and the content description match `AlbumScreen`'s artist link deliberately, down to the
 * wording of the label: two screens that link to the same page should not announce it two ways.
 *
 * ## When there is nowhere to go
 *
 * [onOpenArtist] is nullable rather than defaulted, and null is the honest state rather than a rare one:
 * `Track` carries an artist *name* and no artist MBID, so the destination has to be resolved through the
 * album, and until that resolves - or for an artist the mirror has no MBID for at all - there is nothing
 * to navigate to. A link drawn in accent blue that does nothing when tapped is worse than the grey text
 * this replaced, so the styling follows the capability and not the other way round.
 *
 * `FlowRow`, not `Row`: a long artist and a long album title do not fit one line at 200% text scale, and
 * REQUIREMENTS.md asks that text scale to 200% without clipping.
 */
@Composable
fun TrackByline(
    artistName: String,
    albumTitle: String?,
    onOpenArtist: (() -> Unit)?,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val album: String? = albumTitle?.takeIf { it.isNotBlank() }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
    ) {
        Text(
            text = artistName,
            style = style,
            color = if (onOpenArtist != null) colors.accent else colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (onOpenArtist == null) {
                Modifier
            } else {
                Modifier
                    .clickable(role = Role.Button, onClick = onOpenArtist)
                    .semantics { contentDescription = "Go to " + artistName }
            },
        )
        if (album != null) {
            Text(
                // The dot travels with the album rather than sitting between the two as a node of its
                // own, so a wrap puts "· Submarine" on the second line instead of orphaning the dot.
                text = PlayerFormat.DOT.trim() + " " + album,
                style = style,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The gap between the two halves.
 *
 * [PlayerFormat.DOT] is a middle dot with a space either side, and the space before it belongs to this
 * gap once the line is two nodes - so the dot is trimmed at the call site and 4 dp stands in for it,
 * which is what the pack's own spacing measures to.
 */
private val DOT_GAP: Dp = 4.dp
