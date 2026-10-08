package app.needler.player.service.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService.LibraryParams
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.player.service.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The browse metadata Android Auto draws a row from.
 *
 * ## Why this file exists
 *
 * [BrowseItems]' own KDoc states a contract with a consequence: "Media3 asserts that both are set on
 * anything returned from a browse callback, so a missing flag is a crash in the car rather than a
 * cosmetic fault." Nothing asserted it. `BrowseTreeTest` covers `BrowseTree`, which decides *which*
 * rows a level holds; this covers what each row becomes on the way out, which is where the framework
 * contract lives.
 *
 * That split is the module's pattern rather than one file's oversight - `OutputTargetMapperTest`
 * covers the mapper and not `AudioOutputs`, `EqualiserTest` the kernel and not the processor - and
 * `WorkManagerSchedulerTest` records what it cost elsewhere in the project: the half of the download
 * scheduler that called `WorkManager` had no test, and it crashed the app on every pull.
 *
 * Robolectric, because `rootParams` builds a `Bundle` and the artwork branch parses a `Uri`. Under
 * the unit-test default both return stubs, which would make every assertion here vacuous.
 */
@RunWith(RobolectricTestRunner::class)
class BrowseItemsTest {

    /**
     * The crash-in-the-car contract, over every kind there is.
     *
     * Enumerated rather than sampled, so a kind added later gets the assertion for free.
     */
    @Test
    fun `every row kind sets both browse flags, which Media3 requires`() {
        for (kind in BrowseRowKind.entries) {
            val metadata: MediaMetadata = BrowseItems.mediaItem(row(kind)).mediaMetadata

            assertNotNull("$kind has no isBrowsable, which Media3 rejects", metadata.isBrowsable)
            assertNotNull("$kind has no isPlayable, which Media3 rejects", metadata.isPlayable)
            assertEquals("$kind", kind.isBrowsable, metadata.isBrowsable)
            assertEquals("$kind", kind.isPlayable, metadata.isPlayable)
        }
    }

    /** A folder is never playable, which is what stops a head unit trying to start a level. */
    @Test
    fun `no folder is playable and every folder is browsable`() {
        val folders = BrowseRowKind.entries.filter { it.name.startsWith("FOLDER_") }
        assertTrue("the enum has no folders left to check", folders.isNotEmpty())

        for (kind in folders) {
            val metadata = BrowseItems.mediaItem(row(kind)).mediaMetadata
            assertTrue("$kind must open a list", metadata.isBrowsable!!)
            assertFalse("$kind must not be playable", metadata.isPlayable!!)
        }
    }

    @Test
    fun `every row kind maps to a media type, and a track maps to music`() {
        for (kind in BrowseRowKind.entries) {
            assertNotNull(
                "$kind has no media type, so Auto picks its own placeholder art",
                BrowseItems.mediaItem(row(kind)).mediaMetadata.mediaType,
            )
        }
        assertEquals(
            MediaMetadata.MEDIA_TYPE_MUSIC,
            BrowseItems.mediaItem(row(BrowseRowKind.TRACK)).mediaMetadata.mediaType,
        )
        // A folder of songs is mixed, because Media3 has no folder-of-tracks type and claiming
        // MEDIA_TYPE_FOLDER_ALBUMS would be a lie about what the level holds.
        assertEquals(
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            BrowseItems.mediaItem(row(BrowseRowKind.FOLDER_TRACKS)).mediaMetadata.mediaType,
        )
    }

    @Test
    fun `the media id survives, because it is what comes back on a tap`() {
        val item: MediaItem = BrowseItems.mediaItem(row(BrowseRowKind.ALBUM, mediaId = "album/abc"))

        assertEquals("album/abc", item.mediaId)
    }

    /**
     * No browse item carries a playback URI, which the class's KDoc promises twice over.
     *
     * Media3 does not bundle a `LocalConfiguration` across the session boundary, so a URI put here
     * is dropped in transit and misleading in the source.
     */
    @Test
    fun `a browse row carries no URI`() {
        for (kind in BrowseRowKind.entries) {
            assertNull(
                "$kind carries a playback URI, which does not survive the session boundary",
                BrowseItems.mediaItem(row(kind)).localConfiguration,
            )
        }
    }

