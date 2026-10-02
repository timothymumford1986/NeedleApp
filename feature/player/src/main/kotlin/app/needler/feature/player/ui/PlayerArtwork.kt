package app.needler.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.needler.core.design.component.NeedlerArtwork
import app.needler.core.design.component.albumArtContentDescription
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef

/**
 * Album artwork for the player: the same picture the rest of the app draws for the same album.
 *
 * ## Why this is a two-line wrapper now
 *
 * It used to draw its own empty case - a plain square of
 * [app.needler.core.design.theme.NeedlerColors.artworkPlaceholder] whenever [artwork] was null - on
 * the argument that the player's commonest state is nothing playing and that a Coil request for a
 * null model is waste. The second half is true and the first half was the bug. An album the server
 * has no cover for is not the player's empty state: a track is playing, it has a title and an
 * artist, and the library grid, album detail and search results all draw it as the album's initial
 * over a tint derived from its release group. The player drew a flat box instead, so one absence had
 * two answers and the player's read as a failed image load. Illinois and Hello Nasty found it on the
 * device.
 *
 * So [NeedlerArtwork] is called, which layers that placeholder under the image and needs no branch
 * of its own: nothing at this layer can tell an album with no cover from a cover still in flight,
 * and the right picture is the same either way. Forking a second fallback here is what produced the
 * defect, so there is deliberately no drawing left in this file - only the player's own empty state,
 * below.
 *
 * ## Nothing playing is still a square
 *
 * A null [identity] means there is no album at all, which is a different thing from an album with no
 * cover: there is no release group to derive a tint from and no title to take a letter from, and a
 * letter invented for the empty state would be a lie about what is loaded. That case keeps the pack's
 * placeholder tint, and [ArtworkOnRecord] writes "Nothing playing" across it.
 *
 * ## How an [ArtworkRef] becomes an image
 *
 * It is handed to Coil as the model, unresolved. Turning a ref into a URL needs the server's base
 * address and the session's credentials, which live in `:core:data`, and a feature module does not
 * see that module - that is the layering rule, not an oversight. `:app` builds the one `ImageLoader`
 * for the whole app and registers a `Mapper` from [ArtworkRef] onto the right URL there, beside the
 * OkHttp client that already carries the certificate pinning. Until a cover resolves, what shows is
 * the letter placeholder, which is also what shows if it never does.
 *
 * @param identity the stable key the tint is derived from: the track's release-group MBID, which is
 *   what every other surface passes, so one record is one colour in the player, on the library grid
 *   and in a widget. Null only when nothing is loaded. **Not** the title - see [NeedlerArtwork].
 * @param albumTitle the album's name, which is both the letter on the placeholder and half of the
 *   spoken description. A blank one leaves the tint with no letter rather than inventing a glyph.
 */
@Composable
fun PlayerArtwork(
    artwork: ArtworkRef?,
    identity: String?,
    albumTitle: String?,
    artistName: String?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    describe: Boolean = true,
    /**
     * The tint of the empty slot, drawn only when [identity] is null.
     *
     * The default is the pack's placeholder, which is the raised surface - so on a raised surface,
     * such as the mini player's card, pass something darker or the square vanishes into the card it
     * sits on and the row looks like it has lost its artwork slot. It no longer has any say over a
     * coverless album: that tint is derived from [identity] and is never the surface colour.
     */
    emptyColor: Color = NeedlerTheme.colors.artworkPlaceholder,
) {
    if (identity == null) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(emptyColor),
        )
        return
    }
    NeedlerArtwork(
        model = artwork,
        identity = identity,
        name = albumTitle,
        contentDescription = if (describe) {
            albumArtContentDescription(albumTitle, artistName)
        } else {
            null
        },
        modifier = modifier,
        shape = shape,
    )
}
