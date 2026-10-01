package app.needler.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of a contract with no compiler behind it.
 *
 * [WearPlaybackProtocol] here and `app.needler.wear.playback.WearPlaybackProtocol` in `:wear` are two
 * copies of the same strings in two APKs that cannot share a module - `:core:domain` is a pure
 * Kotlin/JVM library and these are Android data-layer paths. Nothing links them at build time, and a
 * mismatch is completely silent at runtime: the watch listens on one path, the phone publishes on
 * another, no error is raised anywhere, and the watch shows "Phone not connected" for ever.
 *
 * So the literals are written out below, and the same literals are written out in
 * `wear/src/test/kotlin/app/needler/wear/playback/WearPlaybackProtocolTest.kt`. A rename on either side
 * fails the test in the module that renamed it, which is the only signal this contract can have.
 *
 * The invariants are here for the same reason they are on the watch: a path without a leading slash is
 * rejected by Google Play services at runtime and nowhere earlier, and two commands that collide route
 * a Next to whichever branch the `when` reaches first.
 */
class WearPlaybackProtocolTest {

    private val allPaths: List<String> = listOf(
        WearPlaybackProtocol.PATH_NOW_PLAYING,
        WearPlaybackProtocol.PATH_CRATE,
        WearPlaybackProtocol.PATH_REQUEST_STATE,
    ) + WearPlaybackProtocol.COMMAND_PATHS

    @Test
    fun `every path is absolute`() {
        for (path in allPaths) {
            assertTrue("data layer paths must begin with a slash: " + path, path.startsWith("/"))
        }
    }

    @Test
    fun `every path is inside the namespace the manifest filters on`() {
        // app/src/main/AndroidManifest.xml scopes the listener service to android:pathPrefix="/needler".
        // A path outside it would never be delivered, and the service would look broken rather than
        // misconfigured.
        for (path in allPaths) {
            assertTrue(
                "expected the " + WearPlaybackProtocol.NAMESPACE + " namespace: " + path,
                path.startsWith(WearPlaybackProtocol.NAMESPACE),
            )
        }
    }

    @Test
    fun `every path is distinct`() {
        assertEquals(allPaths.size, allPaths.toSet().size)
    }

