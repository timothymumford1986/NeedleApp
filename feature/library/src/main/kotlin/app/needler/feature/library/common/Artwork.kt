package app.needler.feature.library.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.component.albumArtContentDescription
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.ArtworkRef

/**
 * Album artwork, from whichever lane the album came out of.
 *
 * The [ArtworkRef] is handed to Coil **as the model**, unresolved. That is
 * deliberate, and it is the only arrangement that keeps the layering rule
 * intact: turning `ArtworkRef.Owned("al-…")` into a URL needs the server base
 * address and the app-password, and turning `ArtworkRef.Catalogue(mbid)` into
 * one needs the `/api/v1` cover endpoint and its 202-warming and
 * `X-Cover-Source: placeholder` behaviour. None of that is a screen's business,
 * and a feature module cannot see `:core:network` to do it anyway.
 *
 * So `:app`, which builds the one shared OkHttp client, registers a Coil
 * `Mapper` from [ArtworkRef] to a request, and every surface in the app — these
 * screens, the widgets, Auto — passes the ref straight through.
 *
 * **No such mapper exists yet**, which means artwork currently renders as the
 * placeholder tint rather than an image. That is in the handover notes: nothing
 * in `:core:design`, `:core:domain` or `:app` resolves an `ArtworkRef` today.
 * The screens are written against the shape the answer has to have rather than
 * around it, so wiring the mapper is the whole of the remaining work.
 */
@Composable
internal fun AlbumArtwork(
    album: Album,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    decorative: Boolean = false,
) {
    AsyncAlbumArt(
        model = album.artwork,
        contentDescription = if (decorative) {
            null
        } else {
            albumArtContentDescription(album.title, album.artistName)
        },
        modifier = modifier,
        shape = shape,
    )
}
