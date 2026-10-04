package app.needler.feature.library.library

import app.needler.core.domain.model.Artist
import java.text.Normalizer

/**
 * The alphabet jump for the Artists tab: which letters the library has, and where each one starts.
 *
 * REQUIREMENTS.md "Library browse" specifies the Artists screen as `getArtists`, "Alphabetical, with
 * index jump". The alphabetical half shipped; the jump did not, so reaching "D" in a 289-album
 * library meant scrolling the whole way. The data layer was already built for it -
 * `ArtistDao.observeArtists` is documented as "Alphabetical, with the index-jump letter derivable
 * from `sort_name_normalised`" and `SortKeys.indexLetter` exists for exactly this - and nothing read
 * either.
 *
 * ## Why the letter is derived here and not read from `SortKeys`
 *
 * `SortKeys.indexLetter` in `:core:data` is the canonical copy and it sits next to the schema,
 * where the contents of an indexed column belong. `:feature:library` cannot
 * reach it: the module's own build file states the layering rule - a feature "depends on
 * :core:domain", ":core:design", and "does NOT depend on :core:data or :core:network" - and an
 * index strip is not a reason to breach it. So the fold is repeated here, and the handover note is
 * that the shared home for it is `:core:domain` beside [Artist.sortName], which is the field the
 * whole mechanism keys off.
 *
 * The normalisation is repeated too, deliberately, even though [Artist.sortName] arrives from the
 * mirror already normalised. It has to be: `sortName` **defaults to `name`**, so an `Artist`
 * constructed anywhere other than `EntityMappers` carries a raw display name - "The Marias" rather
 * than "marias" - and bucketing that under T would file a third of a library under one letter. The
 * fold is idempotent over an already-normalised value, so doing it twice costs nothing and doing it
 * once in the wrong place costs the whole feature.
 *
 * ## Accented and non-Latin names
 *
 * A leading accented letter is folded to its base letter, so "Ólafur Arnalds" files under O and
 * "Björk" under B. That is NFD decomposition with the combining marks dropped, which handles the
 * accents a Latin music library actually contains. It does **not** handle the letters that do not
 * decompose - "Ø", "Æ", "Ł" - and those land in [OTHER] along with digits, punctuation and every
 * non-Latin script. Enumerating them by hand was rejected: the list is open-ended, every entry is a
 * guess about which base letter a reader expects, and getting one wrong files an artist under a
 * letter they are not at.
 *
 * ## Why [OTHER] comes first, and the one thing it cannot do
 *
 * `#` is the first chip, not the last, because the list it indexes puts those names first: the
 * mirror orders on `sort_name_normalised`, which is an ordinary string comparison, so "!!!", "2Pac"
 * and "65daysofstatic" sort before "a". An index strip whose order disagrees with the list's order
 * is worse than no strip.
 *
 * The consequence is that `#` is one bucket spanning two ends of the list. Non-Latin names sort
 * *after* "z" under the same comparison, so a library holding both "!!!" and "東京事変" has a `#`
 * chip that jumps to the former and leaves the latter adjacent to Z - reachable by scrolling past
 * the end of the alphabet, or by the search field this screen already draws above the tabs, but not
 * by tapping `#`. **The rejected alternative** is a chip per script present. It would put a glyph on
 * the strip that a reader who does not read that script cannot use, and it would turn a fixed
 * 27-chip control into one whose width depends on the library - at which point the strip stops being
 * an alphabet and becomes a second list to scan.
 */
internal object ArtistIndex {

    /** The bucket for everything that does not fold to A-Z. */
    const val OTHER: String = "#"

    /** The twenty-six, in order. */
    val LETTERS: List<String> = ('A'..'Z').map(Char::toString)

