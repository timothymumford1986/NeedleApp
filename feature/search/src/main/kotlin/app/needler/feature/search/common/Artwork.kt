package app.needler.feature.search.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.needler.core.design.component.AsyncAlbumArt
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtworkRef

/**
 * Album artwork, from whichever lane the album came out of.
 *
 * REQUIREMENTS.md "Search behaviour", rule 6: "Cover art for catalogue results
 * comes from `GET /api/v1/covers/release-group/{mbid}`; owned albums use
 * `getCoverArt`." **Neither URL is built here.** The [ArtworkRef] is handed to
 * Coil *as the model*, unresolved, and that is the only arrangement that keeps
 * rule 6 and the layering rule true at the same time: turning
 * `ArtworkRef.Owned("al-…")` into a `getCoverArt` URL needs the server address
 * and the app-password, turning `ArtworkRef.Catalogue(mbid)` into one needs the
 * `/api/v1` cover endpoint with its 202-warming and `X-Cover-Source:
 * placeholder` behaviour, and a feature module cannot see `:core:network` to do
 * either.
 *
 * So `:app` registers one Coil `Mapper` from [ArtworkRef] to a request, and
 * every surface in the app passes the ref straight through. Which lane a cover
 * comes from is therefore decided by the ref the repository attached, not by
 * anything this screen can see — which is exactly what makes a merged result
 * list possible at all.
 *
 * **No such mapper exists yet**, so artwork currently renders as the placeholder
 * tint rather than an image. That is `:feature:library`'s finding too, and it is
 * in the handover notes; nothing below `:app` resolves an `ArtworkRef` today.
 */
@Composable
internal fun AlbumArtwork(
    album: Album,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
) {
    AsyncAlbumArt(
        // Null everywhere: every row on this screen already names its album, and
        // a screen reader told "Mordechai by Khruangbin" twice is worse than one
        // told it once.
        model = album.artwork,
        contentDescription = null,
        modifier = modifier,
        shape = shape,
    )
}

/**
 * A track's artwork, which is its album's.
 *
 * Same arrangement as [AlbumArtwork] and same reason: the ref goes to Coil
 * untouched. Separate only because [app.needler.core.domain.model.Track] and
 * [Album] are separate types carrying the same [ArtworkRef], and the Songs block
 * on screen 03 draws its tiles smaller (44dp) than the album rows above it.
 */
@Composable
internal fun TrackArtwork(
    artwork: ArtworkRef?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
) {
    AsyncAlbumArt(
        model = artwork,
        contentDescription = null,
        modifier = modifier,
        shape = shape,
    )
}

/**
 * An artist avatar: the round tile at the head of the Artist block on screens 03
 * and 10.
 *
 * The pack draws a tinted circle with the artist's initial in Space Grotesk —
 * `K` for Khruangbin, `Y` for Yussef Dayes — rather than a photograph, and that
 * is drawn here as the *base* of the tile rather than as a fallback shown only
 * on failure. Coil paints over it when (and only when) an [ArtworkRef] resolves
 * to a real image, which is why the placeholder colour handed to
 * [AsyncAlbumArt] is transparent: an opaque placeholder would hide the initial
 * for as long as the request took, and forever when the artist has no image at
 * all.
 *
 * The whole tile is removed from the accessibility tree. It carries no
 * information the row's own description does not already contain, and TalkBack
 * announcing a bare "K" beside "Khruangbin" is noise.
 */
@Composable
internal fun ArtistAvatar(
    artist: Artist,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    Box(
        modifier = modifier
            .clip(NeedlerTheme.shapes.circle)
            .background(colors.artworkPlaceholder)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SearchFormat.initial(artist.name),
            style = NeedlerTheme.typography.avatarInitial,
            color = colors.textSecondary,
        )
        val artwork: ArtworkRef? = artist.artwork
        if (artwork != null) {
            AsyncAlbumArt(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = NeedlerTheme.shapes.circle,
                placeholderColor = Color.Transparent,
            )
        }
    }
}
