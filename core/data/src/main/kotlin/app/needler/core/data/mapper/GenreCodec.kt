package app.needler.core.data.mapper

/**
 * How the denormalised `album.genres` column is encoded.
 *
 * Genres are stored pipe-delimited **with delimiters at both ends** - `|rock|post-punk|` - so an
 * exact match is `LIKE '%|rock|%'` rather than a substring match that would also hit `rockabilly`.
 * The column exists because screen 13 badges genres on list rows and loading tracks for that would
 * be absurd; it is a display denormalisation, not a second source of truth.
 *
 * ## One field, several genres
 *
 * The server joins several genres into one `genre` field with a semicolon:
 * `Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock`. That whole string used to be
 * stored and counted as one genre, which the device found three ways at once - the Genres screen
 * reported 138 genres, the list carried an unreadable row five names long, and an album tagged with
 * five genres appeared under none of them. REQUIREMENTS.md "Library browse" puts the Genres screen
 * on the mirror ("All library browsing reads the local metadata mirror"), so a wire value that names
 * five buckets has to become five entries here or it becomes five nowhere.
 *
 * [splitComposite] is therefore applied on the way in, by [encode], and again on the way out, by
 * [decode] - which is what lets a column written before this fix read correctly without waiting for
 * the row to be re-synced. `NeedlerMigrations.MIGRATION_3_4` rewrites those columns so the `LIKE`
 * queries agree with the list; see its own note for why re-ingestion alone was not enough.
 *
 * ## Why only the semicolon
 *
 * `;` is the only separator split on, and the two obvious candidates beside it are rejected
 * deliberately, because on this server both characters appear **inside** single genre names:
 *
 *  * **`/`** - `MediaId` and `BrowseNode` both carry a comment about round-tripping a genre called
 *    "Hip-Hop/Rap", and `GenresRoute` encodes the route argument for "Rock/Pop". Splitting on the
 *    slash would turn one real bucket into two fictional ones.
 *  * **`,`** - "Folk, World, & Country" is a real genre and the comma is also how a sort name or a
 *    group name is punctuated, so splitting on it is how "Crosby, Stills & Nash" becomes three
 *    genres on a server that files such a string under `genre` at all.
 *
 * A separator is added here only on evidence that the server uses it as one, never on the grounds
 * that it could be.
 *
 * ## Case and whitespace
 *
 * [fold] is the key two genre names are compared on. It exists because "Indie Rock" and
 * "indie rock" are one genre and arrive as two strings, and the server has both: the legacy `genre`
 * field is whatever the tagger wrote, while the `genres` list comes from the server's own index.
 * The same folding is `UnifiedSearchUseCase.nameKey`, for the same reason and with the same refusal
 * to strip punctuation - "!!!" is a real name and an R&B bucket must not become "rb".
 *
 * Folding decides **identity**, never what is drawn: the first spelling encountered wins the display
 * form. Rejected: title-casing the fold to get a canonical label, which would render "IDM" as "Idm"
 * and "R&B" as "R&b" - a placeholder dressed as data, where the server's own spelling is at least
 * something a human typed.
 */
public object GenreCodec {

    public const val DELIMITER: String = "|"

    /**
     * The one character the server joins several genres into one field with.
     *
     * Named rather than inlined because [encode], [decode] and the migration that rewrites existing
     * columns all have to agree about it, and three literals is how they come to disagree.
     */
    public const val COMPOSITE_SEPARATOR: String = ";"

    /**
     * One wire genre value as the genres it actually names: split, trimmed, blanks dropped.
     *
     * Blanks are dropped rather than preserved because a trailing separator - `Rock;` - is common
     * enough in tag data to be worth not turning into an unnamed bucket, and a bucket with no name
     * is unreachable: the route, the `LIKE` pattern and the row label are all the name.
     */
    public fun splitComposite(raw: String?): List<String> {
        val value: String = raw.orEmpty()
        if (value.isEmpty()) return emptyList()
        return value
            .split(COMPOSITE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * The key two genre names are compared on: case-folded, with runs of whitespace collapsed.
     *
     * Punctuation is kept, for the reason this file's header gives.
     */
    public fun fold(genre: String): String =
        genre.trim().replace(WHITESPACE, " ").lowercase()

    /**
     * Encodes for storage. Returns null for an empty list so the column stays NULL rather than `||`.
     *
     * Every value is put through [splitComposite] first, so a caller may hand this whatever the
     * server sent - the single `genre` string, the `genres` array, or both appended - without
     * knowing which of them is composite. De-duplication is by [fold] rather than by string, so an
     * album the server tags "Rock" in one field and "rock" in the other is stored once.
     */
    public fun encode(genres: List<String>): String? {
        val cleaned: List<String> = genres
            .flatMap { splitComposite(it) }
            .distinctBy { fold(it) }
        if (cleaned.isEmpty()) return null
        return DELIMITER + cleaned.joinToString(DELIMITER) + DELIMITER
    }

    /**
     * Reads a stored column back.
     *
     * Splits on the composite separator as well as the delimiter, so a row written before genres
     * were split on ingest still decodes to the genres it names. Without that, every screen reading
     * the mirror would stay wrong until the album happened to be re-synced - and a delta sync only
     * rewrites albums the server reports as changed, so for an unchanged album that is never.
     */
    public fun decode(stored: String?): List<String> {
        val raw: String = stored?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw
            .split(DELIMITER, COMPOSITE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { fold(it) }
    }

    /** The `LIKE` pattern that matches exactly one genre inside an encoded column. */
    public fun likePattern(genre: String): String = "%" + DELIMITER + genre.trim() + DELIMITER + "%"

    private val WHITESPACE: Regex = Regex("\\s+")
}
