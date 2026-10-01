package app.needler.player.service.media

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid

/**
 * One node of the tree Android Auto walks, decoded from a browse media id.
 *
 * REQUIREMENTS.md "Android Auto" fixes the shape and this type enumerates it: "The browse tree mirrors the
 * app: Library with albums, artists and songs; Recently added; Playlists; Favourites; On device." Each of
 * those is a node, and so is each thing one of them opens - one album, one artist, one playlist, one genre.
 *
 * ## Why a type rather than the id string
 *
 * `onGetChildren` is handed a `parentId` and nothing else, so every branch of the tree is decided from that
 * one string. Deciding it with `startsWith` at the call site would put the grammar of the id in [MediaId] and
 * its meaning somewhere else, and the two would drift the first time a level was added. Decoding once, into
 * this type, gives the tree an exhaustive `when` and gives a stale id - an Auto tab left open across an app
 * update, a voice shortcut recorded against a level that has since moved - a single honest answer: null, and
 * an empty list rather than the wrong list.
 *
 * Every node id is prefixed [MediaId.BROWSE_PREFIX], which is what keeps a browse id out of
 * [MediaId.toTrackKey]. That invariant is the reason the two id spaces can share one `mediaId` field at all.
 */
public sealed interface BrowseNode {

    /** The root Auto connects to. Its children are the five top-level nodes the requirement names. */
    public data object Root : BrowseNode

    /** "Library": albums, artists, songs and genres, as the app's own library tab has them. */
    public data object Library : BrowseNode

    /** Every owned album, alphabetical by title. */
    public data object Albums : BrowseNode

    /** Every artist with at least one owned album, alphabetical by sort name. */
    public data object Artists : BrowseNode

    /** Every owned track, alphabetical by song title. */
    public data object Songs : BrowseNode

    /** The genre buckets of the mirror, alphabetical. */
    public data object Genres : BrowseNode

    /** Owned albums, newest arrival first. */
    public data object RecentlyAdded : BrowseNode

    /** The user's playlists, alphabetical. */
    public data object Playlists : BrowseNode

    /** Starred albums, artists and songs in one list. */
    public data object Favourites : BrowseNode

    /**
     * Downloaded albums, which play with no network at all.
     *
     * "On device" is the product's fixed word for this (REQUIREMENTS.md "Vocabulary"), and in a car it is the
     * node that matters most: a tunnel, a car park or a rural road is the normal condition, not the
     * exception.
     */
    public data object OnDevice : BrowseNode

    /** One album's tracks, in disc then track order. */
    public data class OneAlbum(val mbid: ReleaseGroupMbid) : BrowseNode

    /**
     * One artist's **owned** albums.
     *
     * Deliberately not the discography `LibraryRepository.observeArtistDiscography` composes: that reaches
     * the catalogue lane over the network, and REQUIREMENTS.md "Android Auto" restricts Auto to owned music
     * because "pulling while driving makes no sense". An un-owned album in a car is a row that cannot play.
     */
    public data class OneArtist(val mbid: ArtistMbid) : BrowseNode

    /** One playlist's entries, in playlist order. */
    public data class OnePlaylist(val id: PlaylistId) : BrowseNode

    /**
     * One genre's tracks.
     *
     * Carries the genre **name**, not the Subsonic `ge-` slug: the mirror keys genres by name and
     * `LibraryRepository.observeTracksByGenre` takes the name, so the slug would have to be translated back
     * at every use. The name rides in the last segment of the id verbatim, which is why the id is parsed by
     * prefix and tail rather than split on a separator - a genre called "Hip-Hop/Rap" survives the trip.
     */
    public data class OneGenre(val name: String) : BrowseNode
}