    /**
     * The credentialed artwork endpoints never reach another process.
     *
     * `Owned` is Subsonic `getCoverArt`, which carries the app-password in its query; `Catalogue` is
     * authorised by a bearer header a URI cannot carry. Browse metadata is bundled to every
     * controller of the session and persisted by the platform, so either one leaks or 401s - and
     * Media3's own bitmap loader would fetch it on a client that knows nothing of the certificate
     * pin. Only an absolute URL the server already handed over is passed on.
     */
    @Test
    fun `only a remote artwork URL is published, never a credentialed one`() {
        assertNull(
            "an owned cover's Subsonic URL carries the app-password",
            artworkUri(ArtworkRef.Owned(subsonicId = "al-1")),
        )
        assertNull(
            "a catalogue cover needs a bearer header, which a URI cannot carry",
            artworkUri(ArtworkRef.Catalogue(ReleaseGroupMbid("rg-1"))),
        )
        assertNull("no artwork at all is no URI", artworkUri(null))
        assertEquals(
            "https://example.test/cover.jpg",
            artworkUri(ArtworkRef.Remote("https://example.test/cover.jpg"))?.toString(),
        )
        assertNull("a blank URL is not a URI", artworkUri(ArtworkRef.Remote("")))
    }

    @Test
    fun `a track row carries the tags Auto draws beneath the title`() {
        val track: Track = Fixtures.track(disc = 2, number = 7, title = "Sienna")
        val metadata = BrowseItems
            .mediaItem(row(BrowseRowKind.TRACK, title = "Sienna", track = track))
            .mediaMetadata

        // The row's title, not the track's: `BrowseTree` decides what a level calls a row, and a
        // playlist may well show a track under a name the tags do not carry.
        assertEquals("Sienna", metadata.title)
        assertEquals(track.artistName, metadata.artist)
        assertEquals(track.albumTitle, metadata.albumTitle)
        assertEquals(7, metadata.trackNumber)
        assertEquals(2, metadata.discNumber)
        assertEquals(track.durationMs, metadata.durationMs)
    }

    /** On a row that is not a track the subtitle doubles as the artist, so Auto has both to draw from. */
    @Test
    fun `an album row's subtitle is also offered as the artist`() {
        val metadata = BrowseItems
            .mediaItem(row(BrowseRowKind.ALBUM, subtitle = "The Marias"))
            .mediaMetadata

        assertEquals("The Marias", metadata.subtitle)
        assertEquals("The Marias", metadata.artist)
    }

    // ---------------------------------------------------------------- the root's content style

    /**
     * The three hints, which are the whole reason an album level is not drawn as a text list.
     *
     * They are plain `android.media.browse` bundle keys rather than anything Media3 defines, so a
     * typo is silent: a head unit ignores a key it does not know and falls back to a list. These
     * assert the exact strings the Auto host has published.
     */
    @Test
    fun `the root params carry the content-style hints Auto reads`() {
        val extras: Bundle = BrowseItems.rootParams(requested = null).extras

        assertTrue(extras.getBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED"))
        assertEquals(GRID, extras.getInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"))
        assertEquals(LIST, extras.getInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"))
    }

    /** Browsable rows are pictures and read as a grid; playable rows are text and read as a list. */
    @Test
    fun `browsable rows are a grid and playable rows a list, which are different hints`() {
        val extras: Bundle = BrowseItems.rootParams(requested = null).extras

        assertFalse(
            "one hint for both styles means albums and songs draw the same",
            extras.getInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT") ==
                extras.getInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"),
        )
    }

    /** A hint this build does not know about survives the round trip rather than being replaced. */
    @Test
    fun `extras the browser sent are carried through`() {
        val requested = LibraryParams.Builder()
            .setExtras(Bundle().apply { putString("a.future.hint", "keep me") })
            .build()

        val extras: Bundle = BrowseItems.rootParams(requested).extras

        assertEquals("keep me", extras.getString("a.future.hint"))
        assertTrue(extras.getBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED"))
    }

    private fun artworkUri(artwork: ArtworkRef?) =
        BrowseItems.mediaItem(row(BrowseRowKind.ALBUM, artwork = artwork)).mediaMetadata.artworkUri

    private fun row(
        kind: BrowseRowKind,
        mediaId: String = "id/" + kind.name,
        title: String = "A title",
        subtitle: String? = "A subtitle",
        artwork: ArtworkRef? = null,
        track: Track? = null,
    ): BrowseRow = BrowseRow(
        mediaId = mediaId,
        title = title,
        kind = kind,
        subtitle = subtitle,
        artwork = artwork,
        track = track,
    )

    private companion object {
        const val LIST: Int = 1
        const val GRID: Int = 2
    }
}
