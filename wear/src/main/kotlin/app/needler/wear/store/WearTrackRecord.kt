package app.needler.wear.store

/**
 * What the watch knows about one track it holds, beyond the bytes themselves.
 *
 * One of these is written beside every audio file as a small text sidecar, and the pair is the whole
 * store: there is no index file and no database. See [WearAudioStore] for why, and
 * [WearTrackRecordCodec] for the format.
 *
 * @param key the track's identity. Never a `file_id` - see [WearAudioKey].
 * @param title the track title, as the watch draws it.
 * @param artist the track artist, which on a compilation differs from the album artist.
 * @param albumTitle the album title, so the store can group itself without consulting the phone's
 *   offer. A watch out of range must still be able to list what it holds.
 * @param durationMs display duration, or 0 when the server never reported one. Zero means unknown and
 *   is drawn as nothing rather than as a zero timecode.
 * @param format the `AudioFormat` name the bytes are in, e.g. `FLAC`. It is the quality badge and the
 *   file extension, and nothing decodes from it: Media3 sniffs the container itself.
 * @param sizeBytes the audio's length. Checked against the file on disk, because a record claiming a
 *   length the file does not have is the one thing this store must never serve as playable.
 * @param fingerprint the opaque staleness token the bytes arrived with, from
 *   [app.needler.wear.playback.WearPlaybackProtocol.KEY_TRACK_FINGERPRINT]. **The watch never parses
 *   it.** It is stored here and echoed back to the phone, which is the only node that can see the
 *   server and therefore the only node that can say whether it has moved. This is REQUIREMENTS.md
 *   "Invalidating upgraded files" across two devices: the fingerprint is "a record of the file as it
 *   was at download time", held beside the bytes it describes.
 */
data class WearTrackRecord(
    val key: WearAudioKey,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val durationMs: Long,
    val format: String,
    val sizeBytes: Long,
    val fingerprint: String,
) {

    /** The album this track belongs to: the first part of its key. */
    val albumKey: String get() = key.releaseGroupMbid

    /**
     * What the watch reports holding, for
     * [app.needler.wear.playback.WearPlaybackProtocol.KEY_HELD_TRACKS].
     *
     * Built here rather than at the call site so that the one place which knows the fingerprint's
     * meaning is also the place that puts it on the wire.
     */
    val heldEntry: String
        get() = key.canonical + HELD_SEPARATOR + fingerprint

    companion object {
        /**
         * Mirror of [app.needler.wear.playback.WearPlaybackProtocol.HELD_TRACK_SEPARATOR].
         *
         * Named again here so this file does not depend on the protocol object for one character, and
         * asserted equal to it by `WearPlaybackProtocolTest` so the two cannot drift.
         */
        const val HELD_SEPARATOR: String = "\t"
    }
}

/**
 * The sidecar format: one field name, a tab, and a value, per line.
 *
 * ## Why a hand-rolled text format
 *
 * Three alternatives were weighed and each costs more than this does.
 *
 *  * **Room.** A database for a few hundred rows of metadata that is already keyed by filename, in a
 *    module with no KSP wired up and no Hilt to inject a DAO into. It would be the largest dependency
 *    on the watch for the smallest reason.
 *  * **`kotlinx.serialization`.** Not a dependency of `:wear`, and adding one means a new line in
 *    `gradle/libs.versions.toml` - a file whose header records the day and the repository every number
 *    was read from. `GmsTasks.kt` already declines that trade for the same reason.
 *  * **The data layer's own `DataMap` byte form.** Google Play services is already on the classpath.
 *    Rejected because it would put the store's durability at the mercy of a byte format that is not
 *    documented as stable across Play services versions, and because a `DataMap` cannot be built or
 *    asserted in a JVM unit test - which is the whole reason the codec is separate from the store.
 *
 * What is here has no dependency and is a pure function in both directions, so the escaping, the
 * unknown-field tolerance and the version check are all exercised by `WearTrackRecordCodecTest` with
 * no watch and no phone.
 *
 * ## Tolerant in one direction only
 *
 * [decode] ignores a line it does not recognise and defaults a field that is absent, because a store
 * written by an older build of this app must stay readable. It refuses outright on two things: a
 * [FIELD_VERSION] it does not know, and a [FIELD_KEY] that will not parse. Both mean the record
 * describes something this build cannot safely name a file for, and a record that cannot be trusted to
 * identify its own bytes is worse than a missing one.
 */
object WearTrackRecordCodec {

