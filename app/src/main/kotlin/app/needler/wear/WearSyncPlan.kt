package app.needler.wear

import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.TrackFetchHandle

/**
 * Everything the phone decides about a sync pass, with no Google Play services and no Room in sight.
 *
 * [WearAudioSync] holds a `DataClient` and two repositories, so it cannot be unit-tested. Everything in
 * this file can be, and everything with a decision in it lives here: which tracks go next, what counts
 * as stale, and which albums the offer carries. It is the same split `WearSnapshots` makes beside
 * `WearStatePublisher`, and it is worth repeating because two of the three decisions below are the sort
 * that fail silently.
 */
internal object WearSyncPlan {

    /**
     * The next tracks to publish for the album the watch is working on.
     *
     * @param candidates the album's downloadable tracks, in record order.
     * @param held what the watch reports holding, from [WearHeldTracks.parse].
     * @param limit [WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT].
     * @param watchFreeBytes the watch's own reading of its usable space, or 0 when it did not say.
     *
     * Three things are skipped, and the second is the one that implements a requirement:
     *
     *  * **A track the watch already holds with current bytes.** That is the ordinary case for most of
     *    an album and it is what makes the pipeline advance.
     *  * **Nothing.** A track whose held fingerprint has moved is *not* skipped: it is published again,
     *    which is how REQUIREMENTS.md "Invalidating upgraded files" reaches the watch. "DroppedNeedle
     *    upgrades files in place when a better source appears, so cached audio can silently become the
     *    older, worse copy" - and the watch cannot detect that for itself, because only the phone can
     *    see the server. The watch's ingest overwrites by key, so the replacement is the eviction.
     *  * **A track past [WearPlaybackProtocol.MAX_TRACK_BYTES], or one larger than the watch says it has
     *    room for.** The size guard is deliberately coarse: the real bound is the watch's free-space
     *    floor, which is the watch's own policy and is re-checked at ingest. This only avoids starting a
     *    transfer that is certain to be thrown away.
     */
    fun next(
        candidates: List<WearSyncCandidate>,
        held: Map<String, String>,
        limit: Int,
        watchFreeBytes: Long,
    ): List<WearSyncCandidate> {
        if (limit <= 0) return emptyList()
        val chosen: MutableList<WearSyncCandidate> = ArrayList(limit)
        for (candidate in candidates) {
            if (chosen.size >= limit) break
            if (candidate.sizeBytes <= 0L) continue
            if (candidate.sizeBytes > WearPlaybackProtocol.MAX_TRACK_BYTES) continue
            // A free figure of zero means the watch did not report one, which is not evidence that it is
            // full - it is a watch on an older build, or one whose volume could not be read. Refusing on
            // it would stop the pipeline for ever; the watch's own check is what actually decides.
            if (watchFreeBytes > 0L && candidate.sizeBytes > watchFreeBytes) continue

            val heldFingerprint: String? = held[candidate.keyCanonical]
            if (heldFingerprint != null &&
                !WearSyncFingerprint.differs(heldFingerprint, candidate.fingerprint)
            ) {
                continue
            }
            chosen.add(candidate)
        }
        return chosen
    }

    /**
     * The window of downloaded albums the offer carries.
     *
     * Most recently downloaded first, which is not the order `PinRepository.observeDownloadedAlbums`
     * emits - that is largest first, for a Storage screen whose job is finding gigabytes. A watch picker
     * has a different job: the album somebody wants on their wrist is almost always the one they have
     * just acquired, and "largest first" would put a box set at the top for ever.
     *
     * An album with nothing downloaded is dropped rather than offered as an empty row. REQUIREMENTS.md
     * "Partial content is a normal state" allows a pin whose download never landed, and offering one to
     * the watch would have the pipeline wait for tracks that are not there.
     */
    fun offer(albums: List<WearOfferAlbum>, limit: Int): WearOfferWindow {
        val usable: List<WearOfferAlbum> = albums
            .filter { album -> album.downloadedTrackCount > 0 }
            .sortedWith(
                compareByDescending<WearOfferAlbum> { album -> album.downloadedAtEpochMs }
                    .thenBy { album -> album.title.lowercase() },
            )
        if (limit <= 0) return WearOfferWindow(rows = emptyList(), notShown = usable.size)
        val rows: List<WearOfferAlbum> = usable.take(limit)
        return WearOfferWindow(rows = rows, notShown = (usable.size - rows.size).coerceAtLeast(0))
    }

