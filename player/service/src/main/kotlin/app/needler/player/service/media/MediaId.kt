package app.needler.player.service.media

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
 * ## Two forms, and why
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
 */
public object MediaId {

    /** Prefix on every browse node, so a browse id can never be mistaken for a playable track. */
    public const val BROWSE_PREFIX: String = "needler:"

    /** The Android Auto browse root. The tree under it is not built yet; see the service KDoc. */
    public const val BROWSE_ROOT: String = BROWSE_PREFIX + "root"

    /** Separates a crate row's sequence from the track it holds. */
    public const val ROW_SEPARATOR: Char = '@'

    /** The id for a track, independent of any crate row it happens to occupy. */
    public fun forTrack(key: TrackKey): String = key.canonicalString

    /** The id for one row of the crate. [sequence] is unique for the lifetime of the session. */
    public fun forQueueRow(key: TrackKey, sequence: Long): String =
        sequence.toString() + ROW_SEPARATOR + key.canonicalString

    /**
     * Decodes a mediaId in either form, or returns null when it is not one of ours.
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
}