    @Test
    fun `the command set is exactly the four commands`() {
        val commands: List<String> = listOf(
            WearPlaybackProtocol.PATH_PLAY_PAUSE,
            WearPlaybackProtocol.PATH_NEXT,
            WearPlaybackProtocol.PATH_PREVIOUS,
            WearPlaybackProtocol.PATH_SKIP_TO_ROW,
        )
        assertEquals(commands.size, commands.toSet().size)
        assertEquals(commands.toSet(), WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `neither data item path is a command`() {
        assertTrue(WearPlaybackProtocol.PATH_NOW_PLAYING !in WearPlaybackProtocol.COMMAND_PATHS)
        assertTrue(WearPlaybackProtocol.PATH_CRATE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `the state request is not a command`() {
        // NeedlerWearListenerService accepts it beside the command set rather than inside it, so that
        // it cannot be caught by a rule about commands. If this ever becomes true, that service stops
        // answering requests and starts trying to play them.
        assertTrue(WearPlaybackProtocol.PATH_REQUEST_STATE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `now-playing keys are distinct`() {
        val keys: List<String> = listOf(
            WearPlaybackProtocol.KEY_HAS_ITEM,
            WearPlaybackProtocol.KEY_TITLE,
            WearPlaybackProtocol.KEY_ARTIST,
            WearPlaybackProtocol.KEY_ALBUM,
            WearPlaybackProtocol.KEY_IS_PLAYING,
            WearPlaybackProtocol.KEY_IS_BUFFERING,
            WearPlaybackProtocol.KEY_ARTWORK_ID,
            WearPlaybackProtocol.KEY_ARTWORK,
            WearPlaybackProtocol.KEY_PUBLISHED_AT,
        )
        // A duplicated key on the publishing side is worse than on the reading side: the second put
        // wins, and one field silently carries another's value all the way to the watch.
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `crate keys are distinct`() {
        val keys: List<String> = listOf(
            WearPlaybackProtocol.KEY_CRATE_ROWS,
            WearPlaybackProtocol.KEY_CRATE_CURRENT,
            WearPlaybackProtocol.KEY_CRATE_WINDOW_START,
            WearPlaybackProtocol.KEY_CRATE_TOTAL,
            WearPlaybackProtocol.KEY_PUBLISHED_AT,
            WearPlaybackProtocol.KEY_ROW_ID,
            WearPlaybackProtocol.KEY_ROW_TITLE,
            WearPlaybackProtocol.KEY_ROW_ARTIST,
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `no current row is not a valid index`() {
        assertTrue(WearPlaybackProtocol.NO_CURRENT_ROW < 0)
    }

    @Test
    fun `the crate window is bounded and useful`() {
        assertTrue(WearPlaybackProtocol.MAX_CRATE_ROWS > 0)
        // A data item's map is capped at 100 KB and a row costs roughly 200 bytes. The cap is set for
        // what a person will scroll, and it has to stay far enough below the ceiling that a long crate
        // publishes a window instead of failing.
        assertTrue(WearPlaybackProtocol.MAX_CRATE_ROWS <= 200)
    }

    @Test
    fun `the publishing window is long enough to be renewed inside`() {
        // The watch renews at a third of it. Anything shorter would put a Bluetooth message on the wire
        // faster than a watch screen stays awake.
        assertTrue(WearPlaybackProtocol.STATE_WINDOW_MS >= 15_000L)
    }

    /**
     * The wire, written out - and this is the copy that has to agree with `:wear`.
     *
     * If this fails, do not copy the new value in. Changing a path breaks every installed watch app
     * until it is updated too, so change both copies and both tests, deliberately.
     */
    @Test
    fun `the wire is exactly these strings`() {
        assertEquals("/needler/", WearPlaybackProtocol.NAMESPACE)

        assertEquals("/needler/now-playing", WearPlaybackProtocol.PATH_NOW_PLAYING)
        assertEquals("hasItem", WearPlaybackProtocol.KEY_HAS_ITEM)
        assertEquals("title", WearPlaybackProtocol.KEY_TITLE)
        assertEquals("artist", WearPlaybackProtocol.KEY_ARTIST)
        assertEquals("album", WearPlaybackProtocol.KEY_ALBUM)
        assertEquals("isPlaying", WearPlaybackProtocol.KEY_IS_PLAYING)
        assertEquals("isBuffering", WearPlaybackProtocol.KEY_IS_BUFFERING)
        assertEquals("artworkId", WearPlaybackProtocol.KEY_ARTWORK_ID)
        assertEquals("artwork", WearPlaybackProtocol.KEY_ARTWORK)
        assertEquals("publishedAt", WearPlaybackProtocol.KEY_PUBLISHED_AT)

        assertEquals("/needler/crate", WearPlaybackProtocol.PATH_CRATE)
        assertEquals("rows", WearPlaybackProtocol.KEY_CRATE_ROWS)
        assertEquals("rowId", WearPlaybackProtocol.KEY_ROW_ID)
        assertEquals("rowTitle", WearPlaybackProtocol.KEY_ROW_TITLE)
        assertEquals("rowArtist", WearPlaybackProtocol.KEY_ROW_ARTIST)
        assertEquals("crateCurrent", WearPlaybackProtocol.KEY_CRATE_CURRENT)
        assertEquals("crateWindowStart", WearPlaybackProtocol.KEY_CRATE_WINDOW_START)
        assertEquals("crateTotal", WearPlaybackProtocol.KEY_CRATE_TOTAL)
        assertEquals(-1, WearPlaybackProtocol.NO_CURRENT_ROW)
        assertEquals(40, WearPlaybackProtocol.MAX_CRATE_ROWS)

        assertEquals("/needler/command/play-pause", WearPlaybackProtocol.PATH_PLAY_PAUSE)
        assertEquals("/needler/command/next", WearPlaybackProtocol.PATH_NEXT)
        assertEquals("/needler/command/previous", WearPlaybackProtocol.PATH_PREVIOUS)
        assertEquals("/needler/command/skip-to-row", WearPlaybackProtocol.PATH_SKIP_TO_ROW)

        assertEquals("/needler/request-state", WearPlaybackProtocol.PATH_REQUEST_STATE)
        assertEquals(60_000L, WearPlaybackProtocol.STATE_WINDOW_MS)
    }

    // ---- On-device audio: the sync contract -----------------------------------------------------

    /** Every fixed sync path. The two prefixes are checked separately: neither is a whole path. */
    private val syncPaths: List<String> = listOf(
        WearPlaybackProtocol.PATH_SYNC_OFFER,
        WearPlaybackProtocol.PATH_SYNC_SELECTION,
        WearPlaybackProtocol.PATH_SYNC_NUDGE,
    )

    private val syncPrefixes: List<String> = listOf(
        WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX,
        WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX,
    )

    @Test
    fun `every sync path and prefix is inside the sync namespace`() {
        for (path in syncPaths + syncPrefixes) {
            assertTrue(
                "expected the " + WearPlaybackProtocol.SYNC_NAMESPACE + " namespace: " + path,
                path.startsWith(WearPlaybackProtocol.SYNC_NAMESPACE),
            )
        }
    }

    @Test
    fun `the sync namespace is inside the Needler namespace`() {
        // This is what makes the nudge reachable through the manifest filter this service already has.
        // If it ever stopped being true, the sync would go silent with no error anywhere and the
        // manifest - which is not this work's file to edit - would need a new path prefix.
        assertTrue(WearPlaybackProtocol.SYNC_NAMESPACE.startsWith(WearPlaybackProtocol.NAMESPACE))
        assertTrue(WearPlaybackProtocol.PATH_SYNC_NUDGE.startsWith(WearPlaybackProtocol.NAMESPACE))
    }

    @Test
    fun `both item prefixes end in a slash`() {
        for (prefix in syncPrefixes) {
            assertTrue("expected a trailing slash: " + prefix, prefix.endsWith("/"))
        }
    }

    @Test
    fun `no sync path collides with the transport contract`() {
        for (path in syncPaths) {
            assertTrue("collides with the transport: " + path, path !in allPaths)
        }
        for (prefix in syncPrefixes) {
            for (path in allPaths) {
                assertTrue(
                    "a transport path sits under " + prefix + ": " + path,
                    !path.startsWith(prefix),
                )
            }
        }
    }

    @Test
    fun `the nudge is not a command`() {
        assertTrue(WearPlaybackProtocol.PATH_SYNC_NUDGE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `the selection and offer items are not under the item prefixes`() {
        // The phone deletes everything under the track and cover prefixes that the plan does not keep. A
        // selection sitting under either would be deleted from under the watch on the first pass.
        for (prefix in syncPrefixes) {
            assertTrue(!WearPlaybackProtocol.PATH_SYNC_SELECTION.startsWith(prefix))
            assertTrue(!WearPlaybackProtocol.PATH_SYNC_OFFER.startsWith(prefix))
        }
    }

    @Test
    fun `sync keys are distinct, including the nested ones`() {
        val keys: List<String> = listOf(
            WearPlaybackProtocol.KEY_OFFER_ALBUMS,
            WearPlaybackProtocol.KEY_ALBUM_KEY,
            WearPlaybackProtocol.KEY_ALBUM_TITLE,
            WearPlaybackProtocol.KEY_ALBUM_ARTIST,
            WearPlaybackProtocol.KEY_ALBUM_TRACK_COUNT,
            WearPlaybackProtocol.KEY_ALBUM_BYTES,
            WearPlaybackProtocol.KEY_OFFER_NOT_SHOWN,
            WearPlaybackProtocol.KEY_TRACK_KEY,
            WearPlaybackProtocol.KEY_TRACK_TITLE,
            WearPlaybackProtocol.KEY_TRACK_ARTIST,
            WearPlaybackProtocol.KEY_TRACK_ALBUM,
            WearPlaybackProtocol.KEY_TRACK_DURATION_MS,
            WearPlaybackProtocol.KEY_TRACK_FORMAT,
            WearPlaybackProtocol.KEY_TRACK_BYTES,
            WearPlaybackProtocol.KEY_TRACK_FINGERPRINT,
            WearPlaybackProtocol.KEY_TRACK_AUDIO,
            WearPlaybackProtocol.KEY_COVER_ALBUM_KEY,
            WearPlaybackProtocol.KEY_COVER_IMAGE,
            WearPlaybackProtocol.KEY_WANTED_ALBUMS,
            WearPlaybackProtocol.KEY_ACTIVE_ALBUM,
            WearPlaybackProtocol.KEY_HELD_TRACKS,
            WearPlaybackProtocol.KEY_WATCH_CHARGING,
            WearPlaybackProtocol.KEY_WATCH_FREE_BYTES,
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `no sync key collides with a transport key`() {
        val transportKeys: Set<String> = setOf(
            WearPlaybackProtocol.KEY_HAS_ITEM,
            WearPlaybackProtocol.KEY_TITLE,
            WearPlaybackProtocol.KEY_ARTIST,
            WearPlaybackProtocol.KEY_ALBUM,
            WearPlaybackProtocol.KEY_IS_PLAYING,
            WearPlaybackProtocol.KEY_IS_BUFFERING,
            WearPlaybackProtocol.KEY_ARTWORK_ID,
            WearPlaybackProtocol.KEY_ARTWORK,
            WearPlaybackProtocol.KEY_CRATE_ROWS,
            WearPlaybackProtocol.KEY_ROW_ID,
            WearPlaybackProtocol.KEY_ROW_TITLE,
            WearPlaybackProtocol.KEY_ROW_ARTIST,
            WearPlaybackProtocol.KEY_CRATE_CURRENT,
            WearPlaybackProtocol.KEY_CRATE_WINDOW_START,
            WearPlaybackProtocol.KEY_CRATE_TOTAL,
        )
        val syncKeys: Set<String> = setOf(
            WearPlaybackProtocol.KEY_OFFER_ALBUMS,
            WearPlaybackProtocol.KEY_ALBUM_KEY,
            WearPlaybackProtocol.KEY_ALBUM_TITLE,
            WearPlaybackProtocol.KEY_ALBUM_ARTIST,
            WearPlaybackProtocol.KEY_ALBUM_TRACK_COUNT,
            WearPlaybackProtocol.KEY_ALBUM_BYTES,
            WearPlaybackProtocol.KEY_TRACK_KEY,
            WearPlaybackProtocol.KEY_TRACK_TITLE,
            WearPlaybackProtocol.KEY_TRACK_ARTIST,
            WearPlaybackProtocol.KEY_TRACK_ALBUM,
            WearPlaybackProtocol.KEY_TRACK_AUDIO,
            WearPlaybackProtocol.KEY_COVER_IMAGE,
        )
        assertTrue(syncKeys.intersect(transportKeys).isEmpty())
    }

    @Test
    fun `the two nudge payloads are distinct`() {
        assertTrue(WearPlaybackProtocol.NUDGE_NOW != WearPlaybackProtocol.NUDGE_WHEN_CHARGING)
    }

    @Test
    fun `the separators cannot be confused with each other or with a field`() {
        assertTrue(WearPlaybackProtocol.FINGERPRINT_SEPARATOR.isNotEmpty())
        assertTrue(WearPlaybackProtocol.FINGERPRINT_ABSENT.isNotEmpty())
        assertTrue(
            WearPlaybackProtocol.FINGERPRINT_ABSENT != WearPlaybackProtocol.FINGERPRINT_SEPARATOR,
        )
        assertTrue(
            WearPlaybackProtocol.HELD_TRACK_SEPARATOR != WearPlaybackProtocol.FINGERPRINT_SEPARATOR,
        )
        assertTrue(WearPlaybackProtocol.HELD_TRACK_SEPARATOR != "/")
    }

    @Test
    fun `the sync bounds are sane`() {
        assertTrue(WearPlaybackProtocol.MAX_OFFER_ALBUMS in 1..400)
        assertTrue(WearPlaybackProtocol.MAX_HELD_TRACKS in 1..400)
        assertTrue(WearPlaybackProtocol.MAX_WANTED_ALBUMS in 1..64)
        assertTrue(WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT in 1..4)
        assertTrue(WearPlaybackProtocol.MAX_TRACK_BYTES > 8L * 1024L * 1024L)
        assertTrue(WearPlaybackProtocol.MAX_TRACK_BYTES <= 128L * 1024L * 1024L)
    }

    /**
     * The sync wire, written out.
     *
     * The mirror of `wear/src/test/.../WearPlaybackProtocolTest.kt`'s copy of this test. A rename on
     * either side fails in the module that renamed it, and says which string it expected.
     */
    @Test
    fun `the sync wire is exactly these strings`() {
        assertEquals("/needler/sync/", WearPlaybackProtocol.SYNC_NAMESPACE)

        assertEquals("/needler/sync/offer", WearPlaybackProtocol.PATH_SYNC_OFFER)
        assertEquals("offerAlbums", WearPlaybackProtocol.KEY_OFFER_ALBUMS)
        assertEquals("albumKey", WearPlaybackProtocol.KEY_ALBUM_KEY)
        assertEquals("albumTitle", WearPlaybackProtocol.KEY_ALBUM_TITLE)
        assertEquals("albumArtist", WearPlaybackProtocol.KEY_ALBUM_ARTIST)
        assertEquals("albumTrackCount", WearPlaybackProtocol.KEY_ALBUM_TRACK_COUNT)
        assertEquals("albumBytes", WearPlaybackProtocol.KEY_ALBUM_BYTES)
        assertEquals("offerNotShown", WearPlaybackProtocol.KEY_OFFER_NOT_SHOWN)
        assertEquals(60, WearPlaybackProtocol.MAX_OFFER_ALBUMS)

        assertEquals("/needler/sync/track/", WearPlaybackProtocol.PATH_SYNC_TRACK_PREFIX)
        assertEquals("trackKey", WearPlaybackProtocol.KEY_TRACK_KEY)
        assertEquals("trackTitle", WearPlaybackProtocol.KEY_TRACK_TITLE)
        assertEquals("trackArtist", WearPlaybackProtocol.KEY_TRACK_ARTIST)
        assertEquals("trackAlbum", WearPlaybackProtocol.KEY_TRACK_ALBUM)
        assertEquals("trackDurationMs", WearPlaybackProtocol.KEY_TRACK_DURATION_MS)
        assertEquals("trackFormat", WearPlaybackProtocol.KEY_TRACK_FORMAT)
        assertEquals("trackBytes", WearPlaybackProtocol.KEY_TRACK_BYTES)
        assertEquals("trackFingerprint", WearPlaybackProtocol.KEY_TRACK_FINGERPRINT)
        assertEquals("trackAudio", WearPlaybackProtocol.KEY_TRACK_AUDIO)
        assertEquals("|", WearPlaybackProtocol.FINGERPRINT_SEPARATOR)
        assertEquals("-", WearPlaybackProtocol.FINGERPRINT_ABSENT)
        assertEquals(48L * 1024L * 1024L, WearPlaybackProtocol.MAX_TRACK_BYTES)
        assertEquals(2, WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT)

        assertEquals("/needler/sync/cover/", WearPlaybackProtocol.PATH_SYNC_COVER_PREFIX)
        assertEquals("coverAlbumKey", WearPlaybackProtocol.KEY_COVER_ALBUM_KEY)
        assertEquals("coverImage", WearPlaybackProtocol.KEY_COVER_IMAGE)

        assertEquals("/needler/sync/selection", WearPlaybackProtocol.PATH_SYNC_SELECTION)
        assertEquals("wantedAlbums", WearPlaybackProtocol.KEY_WANTED_ALBUMS)
        assertEquals("activeAlbum", WearPlaybackProtocol.KEY_ACTIVE_ALBUM)
        assertEquals("heldTracks", WearPlaybackProtocol.KEY_HELD_TRACKS)
        assertEquals("\t", WearPlaybackProtocol.HELD_TRACK_SEPARATOR)
        assertEquals("watchCharging", WearPlaybackProtocol.KEY_WATCH_CHARGING)
        assertEquals("watchFreeBytes", WearPlaybackProtocol.KEY_WATCH_FREE_BYTES)
        assertEquals(12, WearPlaybackProtocol.MAX_WANTED_ALBUMS)
        assertEquals(60, WearPlaybackProtocol.MAX_HELD_TRACKS)

        assertEquals("/needler/sync/nudge", WearPlaybackProtocol.PATH_SYNC_NUDGE)
        assertEquals(0.toByte(), WearPlaybackProtocol.NUDGE_WHEN_CHARGING)
        assertEquals(1.toByte(), WearPlaybackProtocol.NUDGE_NOW)
    }
}
