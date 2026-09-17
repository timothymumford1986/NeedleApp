package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/auth/login`. The account password, never an app-password. */
@Serializable
public data class LoginRequestDto(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
)

/**
 * `POST /api/v1/auth/login` → `AuthResponse`.
 *
 * [token] is the standard bearer. Needler uses it only to mint a device session and an
 * app-password, then discards it (REQUIREMENTS.md §Authentication).
 */
@Serializable
public data class AuthResponseDto(
    @SerialName("token") val token: String,
    @SerialName("user") val user: UserDto,
)

/** Body of `POST /api/v1/auth/device-sessions`. Max 80 characters after whitespace collapsing. */
@Serializable
public data class DeviceSessionRequestDto(
    @SerialName("device_name") val deviceName: String,
)

/**
 * `POST /api/v1/auth/device-sessions` → `DeviceSessionResponse` (HTTP 200, not 201).
 *
 * [token] is the companion bearer: 30 days, fixed, does not slide on use. Minting with the same
 * `device_name` again revokes the previous companion token for that label.
 */
@Serializable
public data class DeviceSessionResponseDto(
    @SerialName("token") val token: String,
    @SerialName("user") val user: UserDto,
)

/** `GET /api/v1/auth/me` → `UserResponse`, also nested in the two auth responses. */
@Serializable
public data class UserDto(
    @SerialName("id") val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    /** `admin` | `trusted` | `user`. Decides whether a request executes or queues for approval. */
    @SerialName("role") val role: String = "user",
    @SerialName("email") val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("username_display") val usernameDisplay: String? = null,
    @SerialName("providers") val providers: List<String> = emptyList(),
    @SerialName("musicbrainz_source") val musicbrainzSource: MusicBrainzSourceDto? = null,
)

/**
 * Opaque provider-identity token. A change here means MBIDs in the mirror came from a different
 * upstream identity and the mirror must be treated as a different server.
 */
@Serializable
public data class MusicBrainzSourceDto(
    @SerialName("source_mode") val sourceMode: String = "",
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("generation") val generation: Int = 0,
)

/** `GET /api/v1/auth/providers` → `AuthProvidersResponse`. Public: no bearer required. */
@Serializable
public data class AuthProvidersDto(
    @SerialName("local") val local: Boolean = false,
    @SerialName("plex") val plex: Boolean = false,
    @SerialName("jellyfin") val jellyfin: Boolean = false,
    @SerialName("oidc") val oidc: Boolean = false,
)

/** `GET /api/v1/auth/sessions` → `SessionListResponse`. */
@Serializable
public data class SessionListDto(
    @SerialName("sessions") val sessions: List<SessionDto> = emptyList(),
)

@Serializable
public data class SessionDto(
    @SerialName("id") val id: String = "",
    /** ISO-8601 with offset, e.g. `2026-09-17T10:11:12.345678+00:00`. */
    @SerialName("issued_at") val issuedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    /** `standard` | `companion`. */
    @SerialName("session_kind") val sessionKind: String = "standard",
)

/** Body of `POST /api/v1/connect-apps/app-passwords`. Needler always sends the name `Needler`. */
@Serializable
public data class AppPasswordCreateRequestDto(
    @SerialName("name") val name: String,
)

/**
 * `POST /api/v1/connect-apps/app-passwords` → `AppPasswordCreateResponse` (HTTP 200).
 *
 * [secret] is returned exactly once and is never re-fetchable, so a failed write to
 * Keystore-backed storage must abort onboarding and revoke this app-password.
 */
@Serializable
public data class AppPasswordCreateResponseDto(
    @SerialName("secret") val secret: String,
    @SerialName("app_password") val appPassword: AppPasswordDto,
)

/** Display-only view of one app-password. Carries no secret. */
@Serializable
public data class AppPasswordDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("last_client") val lastClient: String? = null,
)

/**
 * `GET /api/v1/connect-apps/app-passwords` → `AppPasswordListResponse`.
 *
 * [cap] is 25. Re-login reuses the `Needler` slot by revoking it and creating a replacement.
 */
@Serializable
public data class AppPasswordListDto(
    @SerialName("cap") val cap: Int = 25,
    @SerialName("active_count") val activeCount: Int = 0,
    @SerialName("items") val items: List<AppPasswordDto> = emptyList(),
)
