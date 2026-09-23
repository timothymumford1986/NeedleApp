package app.needler.update

/**
 * The tag arithmetic: turning a GitHub tag such as `v1.2.3` into the `versionCode` that the APK
 * published under that tag actually carries.
 *
 * This is the whole of the "is there a newer version?" question. Everything else in this package is
 * plumbing around the comparison this object makes possible.
 *
 * ## Why the formula lives here at all
 *
 * `buildConfig` is not enabled for `:app`, so there is no `BuildConfig.VERSION_CODE` to read and no
 * generated constant naming the release we are running. The installed version is read back from the
 * platform instead (see [InstalledVersion]), which gives an honest `Long`. The *candidate* version
 * has to come from somewhere too, and the only thing GitHub tells us about a release is its
 * `tag_name`. So the number has to be derived from the tag by repeating, exactly, what
 * `.github/workflows/release.yml` does in its "Derive the version from the tag" step:
 *
 * ```sh
 * SEMVER="${TAG#v}"
 * MAJOR="${SEMVER%%.*}"; REST="${SEMVER#*.}"
 * MINOR="${REST%%.*}"; PATCH="${REST#*.}"; PATCH="${PATCH%%-*}"
 * CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
 * ```
 *
 * `v1.2.3` becomes `10203`, which is the number baked into that release's APK and therefore the
 * number Android itself will compare against when the install is offered.
 *
 * ## Faithful, not improved
 *
 * The shell above has two quirks, and both are reproduced here rather than corrected.
 *
 *  * A two-part tag, `v1.2`, leaves `PATCH` equal to `MINOR` because `${REST#*.}` is a no-op when
 *    `REST` holds no dot — so `v1.2` yields `10202`, not `10200`.
 *  * A minor or patch component of 100 or more collides with the component above it: `v1.0.100`
 *    and `v1.1.0` both come to `10100`.
 *
 * Neither is a good property, but this object's job is to predict the number the release workflow
 * put in the APK, not to compute the number it ought to have put there. "Improving" the formula
 * here would produce a value that disagrees with the one the platform is holding, and the visible
 * symptom would be an update offered that Android then refuses as a downgrade — or, worse, an
 * update silently never offered. If the formula is ever fixed, it must be fixed in the workflow
 * first and here second.
 *
 * ## Never compare version strings
 *
 * There is deliberately no string comparison anywhere in this package. `"1.10.0" < "1.9.0"`
 * lexicographically, and a natural-order or `compareTo` heuristic over dotted strings is a
 * well-worn source of "the app says it is up to date and it is two releases behind". Two `Long`s,
 * derived the same way at both ends, cannot disagree about ordering.
 */
object ReleaseTag {

    /**
     * The `versionCode` the release tagged [tag] was built with, or `null` when the tag is not a
     * shape the release workflow would have accepted.
     *
     * A `null` is not an error to report — it means a tag nobody can install from, which is
     * indistinguishable, from the user's point of view, from there being no new release. The caller
     * stays silent either way.
     */
    fun versionCodeOf(tag: String): Long? {
        val semver = tag.trim().removePrefix("v")
        if (semver.isEmpty()) return null

        // The four expansions above, in order. Kotlin's substringBefore/substringAfter return the
        // whole receiver when the delimiter is absent, which is precisely what `%%.*` and `#*.` do
        // in the shell, so the no-dot cases fall out identically instead of needing a branch.
        val major = semver.substringBefore('.')
        val rest = semver.substringAfter('.')
        val minor = rest.substringBefore('.')
        val patch = rest.substringAfter('.').substringBefore('-')

        val majorValue = componentOrNull(major) ?: return null
        val minorValue = componentOrNull(minor) ?: return null
        val patchValue = componentOrNull(patch) ?: return null

        val code = majorValue * 10_000L + minorValue * 100L + patchValue
        // A versionCode is a 32-bit signed integer on the platform, whatever `longVersionCode`
        // reports; a tag that overflowed it never produced an installable APK in the first place.
        return if (code <= Int.MAX_VALUE.toLong()) code else null
    }

    /**
     * The version as a human reads it — `v1.2.3` becomes `1.2.3` — which is what the release
     * workflow passes as `versionName` and therefore what the banner should say.
     */
    fun versionNameOf(tag: String): String = tag.trim().removePrefix("v")

    /**
     * One component of the tag.
     *
     * The workflow's guard is `[ "$X" -eq "$X" ]`, which is shell for "is this an integer". This is
     * deliberately stricter: digits only, so a signed or whitespace-padded component is rejected
     * rather than quietly accepted. A negative component cannot appear in a real tag, and refusing
     * it costs nothing.
     */
    private fun componentOrNull(text: String): Long? {
        if (text.isEmpty() || text.length > MAX_COMPONENT_DIGITS) return null
        if (!text.all { character -> character in '0'..'9' }) return null
        return text.toLongOrNull()
    }

    /** Enough for any real component, short enough that the multiplication cannot overflow. */
    private const val MAX_COMPONENT_DIGITS = 6
}
