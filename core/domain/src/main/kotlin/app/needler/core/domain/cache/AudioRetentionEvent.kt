package app.needler.core.domain.cache

import app.needler.core.domain.diagnostics.DiagnosticsFormat
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackKey

/**
 * What the audio store did with one streamed track, and the line that says so in the diagnostics log.
 *
 * ## The defect this type exists to prevent
 *
 * A device played three tracks to completion and the Storage section read `Downloaded 125 MB /
 * Cached while listening 0 B`. The split is the whole diagnosis: one store serves both tiers, the
 * download half had kept 125 MB, and the streaming half had kept nothing. Diagnosing it took a full source trace, because every way
 * [AudioCacheWriter.openWrite] declines to keep a stream returns `null` and says nothing - and a
 * correct refusal is then indistinguishable from a wrong one, and both are indistinguishable from
 * the write having worked. REQUIREMENTS.md "Observability" already required the log this fills;
 * nothing was writing to it from this path, so the defect hid for a release.
 *
 * There are four refusals on that path and they have completely different meanings:
 *
 *  1. [TranscodedStream] - correct, and the most likely explanation of a device that retains nothing.
 *     REQUIREMENTS.md "Why transcoded bytes are never cached" forbids keeping those bytes, so a user
 *     with "transcode on mobile data" on and a metered connection is *supposed* to cache nothing.
 *  2. [LengthUnknown] - neither the response nor the mirror declared a length, so the write is never
 *     opened. Nothing about this is the user's doing and nothing else in the app reports it.
 *  3. [NoRoomAboveFloor] - REQUIREMENTS.md "Storage, and why there is no budget" point 5: "the
 *     incoming bytes are skipped, not forced in". The floor is the larger of 2 GB and 2% of the
 *     volume, so roughly 5.1 GB on a 256 GB phone: a fairly full device retains nothing, by design,
 *     and until now said nothing about it either.
 *  4. [IncompleteWrite] - the committed byte count disagreed with the declared length, so the bytes
 *     were discarded. This is the one that is usually a real fault, and REQUIREMENTS.md "Offline and
 *     caching" records it happening once already, from a stale mirror size.
 *
 * [AlreadyOnDevice] and [StoreUnwritable] are the two remaining silent exits from the same function,
 * and [Cached] is the positive case. [Cached] is not decoration: a bug report showing three `cached`
 * lines and a Storage section still reading `0 B` points at the accounting query, while the same
 * report with no lines at all points at the write path. Without it, the absence of evidence has two
 * explanations.
 *
 * ## Opening the write is not the end of the path
 *
 * Instrumenting the refusals above was not enough, and the way it failed is worth recording. A device
 * on the build that added them played a track with `format=raw` and a full `Content-Length` - so the
 * write *was* opened - and the log still held nothing from this path. Every refusal had a line; what
 * had none was the ending *after* a handle exists. [ReaderStoppedShort] and [WriteFailed] are those
 * endings, and [IncompleteWrite] is now reached from the streaming path rather than being short-circuited
 * by a duplicate check in `WriteThroughSink`. The lesson generalises: a log that covers every way a
 * function declines still says nothing about the writes it granted.
 *
 * ## Why the reason is a log line and not a return value
 *
 * [AudioCacheWriter.openWrite] deliberately still returns a plain nullable handle. Its KDoc is
 * explicit that callers must not tell the refusals apart, and that rule is right: a caller that
 * branched on the reason would be making retention policy at the call site, which is precisely what
 * having one store is for. So the reason travels to a sink the store is **given** rather than back to
 * the caller, and the store's signature is unchanged.
 *
 * ## Why the rendering lives here
 *
 * The mapping from a refusal to a sentence is pure logic with no Android, no Room and no filesystem in
 * it, so it belongs where it can be tested by `:core:domain:test` and asserted on character by
 * character. Building the strings inside `AudioCacheStoreWriter` instead would put the only
 * description of a refusal inside the class whose refusals are the thing under suspicion.
 *
 * Lines are deliberately plain ASCII. The export is a fixed-column text file that gets pasted into
 * issue trackers and terminals, and an em dash that arrives as a replacement character in one of them
 * is a line somebody stops trusting.
 *
 * @property key the track. Always the stable [TrackKey], never the server's `file_id`, so a line can
 *   be correlated with a cache row and with a later play attempt across a quality upgrade.
 * @property level how the line should be filed. Policy refusals are [DiagnosticsLevel.Debug] because
 *   they are the app working as specified; a `WARN` badge on correct behaviour is how a reader learns
 *   to ignore the badge.
 * @property line the sentence written to the log.
 */
