package app.needler.core.network.capability

import app.needler.core.network.ApiLane
import app.needler.core.network.NetworkError
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.v1.V1Api

/**
 * Everything one connection discovered about a server. A DTO: no domain types, no persistence.
 *
 * Needler targets no fixed DroppedNeedle version — it discovers what the server can do once per
 * connection and disables features rather than failing (REQUIREMENTS.md §"Capability negotiation
 * on connect").
 */
public data class ServerCapabilitiesDto(
    /** `/api/v1/version` — DroppedNeedle's own version string. */
    public val serverVersion: String? = null,
    public val serverBuildDate: String? = null,
    /** `advertise_server_name`, which is also what the Subsonic envelope reports as `type`. */
    public val serverName: String? = null,
    /** The Subsonic protocol version from the `ping` envelope, e.g. `1.16.1`. */
    public val subsonicProtocolVersion: String? = null,
    /** True when the `ping` envelope carried `openSubsonic: true`. */
    public val openSubsonic: Boolean = false,
    /** `subsonic_enabled` — the one hard dependency on an administrator. */
    public val subsonicEnabled: Boolean = false,
    /** Extension name to advertised versions, from `getOpenSubsonicExtensions`. */
    public val extensions: Map<String, List<Int>> = emptyMap(),
    /** `transcoding_enabled` from the server's Connect Apps settings. */
    public val transcodingEnabled: Boolean = false,
    /** `mp3` | `opus`. */
    public val transcodeDefaultFormat: String? = null,
    public val transcodeMaxBitrateKbps: Int? = null,
    /** `allowed` from `/api/v1/download/access` — the admin gate on library download. */
    public val downloadAllowed: Boolean = false,
    /** `admin` | `trusted` | `user`, from `/api/v1/auth/me`. */
    public val role: String? = null,
    /** Older servers omit this; it is false there, so exact-track approval must fail closed. */
    public val exactTrackApprovalSupported: Boolean = false,
    public val probedAtEpochMillis: Long = 0,
) {

    /** True when the server advertises [name] at [version]. Anything absent is unavailable. */
    public fun hasExtension(name: String, version: Int = 1): Boolean =
        extensions[name]?.contains(version) == true

    /**
     * Whether "Stream on mobile data: MP3 320" may be shown at all: the `transcoding` extension
     * must be advertised **and** the server must report transcoding enabled. Neither is
     * guaranteed — ffmpeg may be absent.
     */
    public val transcodingUsable: Boolean
        get() = transcodingEnabled && hasExtension(EXTENSION_TRANSCODING)

    /** Whether `getLyricsBySongId` may be offered (v2 in the UI, but cheap to record). */
    public val lyricsUsable: Boolean get() = hasExtension(EXTENSION_SONG_LYRICS)

    public companion object {
        public const val EXTENSION_API_KEY_AUTHENTICATION: String = "apiKeyAuthentication"
        public const val EXTENSION_FORM_POST: String = "formPost"
        public const val EXTENSION_TRANSCODE_OFFSET: String = "transcodeOffset"
        public const val EXTENSION_TRANSCODING: String = "transcoding"
        public const val EXTENSION_SONG_LYRICS: String = "songLyrics"
        public const val EXTENSION_PLAYBACK_REPORT: String = "playbackReport"
        public const val EXTENSION_INDEX_BASED_QUEUE: String = "indexBasedQueue"
    }
}

/** The outcomes of [CapabilityProbe.probe]. Each one needs a different response from the UI. */
public sealed interface CapabilityProbeResult {

    /** Both lanes work. Persist [capabilities] against this server identity. */
    public data class Ready(public val capabilities: ServerCapabilitiesDto) : CapabilityProbeResult

    /**
     * The `/api/v1` lane works but `subsonic_enabled` is off, and only an administrator can change
     * it. Setup cannot complete, and the message must name the exact setting rather than blaming
     * the user's credentials — this is the gate REQUIREMENTS.md calls out as the one hard
     * dependency on an admin.
     *
     * [partial] carries everything learned before the gate, so the setup screen can still show the
     * server's name and version.
     */
    public data class SubsonicDisabled(
        public val partial: ServerCapabilitiesDto,
        /** The server-side setting an administrator must switch on. */
        public val settingKey: String = "subsonic_enabled",
        /** Where it lives in DroppedNeedle's web UI. */
        public val settingLocation: String = "Settings → Connect Apps → Subsonic API",
    ) : CapabilityProbeResult

    /**
     * The companion bearer is gone (`401`). Playback stays fully functional; the app prompts to
     * sign in again and keeps going. This is not a setup failure.
     */
    public data class SessionExpired(public val partial: ServerCapabilitiesDto) : CapabilityProbeResult

