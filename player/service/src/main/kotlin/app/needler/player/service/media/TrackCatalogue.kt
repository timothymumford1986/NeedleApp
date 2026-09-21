package app.needler.player.service.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.LibraryRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns domain [Track]s into `MediaItem`s and back, and remembers the tracks the session is holding.
 *
 * The session's own state is a list of `MediaItem`s, which carry a title and an artist but not a [Track]. The
 * controller needs the [Track] back - the crate screen renders it, the widgets render it, and the scrobbler
 * needs the fetch handle - so a small cache sits between the two rather than hitting Room on every timeline
 * callback. The mirror is the source of truth; this only avoids asking it the same question five times a
 * second.
 */
@OptIn(UnstableApi::class)
public class TrackCatalogue(
    private val libraryRepository: LibraryRepository,
) {

    private val known = ConcurrentHashMap<String, Track>()

    /** Remembers [tracks] so a later lookup by media id is free. */
    public fun remember(tracks: Collection<Track>) {
        tracks.forEach { track -> known[track.key.canonicalString] = track }
    }

    /** The track for a media id, from the cache alone. Null means it has to be looked up. */
    public fun cached(mediaId: String?): Track? {
        val key: TrackKey = MediaId.toTrackKey(mediaId) ?: return null
        return known[key.canonicalString]
    }

    /**
     * The tracks for a list of media ids, in order, dropping any the mirror does not know.
     *
     * Dropping rather than substituting a placeholder: a track that has left the library has nothing to play
     * and nothing honest to show, and a greyed row belongs to the album screen, which can explain why.
     */
    public suspend fun resolve(mediaIds: List<String>): List<Track> {
        val keys: List<TrackKey> = mediaIds.mapNotNull { MediaId.toTrackKey(it) }
        val missing: List<TrackKey> = keys.filter { !known.containsKey(it.canonicalString) }
        if (missing.isNotEmpty()) {
            remember(libraryRepository.getTracks(missing))
        }
        return keys.mapNotNull { known[it.canonicalString] }
    }

    /** One track, cache first. */
    public suspend fun resolveOne(mediaId: String?): Track? {
        val key: TrackKey = MediaId.toTrackKey(mediaId) ?: return null
        known[key.canonicalString]?.let { return it }
        val track: Track = libraryRepository.getTrack(key) ?: return null
        known[key.canonicalString] = track
        return track
    }

    /**
     * The `MediaItem` for one crate row.
     *
     * The URI is [TrackUri], never a stream URL: a stream URL carries the app-password, is bundled to every
     * controller of the session, and would freeze the cached-or-stream decision at enqueue time. See
     * [TrackUri] for the whole argument.
     */
    public fun mediaItemFor(track: Track, rowId: String): MediaItem = MediaItem.Builder()
        .setMediaId(rowId)
        .setUri(TrackUri.forTrack(track.key))
        .setMediaMetadata(metadataFor(track))
        .build()

    /**
     * Rebuilds the playable URI on an item that arrived from another process.
     *
     * `MediaItem.LocalConfiguration` is deliberately not bundled across the session boundary, so an item added
     * by Android Auto, Wear or the notification arrives with a media id and no URI at all. Without this the
     * session accepts the item and then cannot play it, which surfaces as a track that is in the crate and
     * silently skipped.
     */
    public fun withPlayableUri(item: MediaItem): MediaItem? {
        val key: TrackKey = MediaId.toTrackKey(item.mediaId) ?: return null
        return item.buildUpon()
            .setUri(TrackUri.forTrack(key))
            .build()
    }

    private fun metadataFor(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artistName)
        .setAlbumTitle(track.albumTitle)
        .setTrackNumber(track.key.trackNumber)
        .setDiscNumber(track.key.discNumber)
        .setDurationMs(track.durationMs)
        .setReleaseYear(track.year)
        .setGenre(track.genres.firstOrNull())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()
}
