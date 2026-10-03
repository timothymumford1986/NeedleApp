package app.needler.player.service.media

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.TrackKey

/**
 * The one translation between a [TrackKey] and the `mediaId` string that Media3, the lock screen,
 * Android Auto and Wear all pass around.
 *
 * Media3 identifies a track by a `String`, so something has to encode the three-part key. It is the
 * canonical key and never the `file_id`, for the same reason the audio store is keyed that way: a
 * quality upgrade moves the `file_id` while the record, disc and track stay exactly where they were, and
 * a session holding file ids would lose its place the moment the server replaced a file.
 *
 * ## Three forms, and why
 *
 * A *track* id is `<release-group mbid>/<disc>/<track>` - [TrackKey.canonicalString], readable in a log
 * line, and what a browse result or a voice search resolves to.
 *
 * A *crate row* id is `<sequence>@<track id>`. The crate can legitimately hold the same track twice -
 * add-to-crate twice, or a playlist that repeats a track - and `PlaybackController` addresses rows by
 * `QueueItem.id` for both remove and skip-to. With the bare track id as the row id, removing the second
 * copy would remove the first, and the user would watch the wrong row disappear. The sequence makes each
 * row distinct, and it rides on the mediaId rather than in a tag because a tag does not survive the
 * process boundary between the session and a controller in another app.
 *
 * A *browse node* id is [BROWSE_PREFIX] followed by the node's own name, and for the four nodes that name
 * one thing, that thing's identity: `needler:album/<release-group mbid>`, `needler:artist/<artist mbid>`,
 * `needler:playlist/<playlist id>`, `needler:genre/<name>`. Identities are stored bare, without the
 * Subsonic `al-`, `ar-` or `pl-` prefix, exactly as REQUIREMENTS.md "Identity model" requires of everything
 * the app persists.
 *
 * The prefix is what keeps the three spaces apart: [toTrackKey] rejects anything carrying it, so a browse
 * id can never be mistaken for something playable and a browse node can never be enqueued by accident. The
 * parameterised ids are parsed by prefix and tail rather than split on `/`, so an identity that contains a
 * slash - a genre called "Hip-Hop/Rap" - round-trips without an escaping scheme nobody would remember.
 */
public object MediaId {

    /** Prefix on every browse node, so a browse id can never be mistaken for a playable track. */
    public const val BROWSE_PREFIX: String = "needler:"

    /** The Android Auto browse root. Its children are the five nodes REQUIREMENTS.md "Android Auto" names. */
    public const val BROWSE_ROOT: String = BROWSE_PREFIX + "root"

    /** "Library": albums, artists, songs and genres. */
    public const val BROWSE_LIBRARY: String = BROWSE_PREFIX + "library"

    /** Every owned album. */
    public const val BROWSE_ALBUMS: String = BROWSE_PREFIX + "albums"

    /** Every artist with owned music. */
    public const val BROWSE_ARTISTS: String = BROWSE_PREFIX + "artists"

    /** Every owned track. */
    public const val BROWSE_SONGS: String = BROWSE_PREFIX + "songs"

    /** The mirror's genre buckets. */
    public const val BROWSE_GENRES: String = BROWSE_PREFIX + "genres"

    /** "Recently added", newest arrival first. */
    public const val BROWSE_RECENTLY_ADDED: String = BROWSE_PREFIX + "recently-added"

    /** The user's playlists. */
    public const val BROWSE_PLAYLISTS: String = BROWSE_PREFIX + "playlists"

    /** Starred albums, artists and songs. */
    public const val BROWSE_FAVOURITES: String = BROWSE_PREFIX + "favourites"

    /** Downloaded albums: REQUIREMENTS.md "Vocabulary" fixes the word "Device" for this tier. */
    public const val BROWSE_ON_DEVICE: String = BROWSE_PREFIX + "on-device"

    /** Separates a crate row's sequence from the track it holds. */
    public const val ROW_SEPARATOR: Char = '@'

    /** The id for a track, independent of any crate row it happens to occupy. */
    public fun forTrack(key: TrackKey): String = key.canonicalString

    /** The id for one row of the crate. [sequence] is unique for the lifetime of the session. */
    public fun forQueueRow(key: TrackKey, sequence: Long): String =
        sequence.toString() + ROW_SEPARATOR + key.canonicalString

    /** The browse id for one album. Carries the release-group MBID bare, without the Subsonic prefix. */
    public fun forAlbum(mbid: ReleaseGroupMbid): String = ALBUM_PREFIX + mbid.value