    /**
     * The app-password was rejected (Subsonic code 40 or 44). Full re-onboarding is required,
     * because the secret is never re-fetchable.
     */
    public data class AppPasswordRejected(
        public val partial: ServerCapabilitiesDto,
        public val subsonicCode: Int?,
    ) : CapabilityProbeResult

    /** Timeout, DNS failure or an untrusted certificate: treat as offline and retry later. */
    public data class Unreachable(public val error: NetworkError) : CapabilityProbeResult

    /** Anything else, including a `5xx` or a URL that is not DroppedNeedle at all. */
    public data class Failed(public val error: NetworkError) : CapabilityProbeResult
}

/**
 * Runs the negotiation sequence from REQUIREMENTS.md §"Capability negotiation on connect", in
 * order, and stops at the first outcome the UI has to act on:
 *
 * ```
 * saved URL → GET /api/v1/version → GET /api/v1/auth/me → GET /api/v1/connect-apps/settings
 *           → subsonic_enabled? ── no ──▶ SubsonicDisabled (ask an admin)
 *                    │ yes
 *                    ▼
 *             getOpenSubsonicExtensions → ping → GET /api/v1/download/access → Ready
 * ```
 *
 * `GET /api/v1/auth/me` is one step beyond the flowchart: the role decides whether requests
 * execute immediately or queue for approval, and the probe is the natural place to read it.
 *
 * `ping` is likewise an addition — it is the cheapest way to confirm the app-password actually
 * works and to read the server's advertised name and `openSubsonic` flag, so onboarding cannot
 * "succeed" with a credential that is already dead.
 */
public class CapabilityProbe(
    private val v1: V1Api,
    private val subsonic: SubsonicApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    public suspend fun probe(): CapabilityProbeResult {
        var capabilities = ServerCapabilitiesDto(probedAtEpochMillis = nowMillis())

        // 1. Reachability and identity of the /api/v1 lane.
        try {
            val version = v1.version()
            capabilities = capabilities.copy(
                serverVersion = version.version.takeIf { it.isNotEmpty() },
                serverBuildDate = version.buildDate,
            )
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }

        // 2. Role, which gates the request and download affordances.
        try {
            capabilities = capabilities.copy(role = v1.me().role)
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }

        // 3. The Connect Apps gate.
        val settings = try {
            v1.connectAppsSettings()
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }
        capabilities = capabilities.copy(
            subsonicEnabled = settings.subsonicEnabled,
            transcodingEnabled = settings.transcodingEnabled,
            transcodeDefaultFormat = settings.transcodeDefaultFormat,
            transcodeMaxBitrateKbps = settings.transcodeMaxBitrateKbps,
            exactTrackApprovalSupported = settings.exactTrackApprovalSupported,
            serverName = settings.advertiseServerName,
        )
        if (!settings.subsonicEnabled) {
            return CapabilityProbeResult.SubsonicDisabled(capabilities)
        }

        // 4. What the compat shim advertises. Absent means unavailable, however plausible.
        try {
            capabilities = capabilities.copy(
                extensions = subsonic.openSubsonicExtensions()
                    .filter { it.name.isNotEmpty() }
                    .associate { it.name to it.versions },
            )
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }

        // 5. Confirm the app-password works, and read the envelope's identity fields.
        try {
            val info = subsonic.ping()
            capabilities = capabilities.copy(
                subsonicProtocolVersion = info.version.takeIf { it.isNotEmpty() },
                openSubsonic = info.openSubsonic,
                serverName = info.type ?: capabilities.serverName,
            )
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }

        // 6. The admin gate on library download.
        try {
            capabilities = capabilities.copy(downloadAllowed = v1.downloadAccess().allowed)
        } catch (failure: NetworkError) {
            return failure.toOutcome(capabilities)
        }

        return CapabilityProbeResult.Ready(capabilities)
    }

    private fun NetworkError.toOutcome(partial: ServerCapabilitiesDto): CapabilityProbeResult =
        when (this) {
            is NetworkError.SubsonicProtocolDisabled ->
                CapabilityProbeResult.SubsonicDisabled(partial.copy(subsonicEnabled = false))

            is NetworkError.Unauthorised -> when (lane) {
                ApiLane.V1 -> CapabilityProbeResult.SessionExpired(partial)
                ApiLane.Subsonic -> CapabilityProbeResult.AppPasswordRejected(partial, subsonicCode)
            }

            is NetworkError.Offline, is NetworkError.TlsNotTrusted ->
                CapabilityProbeResult.Unreachable(this)

            else -> CapabilityProbeResult.Failed(this)
        }
}
