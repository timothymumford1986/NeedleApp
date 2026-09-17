package app.needler.core.data.local

/**
 * Turns what the user typed into an FTS4 MATCH expression.
 *
 * Raw user text must never be passed to `MATCH`. FTS4's match syntax treats `"`, `*`, `-`, `^`,
 * `:`, `(`, `)`, `OR`, `NOT` and `NEAR` as operators, so an apostrophe or a stray quote in an album
 * title turns a search into a syntax error - and a syntax error inside a Flow the search screen is
 * collecting surfaces as an empty library, which reads as data loss.
 *
 * The strategy is the one search-as-you-type needs: quote every complete term so its punctuation is
 * inert, and make the final term a prefix match so results narrow while the user is still typing.
 * `album_fts` and `track_fts` are built with `prefix = [2, 3]`, so a two- or three-character prefix
 * is answered from the prefix index rather than by scanning terms.
 */
public object FtsQuery {

    /**
     * Builds a MATCH expression for [input], or null when there is nothing searchable in it.
     *
     * Null means "do not run the query": an empty MATCH is an error in SQLite, not a match-all.
     */
    public fun forPrefixSearch(input: String?): String? {
        val terms: List<String> = tokenise(input)
        if (terms.isEmpty()) return null
        return terms.mapIndexed { index, term ->
            val quoted: String = quote(term)
            // Only the last term is a prefix: the earlier ones are complete words the user has
            // finished typing, and prefix-matching them all would match far too much.
            if (index == terms.lastIndex) quoted + "*" else quoted
        }.joinToString(separator = " ")
    }

    /** Builds a MATCH expression that requires whole words only, for an explicit "search" action. */
    public fun forExactSearch(input: String?): String? {
        val terms: List<String> = tokenise(input)
        if (terms.isEmpty()) return null
        return terms.joinToString(separator = " ") { quote(it) }
    }

    /**
     * Splits on anything that is not a letter, digit or apostrophe. FTS4's unicode61 tokeniser
     * would split the same way, so nothing searchable is lost, and the pieces that remain contain
     * no match operators at all.
     */
    private fun tokenise(input: String?): List<String> =
        input?.split(NON_TERM)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** Wraps a term in double quotes, doubling any quote it contains, as SQLite expects. */
    private fun quote(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""

    private val NON_TERM: Regex = Regex("[^\\p{L}\\p{N}']+")
}
