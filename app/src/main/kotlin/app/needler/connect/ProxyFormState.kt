package app.needler.connect

import app.needler.core.network.ProxyCredentials
import app.needler.core.network.ProxyHeader
import app.needler.core.network.ProxyPresets

/**
 * The optional half of the Connect form: fixed headers for an edge proxy in front of the server.
 *
 * Hidden behind a disclosure and collapsed by default. Almost nobody has a proxy, and a form that
 * opens by asking about one teaches every user that setting this app up is complicated.
 *
 * The three presets exist because the mechanism - "send these headers with every request" - is not
 * what a user knows about their deployment. A Cloudflare user knows they made a *service token*; a
 * user behind a basic-auth reverse proxy knows a username and a password. Both end up as headers,
 * but neither should have to work that out.
 */
data class ProxyFormState(
    /** Whether the disclosure is open. */
    val expanded: Boolean = false,
    val preset: ProxyPreset = ProxyPreset.CloudflareAccess,
    val cloudflareClientId: String = "",
    val cloudflareClientSecret: String = "",
    val basicUsername: String = "",
    val basicPassword: String = "",
    /** The raw editor, for a gateway nobody has heard of. Always at least one blank row. */
    val customHeaders: List<CustomHeaderDraft> = listOf(CustomHeaderDraft()),
    /** Why the last attempt was refused before it left the device, or null. */
    val problem: String? = null,
) {

    /** True when this form would send something. Drives "you have not set a credential yet". */
    val isConfigured: Boolean get() = credentials().isNotEmpty

    /**
     * What the form means, as headers.
     *
     * Only the chosen preset contributes: a user who typed a Cloudflare token, thought better of
     * it and switched to basic auth does not silently send both.
     */
    fun credentials(): ProxyCredentials = when (preset) {
        ProxyPreset.CloudflareAccess ->
            ProxyPresets.cloudflareAccess(cloudflareClientId, cloudflareClientSecret)

        ProxyPreset.BasicAuth -> ProxyPresets.basicAuth(basicUsername, basicPassword)

        ProxyPreset.Custom -> ProxyCredentials.of(
            customHeaders
                .filter { it.name.isNotBlank() || it.value.isNotBlank() }
                .map { ProxyHeader(it.name.trim(), it.value) },
        )
    }

    /**
     * The first reason this form cannot be sent, or null when it is fine (including entirely empty,
     * which is the normal case).
     *
     * Checked before the first request goes out rather than after: a rejected header name produces
     * a `401` from the proxy that reads exactly like a wrong credential, and a `\r\n` in a value is
     * a request-splitting attempt that OkHttp would throw on from inside an interceptor.
     */
    fun validationProblem(): String? = when (preset) {
        ProxyPreset.CloudflareAccess -> halfFilled(
            first = cloudflareClientId,
            second = cloudflareClientSecret,
            message = "A Cloudflare Access service token needs both the Client ID and the " +
                "Client Secret.",
        )

        ProxyPreset.BasicAuth -> halfFilled(
            first = basicUsername,
            second = basicPassword,
            message = "Proxy basic auth needs both a username and a password.",
        )

        ProxyPreset.Custom -> customHeaders
            .filter { it.name.isNotBlank() || it.value.isNotBlank() }
            .firstNotNullOfOrNull { draft ->
                ProxyCredentials.validate(draft.name, draft.value)?.let { problem ->
                    val named: String = draft.name.trim().ifEmpty { "That header" }
                    named + ": " + problem.message
                }
            }
    }

    private fun halfFilled(first: String, second: String, message: String): String? {
        val one: Boolean = first.isNotBlank()
        val other: Boolean = second.isNotBlank()
        return if (one == other) null else message
    }

    /** Replaces one row of the raw editor, growing the list if the last row is being filled in. */
    fun withCustomHeader(index: Int, name: String, value: String): ProxyFormState {
        if (index !in customHeaders.indices) return this
        val updated: MutableList<CustomHeaderDraft> = customHeaders.toMutableList()
        updated[index] = CustomHeaderDraft(name = name, value = value)
        return copy(customHeaders = updated, problem = null)
    }

    /** Adds a blank row, up to the transport's own ceiling. */
    fun withExtraCustomHeader(): ProxyFormState {
        if (customHeaders.size >= ProxyCredentials.MAX_HEADERS) return this
        return copy(customHeaders = customHeaders + CustomHeaderDraft(), problem = null)
    }

    companion object {

        /**
         * Rebuilds the form from what is already saved, so re-onboarding against the same server
         * does not silently drop the headers that were making it reachable.
         *
         * A saved Cloudflare pair comes back as the Cloudflare preset - which is what the user
         * typed - and anything else as raw rows. Values are restored: they are credentials, so they
         * are rendered masked and the form is the only place they are ever shown.
         */
        fun from(saved: ProxyCredentials): ProxyFormState {
            if (saved.isEmpty) return ProxyFormState()
            val byName: Map<String, String> = saved.headers.associate { it.name.lowercase() to it.value }
            val clientId: String? = byName[ProxyPresets.CLOUDFLARE_CLIENT_ID.lowercase()]
            val clientSecret: String? = byName[ProxyPresets.CLOUDFLARE_CLIENT_SECRET.lowercase()]
            if (saved.headers.size == 2 && clientId != null && clientSecret != null) {
                return ProxyFormState(
                    expanded = true,
                    preset = ProxyPreset.CloudflareAccess,
                    cloudflareClientId = clientId,
                    cloudflareClientSecret = clientSecret,
                )
            }
            return ProxyFormState(
                expanded = true,
                preset = ProxyPreset.Custom,
                customHeaders = saved.headers.map { CustomHeaderDraft(it.name, it.value) },
            )
        }
    }
}

/** One row of the raw header editor. */
data class CustomHeaderDraft(
    val name: String = "",
    val value: String = "",
)

/**
 * The shapes a proxy credential comes in.
 *
 * Cloudflare Access is named rather than folded into "custom" because a user searching their own
 * settings for "service token" has to be able to find it here; the same argument would apply to
 * Authelia or authentik if either wanted anything but a header Needler already supports through
 * [Custom].
 */
enum class ProxyPreset(val label: String) {
    CloudflareAccess("Cloudflare"),
    BasicAuth("Basic auth"),
    Custom("Custom"),
}

/** Which single-value field of the proxy form a change refers to. */
enum class ProxyField {
    CloudflareClientId,
    CloudflareClientSecret,
    BasicUsername,
    BasicPassword,
}
