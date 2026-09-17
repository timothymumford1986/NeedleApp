package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * `/api/v1` wire types.
 *
 * The server serialises msgspec structs with no aliases and no camelCase conversion, so every
 * field below is snake_case exactly as spelled in the Python source, and every field carries an
 * explicit @SerialName so a Kotlin rename can never silently break the wire contract.
 *
 * Every DTO is decoded with `ignoreUnknownKeys = true`: a newer DroppedNeedle may add fields.
 * Fields are nullable or defaulted for the same reason — an older server may omit them.
 *
 * These are DTOs only. `:core:network` maps nothing to domain types; `:core:data` owns that.
 */

/** `GET /api/v1/version` → `VersionInfo`. Authenticated, despite looking like a probe. */
@Serializable
public data class VersionInfoDto(
    @SerialName("version") val version: String = "",
    @SerialName("build_date") val buildDate: String? = null,
)

/** `GET /api/v1/status` → `StatusReport`. */
@Serializable
public data class StatusReportDto(
    /** `ok` | `degraded` | `error`. */
    @SerialName("status") val status: String = "ok",
    /** Free-form map; in practice `download_client` and `library`. */
    @SerialName("services") val services: Map<String, ServiceStatusDto> = emptyMap(),
)

@Serializable
public data class ServiceStatusDto(
    /** `ok` | `error`. */
    @SerialName("status") val status: String = "ok",
    @SerialName("version") val version: String? = null,
    @SerialName("message") val message: String? = null,
)

/**
 * `GET /api/v1/connect-apps/settings` → `ConnectAppsSettings`.
 *
 * [subsonicEnabled] is the one field that reports Subsonic availability, and it defaults to
 * `false` on the server — the single hard dependency on an administrator.
 * [transcodingEnabled] and [transcodeMaxBitrateKbps] decide whether the "MP3 320 on mobile data"
 * setting may be shown at all.
 */
@Serializable
public data class ConnectAppsSettingsDto(
    @SerialName("subsonic_enabled") val subsonicEnabled: Boolean = false,
    @SerialName("jellyfin_enabled") val jellyfinEnabled: Boolean = false,
    /** Older servers omit this, so the client must fail closed on exact-track approval. */
    @SerialName("exact_track_approval_supported") val exactTrackApprovalSupported: Boolean = false,
    @SerialName("transcoding_enabled") val transcodingEnabled: Boolean = false,
    /** `mp3` | `opus`. */
    @SerialName("transcode_default_format") val transcodeDefaultFormat: String? = null,
    @SerialName("transcode_max_bitrate_kbps") val transcodeMaxBitrateKbps: Int? = null,
    @SerialName("advertise_server_name") val advertiseServerName: String? = null,
    @SerialName("advertise_server_version") val advertiseServerVersion: String? = null,
    /** `local-only` | `lazy-mb` | `use-scrobble-targets`. */
    @SerialName("discover_mode") val discoverMode: String? = null,
)

/**
 * `GET /api/v1/download/access` → `DownloadAccessResponse`.
 *
 * When [allowed] is false every pin and "Pull local" affordance must be hidden rather than
 * failing later (REQUIREMENTS.md §"Roles and permissions").
 */
@Serializable
public data class DownloadAccessDto(
    @SerialName("allowed") val allowed: Boolean = false,
)

/**
 * `GET /api/v1/library/stats` → `TargetNativeStatsResponse`.
 *
 * Note this is **not** the legacy `LibraryStatsResponse` shape (`album_count`, `db_size_bytes`);
 * the live route returns the fields below. [lastScanAt] is epoch **seconds as a float**.
 */
@Serializable
public data class LibraryStatsDto(
    @SerialName("total_albums") val totalAlbums: Int = 0,
    @SerialName("total_artists") val totalArtists: Int = 0,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_size_bytes") val totalSizeBytes: Long = 0,
    @SerialName("format_breakdown") val formatBreakdown: Map<String, Int> = emptyMap(),
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("local_only_count") val localOnlyCount: Int = 0,
    @SerialName("last_scan_at") val lastScanAt: Double? = null,
)

/**
 * `GET /api/v1/me/scrobble-preferences` → `ScrobblePreferences`.
 *
 * Used to label the scrobble toggle with whatever destination is actually configured, instead of
 * hard-coding ListenBrainz as screen 12 does.
 */
@Serializable
public data class ScrobblePreferencesDto(
    @SerialName("scrobble_to_lastfm") val scrobbleToLastfm: Boolean = false,
    @SerialName("scrobble_to_listenbrainz") val scrobbleToListenbrainz: Boolean = false,
    @SerialName("navidrome_handles_external_scrobbles") val navidromeHandlesExternalScrobbles: Boolean = true,
    /** `listenbrainz` | `lastfm`. */
    @SerialName("primary_music_source") val primaryMusicSource: String? = null,
    /** `full` | `track_hidden` | `offline`. */
    @SerialName("now_playing_visibility") val nowPlayingVisibility: String? = null,
    @SerialName("auto_request_personal_mix") val autoRequestPersonalMix: Boolean = false,
    /** `none` | `pending` | `approved` | `rejected` | `revoked`. */
    @SerialName("auto_request_state") val autoRequestState: String? = null,
)
