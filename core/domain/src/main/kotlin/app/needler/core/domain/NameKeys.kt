package app.needler.core.domain

/**
 * The one way a name is case-folded before it is compared, sorted or bucketed.
 *
 * Four places had their own copy of the same two lines and their own private `\s+` regex: the
 * mirror's `*_normalised` sort columns, the relevance score behind REQUIREMENTS.md
 * "Search behaviour", the genre identity behind "Library browse", and the Artists index strip. They
 * all answer the same question - are these two strings the same name - and a name that folds one way
 * on write and another way on compare is a row that sorts somewhere the reader cannot find.
 *
 * Here rather than beside the schema in `:core:data` because `:feature:library` cannot reach that
 * module: its build file states the layering rule, that a feature "depends on :core:domain",
 * ":core:design", and "does NOT depend on :core:data or :core:network". `:core:domain` is the one
 * module all four already depend on, and it is where [app.needler.core.domain.model.Artist.sortName]
 * - the field the sorting and the strip both key off - is declared.
 *
 * Rejected: one `normalise` doing everything, with flags for the parts a caller does not want.
 * Genre identity must not strip a leading article - "A Cappella" is a real genre and "cappella" is a
 * bucket nobody typed - and a boolean parameter at the call site reads as a setting rather than as a
 * different question being asked. Two named functions instead, and a caller picks the question.
 */
public object NameKeys {

    /** Leading articles stripped for ordering, matching how music libraries are normally sorted. */
    private val LEADING_ARTICLES: List<String> = listOf("the ", "a ", "an ")

    /**
     * Trimmed, runs of whitespace collapsed to one space, lower-cased. Null folds to "".
     *
     * Punctuation is deliberately kept. "!!!" is a real artist, "...And Justice for All" a real
     * album and "R&B" a real genre; stripping their punctuation leaves nothing to file the first
     * under and turns the last into "rb".
     */
    public fun fold(value: String?): String =
        value?.trim()?.replace(WHITESPACE, " ")?.lowercase().orEmpty()

    /**
     * [fold], then one leading article removed: "The Marias" becomes "marias".
     *
     * One article, and the match needs the space after it, so "Theatre of Tragedy" keeps its T and
     * "The The" becomes "the". The empty-result guard returns the fold instead of "", so no name can
     * be reduced to nothing by being an article.
     */
    public fun sortKey(value: String?): String {
        val collapsed: String = fold(value)
        if (collapsed.isEmpty()) return ""
        for (article in LEADING_ARTICLES) {
            if (collapsed.startsWith(article)) {
                val stripped: String = collapsed.removePrefix(article).trim()
                return stripped.ifEmpty { collapsed }
            }
        }
        return collapsed
    }

    private val WHITESPACE: Regex = Regex("\\s+")
}
