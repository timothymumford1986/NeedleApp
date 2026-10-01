package app.needler.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.needler.core.data.settings.StreamQuality

/**
 * One per-item stream-quality override: "this record, at this rung, wherever I am".
 *
 * The mode defaults - the Wi-Fi and Data rungs - answer "what do I usually want on this connection".
 * This table answers "this record is different", and it is the only place that can: a preference
 * attached to one album has nowhere to live in `DataStore`, which holds settings and not a row per
 * item.
 *
 * ## Why it is a table and not a column on `album`
 *
 * `album` is the metadata mirror, and the mirror is the server's copy. Sync overwrites it - that is
 * the whole point of it - and a user preference sitting in a synced row is a preference waiting to be
 * overwritten. The same reasoning put the staleness fingerprint on `audio_cache` rather than on
 * `track`: see REQUIREMENTS.md "Invalidating upgraded files", where comparing the mirror against
 * itself is exactly the bug that rule exists to prevent. An override also has to be settable for an
 * album the mirror has not fetched yet, which a column on a row that does not exist cannot be.
 *
 * ## No foreign key, and no deletion with the album
 *
 * Deliberately unconstrained, like `pin` and `audio_cache`. A re-import that rewrites or briefly
 * removes an album row must not throw away the user's choice about how to stream that record. An
 * override for an album this server does not hold is inert - nothing resolves it - and costs one row.
 *
 * ## It survives a download, and a server change
 *
 * A local copy always wins, so while an album is downloaded its override decides nothing. It is
 * **kept** rather than cleared: freeing disk space should not silently revoke a preference the user
 * never withdrew, and the dimmed `Server:` tag on the album screen keeps it visible so it is not
 * hidden state.
 *
 * It also survives `clearForServerChange`, unlike the mirror and the pins. REQUIREMENTS.md draws that
 * line already - "quality, EQ, crossfade and notification preferences are properties of the device
 * and the person, not of the server" - and this is a quality preference keyed on a release-group
 * MBID, which is global where a `file_id` is not. The same record on the new server is the same
 * record.
 */
@Entity(
    tableName = "stream_override",
    primaryKeys = ["scope", "item_id"],
)
public data class StreamOverrideEntity(

    @ColumnInfo(name = "scope")
    val scope: StreamOverrideScopeDb,

    /**
     * `TrackKey.canonicalString` (`<mbid>/<disc>/<track>`) for a track, the release-group MBID for an
     * album.
     *
     * One column for both because the pair (scope, id) is the identity and nothing ever queries
     * across scopes. Two nullable columns would allow a row that names neither.
     */
    @ColumnInfo(name = "item_id")
    val itemId: String,

    /**
     * The ceiling this item streams at, on every connection.
     *
     * Stored as [StreamQuality]'s frozen `storageValue`, the same string the two mode rungs use in
     * `DataStore`, so there is one ladder of persisted names rather than two that could drift.
     */
    @ColumnInfo(name = "rung")
    val rung: StreamQuality,

    /** Epoch milliseconds the override was last set. For a future "recently overridden" list. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