    /** The browse id for one artist. */
    public fun forArtist(mbid: ArtistMbid): String = ARTIST_PREFIX + mbid.value

    /** The browse id for one playlist. */
    public fun forPlaylist(id: PlaylistId): String = PLAYLIST_PREFIX + id.value

    /** The browse id for one genre, carrying the genre name as the mirror stores it. */
    public fun forGenre(name: String): String = GENRE_PREFIX + name

    /**
     * Decodes a mediaId in either playable form, or returns null when it is not one of ours.
     *
     * Null rather than throwing: a mediaId can arrive from another process - a resumed platform session,
     * a stale Auto tab, a Wear app built against an older version - and an unparseable one is a row to
     * drop, not a crash in the playback service.
     */
    public fun toTrackKey(mediaId: String?): TrackKey? {
        if (mediaId == null) return null
        if (mediaId.startsWith(BROWSE_PREFIX)) return null
        val trackPart: String = mediaId.substringAfterLast(ROW_SEPARATOR)
        val parts: List<String> = trackPart.split('/')
        if (parts.size != 3) return null
        val mbid: String = parts[0].trim()
        if (mbid.isEmpty()) return null
        val disc: Int = parts[1].trim().toIntOrNull() ?: return null
        val track: Int = parts[2].trim().toIntOrNull() ?: return null
        if (disc < 1 || track < 1) return null
        return TrackKey(ReleaseGroupMbid(mbid), discNumber = disc, trackNumber = track)
    }

    /** The row sequence, or null when [mediaId] is a bare track id or not one of ours. */
    public fun rowSequenceOf(mediaId: String?): Long? {
        if (mediaId == null || !mediaId.contains(ROW_SEPARATOR)) return null
        return mediaId.substringBeforeLast(ROW_SEPARATOR).toLongOrNull()
    }

    /** True when [mediaId] names a browse node rather than a track. */
    public fun isBrowseId(mediaId: String?): Boolean = mediaId != null && mediaId.startsWith(BROWSE_PREFIX)

    /**
     * Decodes a browse id into the node it names, or null when it names none.
     *
     * Null covers two cases that must behave the same way: a playable id, and a browse id from a version of
     * the tree this build does not have. Auto keeps ids across app updates - a tab it had open, a shortcut a
     * user pinned - so an unknown node is a normal event and the answer to it is an empty list, never an
     * error dialog in a car.
     */
    public fun toBrowseNode(mediaId: String?): BrowseNode? {
        if (mediaId == null || !mediaId.startsWith(BROWSE_PREFIX)) return null
        when (mediaId) {
            BROWSE_ROOT -> return BrowseNode.Root
            BROWSE_LIBRARY -> return BrowseNode.Library
            BROWSE_ALBUMS -> return BrowseNode.Albums
            BROWSE_ARTISTS -> return BrowseNode.Artists
            BROWSE_SONGS -> return BrowseNode.Songs
            BROWSE_GENRES -> return BrowseNode.Genres
            BROWSE_RECENTLY_ADDED -> return BrowseNode.RecentlyAdded
            BROWSE_PLAYLISTS -> return BrowseNode.Playlists
            BROWSE_FAVOURITES -> return BrowseNode.Favourites
            BROWSE_ON_DEVICE -> return BrowseNode.OnDevice
        }
        tailOf(mediaId, ALBUM_PREFIX)?.let { return BrowseNode.OneAlbum(ReleaseGroupMbid(it)) }
        tailOf(mediaId, ARTIST_PREFIX)?.let { return BrowseNode.OneArtist(ArtistMbid(it)) }
        tailOf(mediaId, PLAYLIST_PREFIX)?.let { return BrowseNode.OnePlaylist(PlaylistId(it)) }
        tailOf(mediaId, GENRE_PREFIX)?.let { return BrowseNode.OneGenre(it) }
        return null
    }

    /**
     * The identity carried by a parameterised browse id, or null when [mediaId] is not that kind of id or
     * carries nothing after the prefix.
     *
     * The whole tail is taken rather than the next segment, because the tail *is* the identity: an MBID has
     * no slash in it, and a genre name may.
     */
    private fun tailOf(mediaId: String, prefix: String): String? {
        if (!mediaId.startsWith(prefix)) return null
        return mediaId.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    }

    private const val ALBUM_PREFIX: String = BROWSE_PREFIX + "album/"
    private const val ARTIST_PREFIX: String = BROWSE_PREFIX + "artist/"
    private const val PLAYLIST_PREFIX: String = BROWSE_PREFIX + "playlist/"
    private const val GENRE_PREFIX: String = BROWSE_PREFIX + "genre/"
}
