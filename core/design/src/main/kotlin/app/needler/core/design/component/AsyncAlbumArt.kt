package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import app.needler.core.design.theme.NeedlerTheme
import coil3.compose.AsyncImage

/**
 * Album and artist artwork, loaded through Coil 3.
 *
 * Every piece of artwork in the pack is a square with a rounded corner over a solid tint, and the
 * tint shows for as long as the image takes to arrive. This wrapper is that: a clipped, tinted box
 * with the image cropped to fill it, so a slow or missing cover degrades to a coloured square
 * rather than to a gap in the grid.
 *
 * Corner radii come from the tokens and follow the artwork's size, as the pack does: 10dp for a row
 * thumbnail, 14dp for a grid cell, 16dp on album detail, 18-20dp for the hero.
 *
 * **Accessibility.** Pass [contentDescription] built with [albumArtContentDescription], matching the
 * pack's own `alt` text ("Submarine by The Marías"). Pass `null` only when the artwork sits inside a
 * row or cell that already names the album, so a screen reader is not told the same thing twice.
 *
 * @param model anything Coil accepts: a URL string, a `coil3.request.ImageRequest`, a `Uri`, a
 *   resource id. Needler passes the Subsonic `getCoverArt` URL for owned albums and the
 *   `/api/v1/covers/release-group/{mbid}` URL for catalogue results.
 * @param placeholderColor the tint shown behind and before the image. The pack tints each square
 *   from the artwork itself; pass that colour when it is known, otherwise the default
 *   [NeedlerColors.artworkPlaceholder] is used.
 */
@Composable
fun AsyncAlbumArt(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    placeholderColor: Color = NeedlerTheme.colors.artworkPlaceholder,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(placeholderColor),
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The pack's own wording for artwork alt text: "Submarine by The Marías".
 *
 * @return `null` when there is nothing useful to say, which tells [AsyncAlbumArt] to stay out of the
 *   accessibility tree.
 */
fun albumArtContentDescription(albumTitle: String?, artistName: String?): String? = when {
    albumTitle.isNullOrBlank() && artistName.isNullOrBlank() -> null
    artistName.isNullOrBlank() -> albumTitle
    albumTitle.isNullOrBlank() -> artistName
    else -> "$albumTitle by $artistName"
}