    /**
     * The strip the screen draws: `#` when anything is in it, then A to Z.
     *
     * Every letter is present whether or not the library has one, because the strip is read as an
     * alphabet. A letter quietly missing from the middle of one reads as a rendering fault, and -
     * worse - it shifts the position of every letter after it, so the strip a user has learned
     * rearranges itself the first time a sync adds the library's first D. An empty letter is
     * [ArtistIndexEntry.isEmpty] instead, which the strip draws and speaks as having nothing in it.
     *
     * `#` is the exception and is omitted when empty: it is a catch-all rather than a member of a
     * sequence, so there is no expectation to meet and no position to hold steady.
     */
    fun entriesFor(artists: List<Artist>): List<ArtistIndexEntry> {
        val firstIndexByBucket: Map<String, Int> = firstIndexByBucket(artists)
        return buildList(LETTERS.size + 1) {
            firstIndexByBucket[OTHER]?.let { index ->
                add(ArtistIndexEntry(label = OTHER, firstArtistIndex = index))
            }
            LETTERS.forEach { letter ->
                add(ArtistIndexEntry(label = letter, firstArtistIndex = firstIndexByBucket[letter]))
            }
        }
    }

    /**
     * Which bucket one artist belongs in.
     *
     * Reads [Artist.sortName] and not [Artist.name], because the ordering of the list this indexes
     * is the ordering of that field: "The Marias" is drawn under T and sorted under M, and a strip
     * that disagreed with the list would scroll to the wrong place by a third of the alphabet.
     */
    fun bucketOf(artist: Artist): String {
        val normalised: String = normalise(artist.sortName)
        val first: Char = normalised.firstOrNull() ?: return OTHER
        val folded: Char = fold(first).uppercaseChar()
        return if (folded in 'A'..'Z') folded.toString() else OTHER
    }

    /** Where each bucket present in [artists] starts, by the position of its first member. */
    private fun firstIndexByBucket(artists: List<Artist>): Map<String, Int> {
        val first: MutableMap<String, Int> = LinkedHashMap()
        artists.forEachIndexed { index, artist ->
            first.putIfAbsent(bucketOf(artist), index)
        }
        return first
    }

    /**
     * Lower-cases, collapses whitespace and strips one leading article.
     *
     * A deliberate copy of `SortKeys.normalise`, kept behaviourally identical including its refusal
     * to strip punctuation - "!!!" is a real artist and stripping its name leaves nothing to file it
     * under. See this object's own notes for why it is a copy.
     */
    private fun normalise(value: String): String {
        val collapsed: String = value.trim().replace(WHITESPACE, " ").lowercase()
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
     * One character with its accents removed, or the character itself when it has none to remove.
     *
     * NFD splits a precomposed letter into its base plus combining marks; dropping the marks leaves
     * the base. A character that does not decompose comes back unchanged, which is how "Ø" ends up
     * in [OTHER] rather than silently under O.
     */
    private fun fold(value: Char): Char {
        val decomposed: String = Normalizer.normalize(value.toString(), Normalizer.Form.NFD)
        return decomposed.firstOrNull { character ->
            character.category != CharCategory.NON_SPACING_MARK
        } ?: value
    }

    private val LEADING_ARTICLES: List<String> = listOf("the ", "a ", "an ")

    private val WHITESPACE: Regex = Regex("\\s+")
}

/**
 * One chip of the index strip.
 *
 * @param firstArtistIndex where the list should scroll to, or null when the library has nobody under
 *   this letter. The index is a position in the artist list the strip was built from, so a list that
 *   changes rebuilds the strip rather than letting a stale index scroll somewhere arbitrary.
 */
internal data class ArtistIndexEntry(
    val label: String,
    val firstArtistIndex: Int?,
) {
    /** True when there is nothing to jump to, which the strip draws and speaks rather than hides. */
    val isEmpty: Boolean get() = firstArtistIndex == null

    /**
     * What TalkBack announces.
     *
     * The visible label is a single character, which is no description at all on its own - "A" tells
     * a screen-reader user neither that it is a control nor what it does. REQUIREMENTS.md
     * "Accessibility" requires a content description on every control, and the empty case needs its
     * reason spoken as well: the chip is drawn in the muted colour when there is nothing under it,
     * and colour cannot be the only carrier of a state.
     */
    val spokenLabel: String
        get() = when {
            label == ArtistIndex.OTHER && isEmpty -> "No artists whose name does not start with a letter"
            label == ArtistIndex.OTHER -> "Jump to artists whose name does not start with a letter"
            isEmpty -> "No artists under " + label
            else -> "Jump to " + label
        }
}
