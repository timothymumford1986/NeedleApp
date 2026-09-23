package app.needler.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the app is willing to install, decided against literal GitHub payloads.
 *
 * The one test here that is not a formality is the one about the watch APK. `wear/build.gradle.kts`
 * sets `applicationId = "app.needler"` deliberately — Wear pairing is keyed on it — and the release
 * workflow signs both APKs with the same key and publishes them side by side. An asset picker that
 * took the first `.apk` it saw would therefore install the watch app over the phone app, and the
 * platform would accept it: same package, same signature, same versionCode. The listener's data
 * would survive, because a signed update preserves it, but their phone app would be replaced by a
 * watch UI and the only way back would be another install.
 */
class GitHubReleaseParserTest {

    @Test
    fun `a well-formed release yields everything the banner and the download need`() {
        val update = GitHubReleaseParser.parse(release())

        assertNotNull(update)
        requireNotNull(update)
        assertEquals("v1.2.3", update.tagName)
        assertEquals("1.2.3", update.versionName)
        assertEquals(10_203L, update.versionCode)
        assertEquals(3_981_312L, update.sizeBytes)
        assertEquals(
            NeedleAppRepository.DOWNLOAD_URL_PREFIX + "v1.2.3/needler-v1.2.3.apk",
            update.downloadUrl,
        )
        assertEquals("Gapless playback, and a fix for the crate.", update.releaseNotes)
    }

    @Test
    fun `the watch APK is never chosen, because it shares the phone package`() {
        val update = GitHubReleaseParser.parse(
            release(
                assets = listOf(
                    asset("needler-wear-v1.2.3.apk", "v1.2.3/needler-wear-v1.2.3.apk", 2_100_000L),
                    asset("needler-v1.2.3.apk", "v1.2.3/needler-v1.2.3.apk", 3_981_312L),
                ),
            ),
        )

        requireNotNull(update)
        assertEquals(
            NeedleAppRepository.DOWNLOAD_URL_PREFIX + "v1.2.3/needler-v1.2.3.apk",
            update.downloadUrl,
        )
    }

    @Test
    fun `a release with only the watch APK offers nothing at all`() {
        val update = GitHubReleaseParser.parse(
            release(
                assets = listOf(
                    asset("needler-wear-v1.2.3.apk", "v1.2.3/needler-wear-v1.2.3.apk", 2_100_000L),
                ),
            ),
        )

        assertNull(update)
    }

    /**
     * `browser_download_url` arrives in a document fetched over the network. If it were trusted as
     * "wherever to get an APK from", the update path would be exactly as trustworthy as whatever
     * that document happened to say — which is the difference between fetching a signed release and
     * fetching an arbitrary file and asking the platform to install it.
     */
    @Test
    fun `a download URL anywhere but this repository's releases is refused`() {
        val elsewhere = """
            {
              "name": "needler-v1.2.3.apk",
              "state": "uploaded",
              "size": 3981312,
              "browser_download_url": "https://example.invalid/needler-v1.2.3.apk"
            }
        """.trimIndent()

        assertNull(GitHubReleaseParser.parse(release(assets = listOf(elsewhere))))
    }

    @Test
    fun `a draft or a prerelease is refused even though the endpoint should never return one`() {
        assertNull(GitHubReleaseParser.parse(release(draft = true)))
        assertNull(GitHubReleaseParser.parse(release(prerelease = true)))
    }

    @Test
    fun `a tag the release workflow would have rejected is refused here too`() {
        assertNull(GitHubReleaseParser.parse(release(tag = "nightly")))
    }

    @Test
    fun `an implausible size is refused before a single byte is requested`() {
        assertNull(
            GitHubReleaseParser.parse(
                release(assets = listOf(asset("needler-v1.2.3.apk", "v1.2.3/needler-v1.2.3.apk", 0L))),
            ),
        )
        assertNull(
            GitHubReleaseParser.parse(
                release(
                    assets = listOf(
                        asset(
                            "needler-v1.2.3.apk",
                            "v1.2.3/needler-v1.2.3.apk",
                            GitHubReleaseParser.MAX_APK_BYTES + 1L,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `an asset still uploading is not a candidate`() {
        val json = """
            {
              "tag_name": "v1.2.3",
              "draft": false,
              "prerelease": false,
              "body": "",
              "assets": [
                {
                  "name": "needler-v1.2.3.apk",
                  "state": "starter",
                  "size": 3981312,
                  "browser_download_url": "${NeedleAppRepository.DOWNLOAD_URL_PREFIX}v1.2.3/needler-v1.2.3.apk"
                }
              ]
            }
        """.trimIndent()

        assertNull(GitHubReleaseParser.parse(json))
    }

    @Test
    fun `a body that is not JSON, or not an object, is silently nothing`() {
        assertNull(GitHubReleaseParser.parse(""))
        assertNull(GitHubReleaseParser.parse("not json at all"))
        assertNull(GitHubReleaseParser.parse("[]"))
        // The shape GitHub actually returns when a repository has no releases.
        assertNull(GitHubReleaseParser.parse("""{"message":"Not Found"}"""))
    }

    @Test
    fun `a field of the wrong type is tolerated rather than thrown`() {
        val json = """
            {
              "tag_name": { "unexpected": "object" },
              "assets": []
            }
        """.trimIndent()

        assertNull(GitHubReleaseParser.parse(json))
    }

    @Test
    fun `a null body reads as no release notes, not as the string null`() {
        val update = GitHubReleaseParser.parse(release(body = null))

        requireNotNull(update)
        assertEquals("", update.releaseNotes)
    }

    // ---- payload builders ---------------------------------------------------

    private fun release(
        tag: String = "v1.2.3",
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String? = "Gapless playback, and a fix for the crate.",
        assets: List<String> = listOf(
            asset("needler-v1.2.3.apk", "v1.2.3/needler-v1.2.3.apk", 3_981_312L),
        ),
    ): String = """
        {
          "tag_name": "$tag",
          "name": "Needler $tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": ${if (body == null) "null" else "\"$body\""},
          "author": { "login": "timothymumford1986" },
          "assets": [${assets.joinToString(separator = ",")}]
        }
    """.trimIndent()

    private fun asset(name: String, path: String, size: Long): String = """
        {
          "name": "$name",
          "state": "uploaded",
          "content_type": "application/vnd.android.package-archive",
          "size": $size,
          "browser_download_url": "${NeedleAppRepository.DOWNLOAD_URL_PREFIX}$path"
        }
    """.trimIndent()
}
