package app.needler.feature.search.search

import app.needler.core.domain.model.SearchBucket

/**
 * How far the "show all" path has walked one catalogue bucket.
 *
 * REQUIREMENTS.md "Search behaviour", rule 5: "Paginate a single bucket through
 * `GET /api/v1/search/{artists|albums}` with `limit` and `offset`." The repository
 * call for that has existed since the data layer was written and nothing reached
 * it, because neither screen 03 nor screen 10 draws a "more results" affordance —
 * so the mechanism existed with no way in. These few fields are the way in.
 *
 * ## Why the offset walks the bucket from the top
 *
 * A bucket page is a page of the *server's* ordered list. What is on screen is
 * the merge of that list with the mirror's, deduplicated on MBID and on name, so
 * the number of rows drawn is not the server's offset and never will be — using
 * it as one would silently skip results. So [nextOffset] starts at zero and
 * advances by whole pages, and the merge drops the rows already shown.
 * Re-fetching the head of the bucket costs one call the user asked for and
 * cannot produce a duplicate row; guessing an offset can lose an album with no
 * sign that anything is missing.
 *
 * @property expanded the block is showing everything it holds rather than its
 *   capped preview. Expanding is local and instant: it needs no network, which is
 *   why it is a separate step from asking the server for more.
 * @property loading a page is in flight. The row says so and stops accepting
 *   taps, rather than queueing a second identical call behind the first.
 * @property nextOffset the `offset` the next page is asked for.
 * @property hasMore whether another page is worth asking for. It starts **true**,
 *   meaning "never asked": the combined search of rule 2 caps each bucket, so
 *   there is usually more behind it, and the honest way to find out is to ask.
 *   A page that comes back short sets it false, and the affordance goes away.
 * @property note a quiet line in place of the tappable label: why the last page
 *   failed, or that the catalogue has nothing further. Never a dialog — the same
 *   restraint REQUIREMENTS.md asks for `service_status`.
 * @property noteIsProblem true when [note] is a failure the user can retry by
 *   tapping, false when it is simply the end of the list.
 */
data class BucketPaging(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val nextOffset: Int = 0,
    val hasMore: Boolean = true,
    val note: String? = null,
    val noteIsProblem: Boolean = false,
)

/**
 * The paging state of every bucket at once, so [SearchUiState] carries one field
 * rather than four per bucket.
 *
 * Keyed by [SearchBucket] and not a pair of named fields: the enum is what the
 * repository call takes, so a screen that holds its state under the same key
 * cannot hand the albums offset to an artists request.
 */
data class SearchPaging(
    val buckets: Map<SearchBucket, BucketPaging> = emptyMap(),
) {

    /** The state of one bucket. An absent entry is a bucket nobody has expanded yet. */
    fun of(bucket: SearchBucket): BucketPaging = buckets[bucket] ?: BucketPaging()

    fun isExpanded(bucket: SearchBucket): Boolean = of(bucket).expanded

    /** True while any bucket has a page in flight. */
    val loading: Boolean get() = buckets.values.any { it.loading }

    fun with(bucket: SearchBucket, paging: BucketPaging): SearchPaging =
        SearchPaging(buckets + (bucket to paging))
}