public sealed interface AudioRetentionEvent {

    public val key: TrackKey

    public val level: DiagnosticsLevel

    public val line: String

    /**
     * The bytes were kept. [DiagnosticsLevel.Info], because "nothing failed" is exactly the thing a
     * bug report about an empty cache needs to be able to rule out.
     *
     * @property bytes what landed on disk, which is the number the row records and the Storage
     *   section adds up - not the length the response declared, so the two can be compared.
     */
    public data class Cached(
        override val key: TrackKey,
        public val bytes: Long,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Info
        override val line: String
            get() = "cached " + key.canonicalString + " - " +
                DiagnosticsFormat.bytes(bytes) + " written while listening"
    }

    /**
     * The resolver chose a transcode, so the bytes must not be retained.
     *
     * REQUIREMENTS.md "Why transcoded bytes are never cached": a 320 kbps rendering kept as a FLAC
     * track's offline copy would mean one commute quietly downgraded the user's library for good.
     * Correct behaviour, hence [DiagnosticsLevel.Debug] - and simultaneously the single most likely
     * answer to "why is my cache empty", which is why it gets a line at all.
     *
     * @property format what was streamed instead of the original, so the line names the setting that
     *   caused it rather than asserting a rule.
     */
    public data class TranscodedStream(
        override val key: TrackKey,
        public val format: StreamFormat,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Debug
        override val line: String
            get() = "not cached " + key.canonicalString + " - transcoded (" + describe(format) +
                "), only original bytes are retained"
    }

    /**
     * Complete bytes from the same server-side file are already on the device.
     *
     * The benign case, and worth a line only because its absence used to be ambiguous: a store that
     * reported nothing could equally have been refusing every write for a different reason.
     *
     * @property bytes what the existing row claims, so a row that has outlived its file shows up as a
     *   figure that disagrees with the disk.
     */
    public data class AlreadyOnDevice(
        override val key: TrackKey,
        public val bytes: Long,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Debug
        override val line: String
            get() = "not cached " + key.canonicalString + " - already on device, " +
                DiagnosticsFormat.bytes(bytes) + " from the same file"
    }

    /**
     * Neither the response nor the mirror declared a length, so no write was opened.
     *
     * [DiagnosticsLevel.Warn], unlike the other policy refusals, because nothing the user chose
     * produced it and nothing else in the app will ever mention it: the track streams perfectly and
     * is silently never retained, on every play, for as long as the server keeps omitting
     * `Content-Length`. An unknown length cannot be checked against the free-space floor before the
     * write, and a floor discovered after the bytes are on disk is not a floor.
     */
    public data class LengthUnknown(
        override val key: TrackKey,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Warn
        override val line: String
            get() = "not cached " + key.canonicalString +
                " - no length declared by the response or the mirror"
    }

    /**
     * Keeping the bytes would take the device below its free-space floor, so they are skipped.
     *
     * REQUIREMENTS.md "Storage, and why there is no budget" point 5. Nothing extra is evicted for
     * them: evicting further would cost the user recently played music and still leave the write
     * unable to fit. [DiagnosticsLevel.Warn], because the only thing that changes it is the user
     * removing something, and REQUIREMENTS.md point 6 says that case is "a warning, not an eviction
     * of downloads".
     *
     * @property incomingBytes the length the write was going to be held to.
     * @property freeBytes free space **after** the evictions the plan did make - so the line reports
     *   the number the decision was actually taken against, not the number before housekeeping.
     * @property floorBytes the floor for this volume: the larger of 2 GB and 2% of it, which is about
     *   5.1 GB on a 256 GB phone and 10.2 GB on a 512 GB one.
     */
    public data class NoRoomAboveFloor(
        override val key: TrackKey,
        public val incomingBytes: Long,
        public val freeBytes: Long,
        public val floorBytes: Long,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Warn
        override val line: String
            get() = "not cached " + key.canonicalString + " - " +
                DiagnosticsFormat.bytes(incomingBytes) + " needed, " +
                DiagnosticsFormat.bytes(freeBytes) + " free after eviction, floor is " +
                DiagnosticsFormat.bytes(floorBytes) + "; remove a downloaded album to free room"
    }

    /**
     * The store could not be opened for writing at all.
     *
     * A full volume, a directory that could not be created, a `SecurityException` on app-private
     * storage. Playback is unaffected, which is the rule, and that is exactly why nothing else
     * reports it.
     */
    public data class StoreUnwritable(
        override val key: TrackKey,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Warn
        override val line: String
            get() = "not cached " + key.canonicalString +
                " - the audio store could not be opened for writing"
    }

