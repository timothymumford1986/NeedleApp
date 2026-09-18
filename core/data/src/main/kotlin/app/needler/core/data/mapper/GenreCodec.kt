package app.needler.core.data.mapper

/**
 * How the denormalised `album.genres` column is encoded.
 *
 * Genres are stored pipe-delimited **with delimiters at both ends** - `|rock|post-punk|` - so an
 * exact match is `LIKE '%|rock|%'` rather than a substring match that would also hit `rockabilly`.
 * The column exists because screen 13 badges genres on list rows and loading tracks for that would
 * be absurd; it is a display denormalisation, not a second source of truth.
 */
public object GenreCodec {

    public const val DELIMITER: String = "|"

    /** Encodes for storage. Returns null for an empty list so the column stays NULL rather than `||`. */
    public fun encode(genres: List<String>): String? {
        val cleaned: List<String> = genres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (cleaned.isEmpty()) return null
        return DELIMITER + cleaned.joinToString(DELIMITER) + DELIMITER
    }

    public fun decode(stored: String?): List<String> {
        val raw: String = stored?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The `LIKE` pattern that matches exactly one genre inside an encoded column. */
    public fun likePattern(genre: String): String = "%" + DELIMITER + genre.trim() + DELIMITER + "%"
}
