package app.needler.core.data.mapper

import kotlinx.datetime.Instant

/**
 * Timestamp parsing for the two lanes, which do not agree with each other.
 *
 * The `/requests` half of `/api/v1` sends ISO-8601 strings; the `/downloads` half sends epoch
 * **seconds as a float**, and both appear on the same screen. Subsonic sends ISO-8601. Nothing here
 * throws: a timestamp Needler cannot read is a missing timestamp, never a failed sync.
 */
public object WireTime {

    /** ISO-8601 instant, as both Subsonic and the `/requests` lane send it. */
    public fun fromIso(value: String?): Instant? {
        val trimmed: String = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { Instant.parse(trimmed) }.getOrNull()
            ?: runCatching { Instant.parse(trimmed + "Z") }.getOrNull()
    }

    /** Epoch seconds as a float, as the `/downloads` lane and `library/stats` send them. */
    public fun fromEpochSeconds(value: Double?): Instant? {
        val seconds: Double = value ?: return null
        if (seconds <= 0.0) return null
        return Instant.fromEpochMilliseconds((seconds * 1_000.0).toLong())
    }

    public fun fromEpochMillis(value: Long?): Instant? {
        val millis: Long = value ?: return null
        if (millis <= 0L) return null
        return Instant.fromEpochMilliseconds(millis)
    }

    public fun toEpochMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    /** ISO-8601 first, then epoch seconds, for fields whose shape differs between server versions. */
    public fun fromIsoOrEpochSeconds(value: String?): Instant? {
        val parsed: Instant? = fromIso(value)
        if (parsed != null) return parsed
        val asDouble: Double = value?.trim()?.toDoubleOrNull() ?: return null
        return fromEpochSeconds(asDouble)
    }
}