    /**
     * The finished write did not match the length it was held to, so the bytes were discarded.
     *
     * The failure this store cares about most, because publishing them instead is silent: the next
     * play finds a local file, plays it, and stops two minutes into a four-minute song with no error
     * anywhere. Discarding is correct; discarding without a word is what made a stale mirror size
     * look like "downloads are not retained" for a release.
     *
     * Exact byte counts here, not [DiagnosticsFormat.bytes], and this is the one place in this file
     * where that matters. The defect that produced this refusal on every play of every track was a
     * disagreement of eleven bytes between a replaced file and the mirror's remembered size, and
     * `195.3 KB` of `195.3 KB` is a line that hides its own evidence.
     *
     * @property writtenBytes what arrived.
     * @property declaredBytes what the write was held to, or null when nothing declared one and the
     *   write was refused for having no bytes at all.
     */
    public data class IncompleteWrite(
        override val key: TrackKey,
        public val writtenBytes: Long,
        public val declaredBytes: Long?,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Warn
        override val line: String
            get() {
                val declared: Long? = declaredBytes
                val body: String = if (declared == null) {
                    "wrote " + writtenBytes + " bytes with no declared length to check, discarded"
                } else {
                    "wrote " + writtenBytes + " of " + declared +
                        " declared bytes, discarded as truncated"
                }
                return "not cached " + key.canonicalString + " - " + body
            }
    }

    /**
     * The reader closed the stream before the end of the file, and the tail was taken from the same
     * response so that the file on disk is whole.
     *
     * ## Why a read that reached the end of the track can still be short
     *
     * An extractor reads the *track*, and a file is not only its track. `Mp3Extractor` asks its seeker
     * where the audio data ends and reports end-of-stream there; with a Xing or VBRI header that is the
     * byte count the encoder wrote into the header, so a trailing ID3v1 tag (128 bytes), Lyrics3v2 or
     * APEv2 block is never requested. Media3 reads nothing from a `DataSource` that its extractor did
     * not ask for, so the write ends a tag's worth of bytes short of `Content-Length` on a track that
     * played perfectly from the first byte to the last - and the store, holding the write to the length
     * the response declared, discarded it. Every play, every track. That is what a device reporting
     * `Cached while listening 0 B` beside `Downloaded 440 MB` looked like from the outside.
     *
     * [DiagnosticsLevel.Debug]: nothing failed, and the line exists so that the next report of an empty
     * cache says whether the tail was taken, how big it was, and therefore whether the reader or the
     * store is the half to look at.
     *
     * @property shortfallBytes how far short of [declaredBytes] the reader stopped.
     * @property tailBytes how much of that shortfall was actually read. Less than [shortfallBytes] means
     *   the body ended early or the read was interrupted, and the store then refuses the write and
     *   reports [IncompleteWrite] with both counts.
     * @property declaredBytes the length the write is held to.
     */
    public data class ReaderStoppedShort(
        override val key: TrackKey,
        public val shortfallBytes: Long,
        public val tailBytes: Long,
        public val declaredBytes: Long,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Debug
        override val line: String
            get() = "reader stopped short on " + key.canonicalString + " - " + shortfallBytes +
                " of " + declaredBytes + " declared bytes were never read; took " + tailBytes +
                " from the same response"
    }

    /**
     * The store stopped accepting bytes part way through, so there is nothing to publish.
     *
     * A full volume, a file that went away underneath the write, a database that would not answer. The
     * handle has already thrown its partial away by the time this is reported, and playback never
     * noticed - which is the rule, and exactly why nothing else in the app will ever mention it.
     *
     * @property writtenBytes how far the write got before it gave up.
     */
    public data class WriteFailed(
        override val key: TrackKey,
        public val writtenBytes: Long,
    ) : AudioRetentionEvent {
        override val level: DiagnosticsLevel get() = DiagnosticsLevel.Warn
        override val line: String
            get() = "not cached " + key.canonicalString + " - the store stopped accepting bytes after " +
                writtenBytes + ", nothing published"
    }

    public companion object {

        /**
         * `original` or `mp3 320 kbps` - what was actually streamed.
         *
         * Named rather than derived from `toString`, because a data class's generated `toString`
         * changes shape when a property is added and a log line is something people grep for.
         */
        public fun describe(format: StreamFormat): String = when (format) {
            is StreamFormat.Original -> "original"
            is StreamFormat.Transcoded -> format.codec + " " + format.maxBitrateKbps + " kbps"
        }
    }
}
