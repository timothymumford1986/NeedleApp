package app.needler.core.data.local

/**
 * How the `*_normalised` sort columns are built.
 *
 * Those columns exist because SQLite will not use an index to satisfy
 * `ORDER BY title COLLATE NOCASE`, and Room's `@Index` cannot declare a collation. Normalising once
 * on write gives the album grid and the artist list an index-ordered scan, which is what the
 * "no dropped frames on a 5,000-album grid" budget needs.
 *
 * Kept here, next to the schema, rather than in the sync module: the contents of an indexed column
 * are part of the storage contract, and two call sites normalising slightly differently would
 * scatter the ordering in ways no test would notice.
 */
public object SortKeys {

    /** Leading articles stripped for ordering, matching how music libraries are normally sorted. */
    private val LEADING_ARTICLES: List<String> = listOf("the ", "a ", "an ")

    /**
     * Lower-cases, collapses whitespace and strips a leading article.
     *
     * Deliberately does not strip punctuation: "!!!" and "...And Justice for All" are real artist
     * and album names, and removing their punctuation would sort them under nothing.
     */
    public fun normalise(value: String?): String {
        val collapsed: String = value
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.lowercase()
            .orEmpty()
        if (collapsed.isEmpty()) return ""
        for (article in LEADING_ARTICLES) {
            if (collapsed.startsWith(article)) {
                val stripped: String = collapsed.removePrefix(article).trim()
                return stripped.ifEmpty { collapsed }
            }
        }
        return collapsed
    }

    /**
     * The index-jump letter for the Artists screen: the first character of the normalised name,
     * upper-cased, or "#" for anything that does not start with a letter.
     */
    public fun indexLetter(normalised: String): String {
        val first: Char = normalised.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    private val WHITESPACE: Regex = Regex("\\s+")
}