    /** The format version. Bumped only by a change an older build could misread. */
    const val VERSION: Int = 1

    const val FIELD_VERSION: String = "v"
    const val FIELD_KEY: String = "key"
    const val FIELD_TITLE: String = "title"
    const val FIELD_ARTIST: String = "artist"
    const val FIELD_ALBUM: String = "album"
    const val FIELD_DURATION_MS: String = "durationMs"
    const val FIELD_FORMAT: String = "format"
    const val FIELD_SIZE_BYTES: String = "sizeBytes"
    const val FIELD_FINGERPRINT: String = "fingerprint"

    /** Separates a field name from its value. Escaped out of every value; see [escape]. */
    const val FIELD_SEPARATOR: String = "\t"

    /** One record as text. Every line ends in a newline, so nothing is ever accidentally joined. */
    fun encode(record: WearTrackRecord): String {
        val builder: StringBuilder = StringBuilder()
        appendField(builder, FIELD_VERSION, VERSION.toString())
        appendField(builder, FIELD_KEY, record.key.canonical)
        appendField(builder, FIELD_TITLE, record.title)
        appendField(builder, FIELD_ARTIST, record.artist)
        appendField(builder, FIELD_ALBUM, record.albumTitle)
        appendField(builder, FIELD_DURATION_MS, record.durationMs.toString())
        appendField(builder, FIELD_FORMAT, record.format)
        appendField(builder, FIELD_SIZE_BYTES, record.sizeBytes.toString())
        appendField(builder, FIELD_FINGERPRINT, record.fingerprint)
        return builder.toString()
    }

    /**
     * One record from text, or null when it cannot be trusted to identify its own bytes.
     *
     * A duplicated field takes the last value, which is the only reading that is ever right: the store
     * appends nothing, so a later line can only be a later write.
     */
    fun decode(text: String?): WearTrackRecord? {
        if (text.isNullOrBlank()) return null
        val fields: MutableMap<String, String> = LinkedHashMap()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val separator: Int = line.indexOf(FIELD_SEPARATOR)
            if (separator <= 0) continue
            val name: String = line.substring(0, separator)
            val value: String = unescape(line.substring(separator + FIELD_SEPARATOR.length))
            fields[name] = value
        }

        // An unknown version is refused rather than read hopefully: the fields may mean something else.
        val version: Int = fields[FIELD_VERSION]?.toIntOrNull() ?: return null
        if (version != VERSION) return null

        val key: WearAudioKey = WearAudioKey.parse(fields[FIELD_KEY]) ?: return null

        return WearTrackRecord(
            key = key,
            // A blank title stays blank. REQUIREMENTS.md's argument for sending `hasItem` explicitly
            // applies here too: badly tagged and absent must not render alike, and the store can only
            // show what it was given.
            title = fields[FIELD_TITLE] ?: "",
            artist = fields[FIELD_ARTIST] ?: "",
            albumTitle = fields[FIELD_ALBUM] ?: "",
            durationMs = fields[FIELD_DURATION_MS]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            format = fields[FIELD_FORMAT] ?: "",
            sizeBytes = fields[FIELD_SIZE_BYTES]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            fingerprint = fields[FIELD_FINGERPRINT] ?: "",
        )
    }

    private fun appendField(builder: StringBuilder, name: String, value: String) {
        builder.append(name).append(FIELD_SEPARATOR).append(escape(value)).append('\n')
    }

    /**
     * Makes [value] safe to hold on one line beside a tab-separated name.
     *
     * A track title can legitimately contain anything, tabs and newlines included, and a title with a
     * newline in it would otherwise turn one record into two half-records. The backslash is escaped
     * first on encode and handled last on decode, which is the only ordering that round-trips a value
     * that already contained one.
     */
    fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** Inverse of [escape]. A trailing lone backslash is dropped rather than read past the end. */
    fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val builder: StringBuilder = StringBuilder(value.length)
        var index: Int = 0
        while (index < value.length) {
            val character: Char = value[index]
            if (character != '\\') {
                builder.append(character)
                index += 1
                continue
            }
            if (index + 1 >= value.length) break
            val escaped: Char = value[index + 1]
            when (escaped) {
                't' -> builder.append('\t')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                '\\' -> builder.append('\\')
                // An escape this build does not know keeps both characters, so a value written by a
                // later build is degraded rather than silently truncated.
                else -> builder.append('\\').append(escaped)
            }
            index += 2
        }
        return builder.toString()
    }
}
