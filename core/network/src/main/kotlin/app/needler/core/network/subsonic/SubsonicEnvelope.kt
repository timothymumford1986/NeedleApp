package app.needler.core.network.subsonic

import app.needler.core.network.ApiLane
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NetworkError
import app.needler.core.network.subsonic.dto.SubsonicErrorDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The standard Subsonic error codes, as DroppedNeedle's shim emits them.
 *
 * The two that matter most to the UI both arrive as a `status=failed` envelope over **HTTP 200**,
 * and they mean completely different things:
 *  * [WRONG_CREDENTIALS] / [INVALID_APIKEY] — the app-password is dead; require re-onboarding;
 *  * [GENERIC] with the "API is disabled" message — an administrator must switch
 *    `subsonic_enabled` on; the user can do nothing themselves.
 *
 * [GENERIC] is overloaded: the shim also uses it for an unknown method and for its own rate-limit
 * rejection (which arrives with a `Retry-After` header), so code 0 alone is never enough.
 */
public object SubsonicErrorCode {
    public const val GENERIC: Int = 0
    public const val PARAM_MISSING: Int = 10
    public const val WRONG_CREDENTIALS: Int = 40
    public const val TOKEN_AUTH_NOT_SUPPORTED: Int = 41
    public const val LDAP_NOT_SUPPORTED: Int = 42
    public const val CONFLICTING_AUTH: Int = 43
    public const val INVALID_APIKEY: Int = 44
    public const val NOT_AUTHORIZED: Int = 50
    public const val NOT_FOUND: Int = 70
}

/** A parsed `subsonic-response` envelope: metadata, plus either an error or a payload. */
internal class SubsonicEnvelope(
    val status: String,
    val version: String,
    val type: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean,
    val error: SubsonicErrorDto?,
    private val body: JsonObject,
) {

    val isFailed: Boolean get() = status.equals("failed", ignoreCase = true) || error != null

    /** The payload under [key], or null when the endpoint returns no payload (ping, star, …). */
    fun payload(key: String): JsonElement? = body[key]
}

internal object SubsonicEnvelopeParser {

    private const val ROOT_KEY = "subsonic-response"

    /**
     * Parse the envelope generically, so one code path serves every endpoint: the wrapper, the
     * status, the error and the metadata are read here, and only the payload is handed to a
     * typed serializer by the caller.
     *
     * @throws NetworkError.Serialisation when the body is not a Subsonic envelope at all — which
     *   is what a captive portal or a non-DroppedNeedle URL produces.
     */
    fun parse(json: Json, body: String): SubsonicEnvelope {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (failure: Exception) {
            throw NetworkError.Serialisation(ApiLane.Subsonic, failure)
        }
        val envelope = root[ROOT_KEY]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: throw NetworkError.Serialisation(
            ApiLane.Subsonic,
            IllegalArgumentException("Response has no \"$ROOT_KEY\" object"),
        )

        val error = envelope["error"]?.let { element ->
            runCatching { json.decodeFromJsonElement(SubsonicErrorDto.serializer(), element) }.getOrNull()
        }
        return SubsonicEnvelope(
            status = envelope.stringOrNull("status") ?: "",
            version = envelope.stringOrNull("version") ?: "",
            type = envelope.stringOrNull("type"),
            serverVersion = envelope.stringOrNull("serverVersion"),
            openSubsonic = envelope["openSubsonic"]?.let {
                runCatching { it.jsonPrimitive.content == "true" }.getOrDefault(false)
            } ?: false,
            error = error,
            body = envelope,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /**
     * Subsonic `error.code` to [NetworkError], with the two special cases REQUIREMENTS.md's
     * failure table needs kept apart.
     *
     * @param retryAfterSeconds the response's `Retry-After`, which is the only way to tell the
     *   shim's rate-limit rejection (code 0) from its other uses of code 0.
     */
    fun toNetworkError(
        error: SubsonicErrorDto?,
        retryAfterSeconds: Long?,
        credentials: CredentialProvider,
    ): NetworkError {
        val code = error?.code ?: SubsonicErrorCode.GENERIC
        val message = error?.message
        return when (code) {
            SubsonicErrorCode.WRONG_CREDENTIALS,
            SubsonicErrorCode.INVALID_APIKEY,
            -> {
                credentials.onAppPasswordRejected()
                NetworkError.Unauthorised(ApiLane.Subsonic, code)
            }

            SubsonicErrorCode.TOKEN_AUTH_NOT_SUPPORTED,
            SubsonicErrorCode.LDAP_NOT_SUPPORTED,
            -> NetworkError.Unauthorised(ApiLane.Subsonic, code)

            SubsonicErrorCode.PARAM_MISSING,
            SubsonicErrorCode.CONFLICTING_AUTH,
            -> NetworkError.InvalidRequest(ApiLane.Subsonic, null, code.toString(), message)

            SubsonicErrorCode.NOT_AUTHORIZED -> NetworkError.Forbidden(ApiLane.Subsonic, message)
            SubsonicErrorCode.NOT_FOUND -> NetworkError.NotFound(ApiLane.Subsonic, message)

            SubsonicErrorCode.GENERIC -> when {
                retryAfterSeconds != null -> NetworkError.RateLimited(retryAfterSeconds, ApiLane.Subsonic)
                looksDisabled(message) -> NetworkError.SubsonicProtocolDisabled
                else -> NetworkError.SubsonicFailure(code, message)
            }

            else -> NetworkError.SubsonicFailure(code, message)
        }
    }

    /**
     * The disabled-protocol envelope is code 0 with the server's fixed message
     * "The Subsonic API is disabled on this server." Matched loosely so a reworded server message
     * still lands on the actionable outcome rather than on a generic failure.
     */
    private fun looksDisabled(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return text.contains("disabled") && (text.contains("subsonic") || text.contains("api"))
    }
}
