package app.needler.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.albumArtContentDescription
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.ArtworkRef

/**
 * Album artwork for the player, with the empty case drawn rather than left blank.
 *
 * `:core:design`'s [AsyncAlbumArt] always goes through Coil, which is right for a grid of albums
 * that definitely have covers and wrong for the player, whose commonest state is nothing playing at
 * all. So a null [artwork] short-circuits to the tinted square the pack shows behind a loading cover
 * and no image request is made.
 *
 * ## How an [ArtworkRef] becomes an image
 *
 * It is handed to Coil as the model, unresolved. Turning a ref into a URL needs the server's base
 * address and the session's credentials, which live in `:core:data`, and a feature module does not
 * see that module - that is the layering rule, not an oversight. `:app` builds the one `ImageLoader`
 * for the whole app and registers a `Mapper` from [ArtworkRef] onto the right URL there, beside the
 * OkHttp client that already carries the certificate pinning. Until it does, artwork degrades to the
 * tinted square, which is exactly what the design asks for while a cover is on its way.
 */
@Composable
fun PlayerArtwork(
    artwork: ArtworkRef?,
    albumTitle: String?,
    artistName: String?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    describe: Boolean = true,
    /**
     * The tint shown before, behind and instead of an image.
     *
     * The default is the pack's placeholder, which is the raised surface - so on a raised surface,
     * such as the mini player's card, pass something darker or the square vanishes into the card it
     * sits on and the row looks like it has lost its artwork slot.
     */
    placeholderColor: Color = NeedlerTheme.colors.artworkPlaceholder,
) {
    if (artwork == null) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(placeholderColor),
        )
        return
    }
    AsyncAlbumArt(
        model = artwork,
        contentDescription = if (describe) {
            albumArtContentDescription(albumTitle, artistName)
        } else {
            null
        },
        modifier = modifier,
        shape = shape,
        placeholderColor = placeholderColor,
    )
}
