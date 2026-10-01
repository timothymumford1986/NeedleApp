package app.needler.wear.store

/**
 * The identity of one track in the watch's audio store.
 *
 * ## Why this is the phone's key, exactly
 *
 * REQUIREMENTS.md "Track identity is not stable" is the whole reason this type exists rather than a
 * `String` id off the wire. DroppedNeedle "performs automatic quality upgrades, replacing files in
 * place", so a Subsonic track id - which is `tr-<file_id>` - names "whatever bytes currently sit
 * behind a track, not the track itself". Keying the store on one means a track upgraded on the server
 * keeps playing the old bytes on the wrist for ever, and nothing ever says so.
 *
 * So the key is the same three parts `:core:domain`'s `TrackKey` and `:core:data`'s `TrackKeyDb`
 * carry: release-group MBID, disc number, track number. Not four parts. REQUIREMENTS.md names the
 * recording MBID as part of the identity tuple "where the server provides one", and the phone's own
 * store does not key on it - so a watch that did could not be diffed against the phone at all, and the
 * first sync after a track gained a recording MBID would re-transfer the album over Bluetooth. Same
 * rule as the phone store.
 *
 * The `file_id` is not absent from the watch: it is one field inside the opaque fingerprint on
 * [WearTrackRecord], which exists solely to be compared for equality by the phone. It names nothing
 * and finds nothing.
 *
 * ## Why the alphabet is restricted
 *
 * [fileStem] joins the three parts with [FILE_SEPARATOR] to make a filename, and the store names every
 * file it writes from it. Two things follow, and both are enforced by [parse] rather than trusted:
 *
 *  * An identifier containing a path separator, or reading as `.` or `..`, would let a malformed
 *    snapshot from a mismatched phone build name a file outside the store's own directory.
 *  * An identifier containing [FILE_SEPARATOR] would make the filename mapping merely *provably*
 *    injective instead of *obviously* injective. Excluding it from the alphabet is one line and
 *    removes the question. No MusicBrainz identifier contains an underscore - they are UUIDs - so
 *    nothing real is lost.
 *
 * A key that fails these checks is dropped, which costs one track on the watch. Naming a file from
 * text that arrived over a wire would cost rather more.
 */
data class WearAudioKey(
    val releaseGroupMbid: String,
    val discNumber: Int,
    val trackNumber: Int,
) {

    /**
     * The form that goes on the wire and into a data-layer path:
     * `<release-group-mbid>/<disc>/<track>`.
     *
     * Identical to `TrackKey.canonicalString` on the phone, deliberately, so the two stores talk about
     * the same track in the same words.
     */
    val canonical: String
        get() = releaseGroupMbid + SEPARATOR + discNumber + SEPARATOR + trackNumber

    /**
     * The filename this track's files are named from, with no extension.
     *
     * Slashes cannot appear in a filename, so [SEPARATOR] becomes [FILE_SEPARATOR]. See the class note
     * for why that substitution is safe.
     */
    val fileStem: String
        get() = releaseGroupMbid + FILE_SEPARATOR + discNumber + FILE_SEPARATOR + trackNumber

    override fun toString(): String = canonical

    companion object {

        /** Separates the three parts of [canonical]; also a data-layer path separator. */
        const val SEPARATOR: String = "/"

        /** Separates the three parts of [fileStem]. Excluded from the identifier alphabet. */
        const val FILE_SEPARATOR: String = "_"

        /**
         * The longest identifier accepted. A release-group MBID is 36 characters; 64 leaves room for a
         * server that mints something longer without accepting a filename of unbounded length.
         */
        const val MAX_IDENTIFIER_LENGTH: Int = 64

        /**
         * Parses [canonical], or answers null for anything this store will not name a file from.
         *
         * Null is an ordinary answer. The phone and the watch are separately installed APKs with
         * independent version codes, so a snapshot carrying something unexpected is a normal
         * situation, not a corrupt one - see [app.needler.wear.playback.DataLayerPlaybackClient] for
         * the same argument about the transport.
         */
        fun parse(value: String?): WearAudioKey? {
            if (value.isNullOrEmpty()) return null
            val parts: List<String> = value.split(SEPARATOR)
            if (parts.size != 3) return null
            val mbid: String = parts[0]
            if (!isSafeIdentifier(mbid)) return null
            val disc: Int = parts[1].toIntOrNull() ?: return null
            val track: Int = parts[2].toIntOrNull() ?: return null
            // The domain requires both to be 1-based, and a zero or negative number here would come
            // from a mismatched build rather than from a record.
            if (disc < 1 || track < 1) return null
            return WearAudioKey(releaseGroupMbid = mbid, discNumber = disc, trackNumber = track)
        }

        /**
         * Whether [value] may be used as part of a filename in the store.
         *
         * Also the check an album identifier passes before it names a cover file, which is why it is
         * separate from [parse] rather than inlined into it.
         */
        fun isSafeIdentifier(value: String): Boolean {
            if (value.isEmpty() || value.length > MAX_IDENTIFIER_LENGTH) return false
            // "." and ".." are legal under the character test below and name a directory, not a file.
            if (value == "." || value == "..") return false
            return value.all { character ->
                character in 'a'..'z' ||
                    character in 'A'..'Z' ||
                    character in '0'..'9' ||
                    character == '-' ||
                    character == '.'
            }
        }
    }
}
