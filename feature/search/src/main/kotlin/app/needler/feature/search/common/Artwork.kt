package app.needler.feature.search.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import app.needler.core.design.component.NeedlerArtwork
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Track

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
 * That mapper is `:app`'s `ArtworkLoader`. For every album that genuinely has no
 * cover behind it, and for the moment before one arrives, `:core:design`'s
 * [NeedlerArtwork] draws the letter over a tint derived from the release-group
 * MBID. The identity is the MBID and not the title, as its own documentation
 * requires: two records called "Greatest Hits" are two colours, and the same
 * record is the same colour here, on album detail and in the widget.
 */
@Composable
internal fun AlbumArtwork(
    album: Album,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
) {
    NeedlerArtwork(
        model = album.artwork,
        identity = album.releaseGroupMbid.value,
        name = album.title,
        // Null everywhere: every row on this screen already names its album, and
        // a screen reader told "Mordechai by Khruangbin" twice is worse than one
        // told it once.
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
 * [Album] are separate types carrying the same [ArtworkRef], and each knows a
 * different place to find its release group. Both draw at the one thumbnail size
 * the results list uses throughout; see the Songs row for why the pack's smaller
 * song tile was not kept.
 *
 * The placeholder identity is the song's own release-group MBID, taken from its
 * [app.needler.core.domain.model.TrackKey], so a song and the album it is on draw
 * the same colour on the same screen. The letter is the album's title for the same
 * reason: a Songs row already carries the song's name in text beside it.
 */
@Composable
internal fun TrackArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
) {
    NeedlerArtwork(
        model = track.artwork,
        identity = track.key.releaseGroupMbid.value,
        name = track.albumTitle ?: track.title,
        contentDescription = null,
        modifier = modifier,
        shape = shape,
    )
}

/**
 * An artist avatar: the round tile at the head of the Artist block on screens 03
 * and 10.
 *
 * The pack draws a tinted circle with the artist's initial — `K` for Khruangbin,
 * `Y` for Yussef Dayes — rather than a photograph, and that is exactly what
 * `:core:design`'s [NeedlerArtwork] draws when no image resolves: the letter
 * underneath, the image over it when there is one. Hand-rolling the same stack
 * here, as this file used to, meant the search screen's coverless tiles were a
 * different colour from every other surface's.
 *
 * The circle is passed as the shape, and the artist MBID as the identity, so an
 * artist is one colour wherever they appear. The whole tile is left out of the
 * accessibility tree by passing no description: it carries no information the
 * row's own description does not already contain, and TalkBack announcing a bare
 * "K" beside "Khruangbin" is noise.
 */
@Composable
internal fun ArtistAvatar(
    artist: Artist,
    modifier: Modifier = Modifier,
) {
    NeedlerArtwork(
        model = artist.artwork,
        identity = artist.mbid.value,
        name = artist.name,
        contentDescription = null,
        modifier = modifier,
        shape = NeedlerTheme.shapes.circle,
    )
}
