package app.needler.core.data.local

import app.needler.core.domain.NameKeys

/**
 * How the `*_normalised` sort columns are built.
 *
 * Those columns exist because SQLite will not use an index to satisfy
 * `ORDER BY title COLLATE NOCASE`, and Room's `@Index` cannot declare a collation. Normalising once
 * on write gives the album grid and the artist list an index-ordered scan, which is what the
 * "no dropped frames on a 5,000-album grid" budget needs.
 *
 * Named here, next to the schema, rather than in the sync module: the contents of an indexed column
 * are part of the storage contract, and two call sites normalising slightly differently would
 * scatter the ordering in ways no test would notice. The fold itself is
 * [app.needler.core.domain.NameKeys], because the Artists index strip in `:feature:library` has to
 * agree with these columns and cannot depend on `:core:data` to do it.
 */
public object SortKeys {

    /**
     * Lower-cases, collapses whitespace and strips a leading article.
     *
     * Deliberately does not strip punctuation: "!!!" and "...And Justice for All" are real artist
     * and album names, and removing their punctuation would sort them under nothing.
     */
    public fun normalise(value: String?): String = NameKeys.sortKey(value)
}