    /**
     * How many tracks of an album the phone can actually serve, from the pin's own download state.
     *
     * A pin already knows how far its download got, which is what makes this possible without a cache
     * lookup per track - sixty albums of those is several hundred queries on every nudge.
     *
     * The five cases:
     *
     *  * [OfflineDownloadState.Complete] carries no count, because "complete" means the album's length -
     *    so [albumTrackCount] is read, and a null one answers 0. A null is an album whose row the mirror
     *    has not got, which is a situation to skip rather than guess at: an offer claiming tracks the
     *    phone cannot produce would have the watch's pipeline wait for them for ever.
     *  * [OfflineDownloadState.Downloading] and [OfflineDownloadState.Partial] both carry
     *    `tracksComplete`, which is exactly the figure wanted: what the watch can have *now*.
     *    REQUIREMENTS.md "Partial content is a normal state" makes both of those ordinary rather than
     *    exceptional, and an album being offered while it is still downloading is correct - the watch
     *    fills in behind it on later passes.
     *  * Queued, waiting for Wi-Fi, or failed: nothing is known to be on disk, so nothing is offered.
     *    A failed download that did land some tracks is under-reported here, which costs the user an
     *    album they cannot add until the retry succeeds - the honest direction to be wrong in, since the
     *    alternative is a watch asking for tracks that are not there.
     */
    fun downloadedTracks(state: OfflineDownloadState, albumTrackCount: Int?): Int = when (state) {
        OfflineDownloadState.Complete -> (albumTrackCount ?: 0).coerceAtLeast(0)
        is OfflineDownloadState.Downloading -> state.tracksComplete.coerceAtLeast(0)
        is OfflineDownloadState.Partial -> state.tracksComplete.coerceAtLeast(0)
        OfflineDownloadState.Queued -> 0
        OfflineDownloadState.WaitingForUnmeteredNetwork -> 0
        is OfflineDownloadState.Failed -> 0
    }
}

/**
 * One track the phone could put on the watch.
 *
 * Deliberately made of primitives rather than of a `Track` and a `CachedAudio`. Two reasons: it is what
 * lets [WearSyncPlan.next] be tested without domain fixtures, and it forces the mapping - which is where
 * the "is this downloaded, complete, pinned and original-format" question is answered - to happen in one
 * place, in [WearAudioSync], rather than being re-derived wherever a field is read.
 *
 * @param keyCanonical `TrackKey.canonicalString`. The identity, and the data-layer path suffix.
 * @param fingerprint the token for the bytes *as they are now on the phone*, from
 *   [WearSyncFingerprint.of].
 * @param filePath where the bytes are, in the phone's app-private storage.
 */
internal data class WearSyncCandidate(
    val keyCanonical: String,
    val fingerprint: String,
    val filePath: String,
    val sizeBytes: Long,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val durationMs: Long,
    val format: String,
)

/** One downloaded album, flattened for [WearSyncPlan.offer]. */
internal data class WearOfferAlbum(
    val albumKey: String,
    val title: String,
    val artist: String,
    /** Tracks of this album actually on the phone's disk, not the album's length. */
    val downloadedTrackCount: Int,
    /** Bytes on the phone's disk, as the cache index accounts for them. */
    val sizeBytes: Long,
    /** When the user pinned it, as epoch milliseconds. Orders the offer; nothing else reads it. */
    val downloadedAtEpochMs: Long,
)

