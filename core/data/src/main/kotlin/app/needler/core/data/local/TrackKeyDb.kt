package app.needler.core.data.local

import androidx.room.ColumnInfo

/**
 * The stable identity of a track as the database stores it: release group, disc, track number.
 *
 * Annotated so it can be `@Embedded` in every DAO projection that returns a track key, which keeps
 * the three column names in one place. It is otherwise a plain data class with no behaviour beyond
 * its own rendering, so the cache and staleness logic that uses it is unit-testable on the JVM.
 * `:core:domain` has its own `TrackKey` built from value classes; converting between the two
 * belongs to the mapper wave, not here.
 *
 * There is no constructor taking a `file_id`, and there never should be: a `file_id` identifies
 * whatever bytes currently sit behind a track, not the track itself.
 */
public data class TrackKeyDb(
    @ColumnInfo(name = "release_group_mbid")
    val releaseGroupMbid: String,
    @ColumnInfo(name = "disc_no")
    val discNo: Int,
    @ColumnInfo(name = "track_no")
    val trackNo: Int,
) {
    /**
     * Stable rendering used wherever a single string is unavoidable - the `favourite.entity_id`
     * column, `write_queue.entity_key`, and cache file names.
     */
    public val canonical: String
        get() = releaseGroupMbid + SEPARATOR + discNo + SEPARATOR + trackNo

    override fun toString(): String = canonical

    public companion object {
        public const val SEPARATOR: String = "/"

        /** Parses [canonical]. Returns null for anything that is not `<mbid>/<disc>/<track>`. */
        public fun parse(value: String?): TrackKeyDb? {
            if (value.isNullOrBlank()) return null
            val parts: List<String> = value.split(SEPARATOR)
            if (parts.size != 3) return null
            val mbid: String = parts[0]
            val disc: Int = parts[1].toIntOrNull() ?: return null
            val track: Int = parts[2].toIntOrNull() ?: return null
            if (mbid.isBlank()) return null
            return TrackKeyDb(releaseGroupMbid = mbid, discNo = disc, trackNo = track)
        }
    }
}
