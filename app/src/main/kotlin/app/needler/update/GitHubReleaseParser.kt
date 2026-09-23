package app.needler.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns the body of `GET /repos/{owner}/{repo}/releases/latest` into an [AvailableUpdate], or into
 * nothing at all.
 *
 * Separate from [GitHubReleaseClient] so that every decision this makes can be tested against a
 * literal JSON string — no server, no MockWebServer, no network permission. The client's job is a
 * request and a status code; this object's job is "is there something here we are willing to
 * install?", which is the part with the interesting failure modes.
 *
 * ## Why there are no `@Serializable` DTOs
 *
 * `:core:network` models both server lanes with `@Serializable` data classes, and that is the right
 * shape there: the DTOs mirror a documented contract, there are hundreds of fields, and the module
 * already applies the Kotlin serialization compiler plugin for them. None of that holds here. The
 * GitHub release object has several dozen fields across three nested author/uploader/reaction
 * objects and we want exactly five of them, from a service whose response shape is not ours to
 * pin down. So the body is walked as a [JsonObject] with [Json.parseToJsonElement], which needs
 * only the runtime library — already on `:app`'s compile classpath through `:core:network`'s
 * `api(libs.kotlinx.serialization.json)` — and no compiler plugin on `:app` at all.
 *
 * Every read goes through the `as? JsonPrimitive` helpers at the bottom rather than the
 * `jsonPrimitive` extensions, because those throw on a type mismatch and a parser for a remote
 * document should return `null` for "that field was an object", not an exception.
 *
 * ## What is rejected, and why each rule exists
 *
 *  * **Drafts and prereleases.** `/releases/latest` should never return one, so this is belt and
 *    braces against the endpoint being changed or misread.
 *  * **A tag the release workflow would not have accepted**, because then there is no way to know
 *    what `versionCode` the APK carries. See [ReleaseTag].
 *  * **The watch APK.** See [NeedleAppRepository.expectedApkAssetName] — this is the rule that
 *    matters most, and it is enforced twice.
 *  * **A download URL that is not on [NeedleAppRepository.DOWNLOAD_URL_PREFIX].** The URL comes
 *    from a remote document; treating it as an arbitrary place to fetch an APK from would make the
 *    whole update path only as trustworthy as whatever that document happened to say.
 *  * **An implausible asset size.** A zero or negative size makes the post-download verification
 *    meaningless, and an absurd one would fill the cache partition before failing. Both are
 *    refused before a single byte is requested.
 */
object GitHubReleaseParser {

    /**
     * The largest asset this will agree to download, in bytes.
     *
     * The release workflow's own note puts the minified release APK at about 3.8 MB; 256 MB is
     * three orders of magnitude of headroom and still small enough that an absurd `size` field
     * cannot be used to fill a device's cache partition.
     */
    const val MAX_APK_BYTES: Long = 256L * 1024L * 1024L

    /** Release notes are carried, not shown; this keeps a pathological body out of memory. */
    private const val MAX_NOTES_CHARS = 8_000

    /**
     * Parse a release document, or return `null`.
     *
     * `null` always means "nothing to offer" and never means "something went wrong that the user
     * should hear about". Every rejection below is silent by design.
     */
    fun parse(body: String): AvailableUpdate? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null

        if (root.flag("draft") || root.flag("prerelease")) return null

        val tag = root.string("tag_name")?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val versionCode = ReleaseTag.versionCodeOf(tag) ?: return null

        val asset = chooseApkAsset(root["assets"] as? JsonArray, tag) ?: return null

        val downloadUrl = asset.string("browser_download_url")?.trim().orEmpty()
        if (!downloadUrl.startsWith(NeedleAppRepository.DOWNLOAD_URL_PREFIX)) return null

        val sizeBytes = asset.long("size") ?: return null
        if (sizeBytes <= 0L || sizeBytes > MAX_APK_BYTES) return null

        return AvailableUpdate(
            tagName = tag,
            versionName = ReleaseTag.versionNameOf(tag),
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            sizeBytes = sizeBytes,
            releaseNotes = root.string("body").orEmpty().trim().take(MAX_NOTES_CHARS),
        )
    }

    /**
     * Pick the phone APK out of the release's assets.
     *
     * Exact name first — the workflow builds it as `needler-$TAG.apk` and there is no reason for it
     * ever to be anything else. The fallback exists only for the case where there is exactly one
     * candidate left after the watch APK and any non-APK asset have been removed, which is the
     * shape a release that renamed its artefact would have. Two ambiguous candidates and no exact
     * match is not a puzzle worth solving quietly: it returns `null` and the check stays silent.
     */
    private fun chooseApkAsset(assets: JsonArray?, tag: String): JsonObject? {
        if (assets == null) return null
        val candidates = assets
            .filterIsInstance<JsonObject>()
            .filter { asset ->
                val name = asset.string("name").orEmpty()
                val uploaded = asset.string("state")?.equals("uploaded", ignoreCase = true) ?: true
                uploaded &&
                    name.endsWith(".apk", ignoreCase = true) &&
                    // The watch APK shares :app's applicationId. It must never be a candidate.
                    !name.contains("wear", ignoreCase = true)
            }
        val expected = NeedleAppRepository.expectedApkAssetName(tag)
        return candidates.firstOrNull { asset ->
            asset.string("name").equals(expected, ignoreCase = true)
        } ?: candidates.singleOrNull()
    }

    // ---- lenient field readers ---------------------------------------------
    //
    // `as? JsonPrimitive` rather than the `jsonPrimitive` extension: the extension throws when the
    // field is an object or an array, and a missing or oddly-typed field in a remote document is an
    // ordinary thing to meet, not an exception. `JsonNull` is itself a primitive whose
    // `contentOrNull` is null, so an explicit `"body": null` reads as absent rather than as "null".

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