/** The offer, with how much of it did not fit. */
internal data class WearOfferWindow(
    val rows: List<WearOfferAlbum>,
    val notShown: Int,
)

/**
 * The staleness token, built and compared only here.
 *
 * ## What it is for
 *
 * REQUIREMENTS.md "Invalidating upgraded files" requires that cached audio be compared, on every sync,
 * against "the fingerprint snapshotted onto the `audio_cache` row when those bytes were downloaded" -
 * and that the comparison be against a record of the file *as it was at download time*, because
 * "comparing fresh server metadata against a row that has just been overwritten with that same metadata
 * compares the mirror against itself: it reports 'unchanged' every time".
 *
 * The watch has the same problem one device further out, and it cannot solve it: it has no server to
 * ask. So the phone does both halves. It writes this token beside the bytes it sends, the watch stores it
 * verbatim and echoes it back, and [differs] is the comparison - run on the phone, against what the
 * server says now.
 *
 * ## Why it is a string, and why the watch must not parse it
 *
 * A string because a `DataMap` holds primitives and a nested map per track would double the selection
 * item's size for fields nothing on the watch reads. Opaque because a watch that understood the fields
 * would be a watch that could be tempted to key something on the `file_id` inside one - and
 * REQUIREMENTS.md "Track identity is not stable" is unambiguous that `file_id` "must never be used as an
 * offline cache key". It is in here because it is the strongest single signal that the bytes changed,
 * and it names nothing.
 *
 * ## Why the comparison is not string equality
 *
 * This is the part that would fail silently. REQUIREMENTS.md: "A field counts as changed only when both
 * sides carry a value. A null on either side means 'unknown', never 'changed'. Without that rule, a
 * server release that stopped reporting durations, or a cache row written before a column existed, would
 * declare every cached track stale and re-download the user's entire offline library - possibly over
 * mobile data. Unknown is not evidence."
 *
 * Over Bluetooth that is worse than a re-download: it is a day of transfers, on two batteries, to arrive
 * at the same bytes. So [differs] parses both tokens and applies exactly the rule
 * `TrackFetchHandle.isStaleComparedTo` applies, field by field.
 */
internal object WearSyncFingerprint {

    /**
     * The token for the bytes a handle describes.
     *
     * The five fields of `TrackFetchHandle`, in a fixed order. A field the server did not report becomes
     * [WearPlaybackProtocol.FINGERPRINT_ABSENT], which [differs] reads as unknown.
     *
     * The separator is stripped out of each field rather than escaped. A `file_id` is a row id and a
     * format is an enum name, so none of them can contain it in practice; substituting is a guard
     * against a server that surprises us, and it is safe because this token is only ever compared for
     * equality - two values that collided after substitution would report "unchanged", which is the same
     * answer they would have given had they been equal in the first place.
     */
    fun of(handle: TrackFetchHandle): String = listOf(
        handle.fileId.value,
        handle.sizeBytes?.toString(),
        handle.durationMs?.toString(),
        handle.format?.name,
        handle.bitrateKbps?.toString(),
    ).joinToString(separator = WearPlaybackProtocol.FINGERPRINT_SEPARATOR) { field ->
        sanitise(field)
    }

