package app.needler.update

/**
 * Where Needler is published, in one place.
 *
 * REQUIREMENTS.md "Decisions already fixed" settles distribution: "Signed APK on GitHub releases",
 * with the consequence noted as "No store policy limits, but Auto and Wear need manual enablement".
 * There is no Play Store to hand the update problem to, so the app has to notice a new release
 * itself — which is what this package is.
 */
object NeedleAppRepository {

    const val OWNER: String = "timothymumford1986"

    const val NAME: String = "NeedleApp"

    /**
     * The unauthenticated "latest release" endpoint.
     *
     * GitHub defines `/releases/latest` as the most recent **non-draft, non-prerelease** release,
     * which is exactly the set a listener should be offered, so no client-side filtering of a list
     * is needed. The draft and prerelease flags are still checked on the way in — see
     * [GitHubReleaseParser] — because a check that depends on a remote service honouring its own
     * documentation is a check that can be surprised.
     */
    const val LATEST_RELEASE_URL: String = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"

    /**
     * Every legitimate asset download URL begins with this.
     *
     * The parser refuses any other prefix. `browser_download_url` is a value from a remote
     * document, and the one thing the app must never do with it is fetch an APK from wherever it
     * happens to point: the whole security of the update path is that the bytes come from this
     * repository's releases and are signed with the key that signed what is already installed.
     */
    const val DOWNLOAD_URL_PREFIX: String = "https://github.com/$OWNER/$NAME/releases/download/"

    /**
     * What the release workflow names the phone APK: `needler-v1.2.3.apk`.
     *
     * **The wear APK is a genuine hazard and this is where it is kept out.** `wear/build.gradle.kts`
     * sets `applicationId = "app.needler"` — the same package as the phone app, deliberately,
     * because Wear pairing is keyed on it — and the same workflow signs both with the same key and
     * publishes `needler-wear-v1.2.3.apk` beside `needler-v1.2.3.apk`. An asset picker that took
     * "the first thing ending in .apk" would therefore sooner or later install the watch app over
     * the phone app, and the platform would accept it without complaint: same package, same
     * signature, same versionCode. The listener's music would survive, because the data directory
     * does, but their phone app would be gone and replaced with a watch UI.
     *
     * So the asset is chosen by its exact expected name first, and anything with `wear` in the name
     * is excluded outright as a second lock on the same door.
     */
    fun expectedApkAssetName(tag: String): String = "needler-$tag.apk"
}

/**
 * A release on GitHub that is newer than what is installed and that we could actually install.
 *
 * Everything here has already been validated by [GitHubReleaseParser]: the tag parsed to a
 * [versionCode], the download URL is on [NeedleAppRepository.DOWNLOAD_URL_PREFIX], and the asset is
 * the phone APK rather than the watch one. Nothing downstream re-derives any of it.
 *
 * @property tagName the tag exactly as GitHub reports it, e.g. `v1.2.3`. Kept verbatim because it
 *   is what names the asset and the cached file.
 * @property versionName the tag without its `v`, which is what the release workflow passes as
 *   `versionName` and therefore what the APK will report once installed. This is the only version
 *   string shown to a human.
 * @property versionCode derived by [ReleaseTag.versionCodeOf]; the only value ever compared.
 * @property sizeBytes the asset's `size` field, used to verify the download landed whole. GitHub
 *   reports this from its own storage, so a short read or a truncated proxy response is caught
 *   before anything is handed to the package installer.
 * @property releaseNotes the release `body`, trimmed and capped. Not shown on the one-line banner,
 *   which deliberately says only that an update exists; it is carried so that a "what's new" sheet
 *   can be added later without another network trip.
 */
data class AvailableUpdate(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

/**
 * What a check against GitHub came back with.
 *
 * Four outcomes, and **three of them are silent**. REQUIREMENTS.md treats an unreachable server as
 * a state rather than an error throughout the app ("Offline is a first-class state, not an error"),
 * and an update check has even less claim on the listener's attention than the library does: they
 * did not ask for it, it runs on its own schedule, and it has nothing to say when it fails. A
 * toast reading "could not check for updates" would be the app apologising for something nobody
 * requested. So only [Found] can ever produce a pixel.
 */
sealed interface ReleaseLookup {

    /** A release was read and parsed. Whether it is *newer* is [UpdateCheckPolicy]'s decision. */
    data class Found(val release: AvailableUpdate) : ReleaseLookup

    /**
     * GitHub answered, and the answer was useless: no releases yet, a malformed body, a tag that is
     * not `vMAJOR.MINOR.PATCH`, or no installable phone APK attached. Counts as a completed check,
     * so the 24-hour clock restarts — there is nothing to retry sooner for.
     */
    data object NoUsableRelease : ReleaseLookup

    /**
     * The unauthenticated rate limit — 60 requests an hour per IP — has been spent, or GitHub is
     * shedding load. Carries how long to wait, taken from `Retry-After` or `X-RateLimit-Reset`.
     *
     * This is not a failure of the app. A household behind one NAT address shares the allowance,
     * and so does anyone on a carrier-grade NAT; hitting it with a once-a-day check means something
     * else on the network is talking to GitHub, not that anything here is wrong.
     */
    data class RateLimited(val retryAfterMillis: Long) : ReleaseLookup

    /**
     * No answer at all: no network, DNS failure, TLS failure, a captive portal, a timeout, or a
     * `5xx`. Nothing is persisted for this case, so the check is retried on a short in-memory
     * cooldown rather than in a day's time — a listener who was on a plane at 09:00 should not have
     * to wait until tomorrow.
     */
    data object Unreachable : ReleaseLookup
}
