package app.needler.core.data.mapper

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * The one place Subsonic's type-prefixed ids are taken apart and put back together.
 *
 * DroppedNeedle's compat shim keys everything on a prefix plus a MusicBrainz id or a server row id:
 *
 * | Subsonic id      | Internal value            |
 * | ---------------- | ------------------------- |
 * | `al-<uuid>`      | release-group MBID        |
 * | `ar-<uuid>`      | artist MBID               |
 * | `tr-<id>`        | track file row id         |
 * | `pl-<id>`        | playlist id               |
 * | `ge-<slug>`      | genre slug                |
 *
 * Stripping `al-` yields exactly the key `POST /api/v1/requests/new` accepts, which is what lets one
 * `Album` describe both an owned album and a catalogue-only one. Doing that strip at every call site
 * is how the two halves drift apart, so it happens here and nowhere else.
 */
public object SubsonicIds {

    public const val ALBUM_PREFIX: String = "al-"
    public const val ARTIST_PREFIX: String = "ar-"
    public const val TRACK_PREFIX: String = "tr-"
    public const val PLAYLIST_PREFIX: String = "pl-"
    public const val GENRE_PREFIX: String = "ge-"

    // ------------------------------------------------------------------ building

    public fun albumId(mbid: ReleaseGroupMbid): String = ALBUM_PREFIX + mbid.value

    public fun albumId(mbid: String): String = prefix(ALBUM_PREFIX, mbid)

    public fun artistId(mbid: ArtistMbid): String = ARTIST_PREFIX + mbid.value

    public fun artistId(mbid: String): String = prefix(ARTIST_PREFIX, mbid)

    public fun trackId(fileId: FileId): String = TRACK_PREFIX + fileId.value

    public fun trackId(fileId: String): String = prefix(TRACK_PREFIX, fileId)

    public fun playlistId(id: PlaylistId): String = PLAYLIST_PREFIX + id.value

    public fun playlistId(id: String): String = prefix(PLAYLIST_PREFIX, id)

    public fun genreId(slug: String): String = prefix(GENRE_PREFIX, slug)

    // ------------------------------------------------------------------ stripping

    /**
     * Release-group MBID from `al-<mbid>`, or from a bare MBID.
     *
     * [fallback] is the `musicBrainzId` field the shim also sets on album entries: the two agree in
     * practice, and taking either means a server that stops sending one of them still maps.
     */
    public fun releaseGroupMbid(id: String?, fallback: String? = null): ReleaseGroupMbid? {
        val bare: String = bare(id, ALBUM_PREFIX) ?: bare(fallback, ALBUM_PREFIX) ?: return null
        return ReleaseGroupMbid(bare)
    }

    public fun artistMbid(id: String?, fallback: String? = null): ArtistMbid? {
        val bare: String = bare(id, ARTIST_PREFIX) ?: bare(fallback, ARTIST_PREFIX) ?: return null
        return ArtistMbid(bare)
    }

    /**
     * File id from `tr-<file id>`.
     *
     * This is a fetch handle, never an identity - see `FileId`. Nothing in this module may use the
     * result as a map, cache or row key.
     */
    public fun fileId(id: String?): FileId? {
        val bare: String = bare(id, TRACK_PREFIX) ?: return null
        return FileId(bare)
    }

    public fun playlistIdOrNull(id: String?): PlaylistId? {
        val bare: String = bare(id, PLAYLIST_PREFIX) ?: return null
        return PlaylistId(bare)
    }

    public fun genreSlug(id: String?): String? = bare(id, GENRE_PREFIX)

    // ------------------------------------------------------------------ routing

    /** True for an `al-` id. Used to route a mixed `star`/`unstar` id list. */
    public fun isAlbumId(id: String): Boolean = id.startsWith(ALBUM_PREFIX)

    public fun isArtistId(id: String): Boolean = id.startsWith(ARTIST_PREFIX)

    public fun isTrackId(id: String): Boolean = id.startsWith(TRACK_PREFIX)

    // ------------------------------------------------------------------ internals

    private fun prefix(prefix: String, value: String): String {
        val trimmed: String = value.trim()
        return if (trimmed.startsWith(prefix)) trimmed else prefix + trimmed
    }

    private fun bare(id: String?, prefix: String): String? {
        val trimmed: String = id?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val stripped: String = if (trimmed.startsWith(prefix)) trimmed.substring(prefix.length) else trimmed
        return stripped.trim().ifEmpty { null }
    }
}