    /**
     * Whether bytes carrying [held] must be replaced given [current].
     *
     * @param held the token the watch reports, which the phone wrote when it sent those bytes.
     * @param current the token for what the phone holds now, from [of].
     *
     * True in three cases:
     *
     *  * The file id changed. Compared directly, because it is never absent and "a changed id is the
     *    server telling us plainly that these are different bytes".
     *  * Any of size, duration, format or bitrate is present on **both** sides and unequal.
     *  * [held] cannot be parsed at all.
     *
     * The third needs its justification, because it is the one that could loop. A token this phone
     * cannot read describes bytes whose currency cannot be established, and REQUIREMENTS.md does not
     * allow those to survive on a device - so they are replaced. It cannot loop, because the replacement
     * the phone publishes carries a token this phone wrote, which it can read; one redundant transfer is
     * the whole cost.
     */
    fun differs(held: String, current: String): Boolean {
        val heldFields: List<String> = split(held) ?: return true
        val currentFields: List<String> = split(current) ?: return true

        // The file id, compared directly. An absent one on either side cannot be compared, which counts
        // as changed for the reason above rather than as unknown: unlike the four below, this field is
        // never legitimately absent.
        val heldId: String = heldFields[0]
        val currentId: String = currentFields[0]
        if (heldId == WearPlaybackProtocol.FINGERPRINT_ABSENT) return true
        if (currentId == WearPlaybackProtocol.FINGERPRINT_ABSENT) return true
        if (heldId != currentId) return true

        for (index in 1 until FIELD_COUNT) {
            if (fieldDiffers(heldFields[index], currentFields[index])) return true
        }
        return false
    }

    /**
     * Two optional fields differ only when both are present and unequal.
     *
     * The whole safety of [differs] is in this function, and it is three lines because the rule is three
     * lines. See the class note for what happens without it.
     */
    private fun fieldDiffers(held: String, current: String): Boolean {
        if (held == WearPlaybackProtocol.FINGERPRINT_ABSENT) return false
        if (current == WearPlaybackProtocol.FINGERPRINT_ABSENT) return false
        return held != current
    }

    private fun split(token: String): List<String>? {
        if (token.isEmpty()) return null
        val fields: List<String> = token.split(WearPlaybackProtocol.FINGERPRINT_SEPARATOR)
        return if (fields.size == FIELD_COUNT) fields else null
    }

    private fun sanitise(field: String?): String {
        if (field.isNullOrEmpty()) return WearPlaybackProtocol.FINGERPRINT_ABSENT
        val cleaned: String = field
            .replace(WearPlaybackProtocol.FINGERPRINT_SEPARATOR, "_")
            .replace(WearPlaybackProtocol.HELD_TRACK_SEPARATOR, "_")
        // A field that is literally the absent marker would be read back as unknown, which for a file id
        // means "replace these bytes" and for the rest means "do not compare". Neither is wrong, but the
        // value is real, so it is kept distinguishable.
        return if (cleaned == WearPlaybackProtocol.FINGERPRINT_ABSENT) "_" else cleaned
    }

    /** File id, size, duration, format, bitrate. */
    private const val FIELD_COUNT: Int = 5
}

/**
 * What the watch says it holds, decoded.
 *
 * Its own object because the encoding is shared with nothing and the failure modes are all "a mismatched
 * build sent something unexpected", which is a normal situation between two separately installed APKs
 * rather than a corrupt one - the argument `DataLayerPlaybackClient` makes about the transport applies
 * identically here.
 */
internal object WearHeldTracks {

    /**
     * Track key to fingerprint.
     *
     * An entry that does not split into exactly two parts is dropped rather than guessed at: with only
     * one half there is no way to tell a key with no fingerprint from a fingerprint with no key, and
     * either reading would have the phone act on a track it cannot identify. A dropped entry reads as
     * "the watch does not hold this", so the worst case is one redundant transfer.
     *
     * A duplicated key takes the first entry, which is the one nearest the front of the album - the
     * watch reports its holdings in record order, so the first is the one the album's own ordering
     * agrees with.
     */
    fun parse(entries: List<String>?): Map<String, String> {
        if (entries.isNullOrEmpty()) return emptyMap()
        val held: MutableMap<String, String> = LinkedHashMap(entries.size)
        for (entry in entries) {
            val parts: List<String> = entry.split(WearPlaybackProtocol.HELD_TRACK_SEPARATOR)
            if (parts.size != 2) continue
            val key: String = parts[0]
            val fingerprint: String = parts[1]
            if (key.isEmpty() || fingerprint.isEmpty()) continue
            if (held.containsKey(key)) continue
            held[key] = fingerprint
        }
        return held
    }
}
