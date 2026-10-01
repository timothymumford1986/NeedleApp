package app.needler.wear.playback

import app.needler.wear.store.WearTrackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing in this module that a test can meaningfully hold still.
 *
 * [WearPlaybackProtocol] is a contract between two separately installed APKs that cannot share a
 * module - `:core:domain` is a pure Kotlin/JVM library and these are Android data-layer paths, so the
 * phone side has to declare the same strings again. Nothing at compile time connects the two copies.
 *
 * Two kinds of check follow, and the second is the one that catches a drifting mirror.
 *
 * The **invariants** are the things that would break the wire silently if someone edited a constant:
 * a path that stops beginning with a slash is rejected by Google Play services at runtime and nowhere
 * earlier, and two commands that collide route a Next to whatever the phone happens to match first.
 *
 * The **literals** are spelled out on purpose. `assertEquals(PATH_NEXT, PATH_NEXT)` passes cheerfully
 * while one copy of the protocol is renamed and the wire goes quiet, so the only test that can catch
 * that is one which writes the string down. `app/src/test/kotlin/app/needler/wear/`'s copy of this
 * file asserts the same literals, so a rename on either side fails in the module that renamed it.
 *
 * The rest of this module is deliberately not unit-tested here. [DataLayerPlaybackClient] is a thin
 * wrapper over Google Play services with no logic worth mocking a `DataClient` for - what decisions it
 * does make are in [WearCrateState.of], which has its own test - and the screens are stateless and
 * previewed, which is a screenshot test's job once there is a Wear renderer, not a JUnit one's.
 */
class WearPlaybackProtocolTest {

    /** Every path on the wire: both data items, every command, and the state request. */
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
    fun `every path is namespaced to Needler`() {
        // So the phone's manifest filter can never pick up another app's items, and so a Needler path
        // is recognisable in a data layer dump.
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
    fun `commands are distinct, and the command set is complete`() {
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
    fun `no command collides with a state path`() {
        assertTrue(WearPlaybackProtocol.PATH_NOW_PLAYING !in WearPlaybackProtocol.COMMAND_PATHS)
        assertTrue(WearPlaybackProtocol.PATH_CRATE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `the state request is not a command`() {
        // It changes nothing a user can hear and is safe to repeat, and the phone handles it on a
        // different branch. Putting it in the command set would let a future "drop commands while
        // busy" rule drop the request that keeps the watch's screen honest.
        assertTrue(WearPlaybackProtocol.PATH_REQUEST_STATE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `now-playing keys are distinct`() {
        // A duplicated key is the failure that looks like a working build: the second write wins and
        // one field silently carries another's value.
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
            // The row keys live in a nested map, so they could legally collide with the outer ones.
            // They are checked together anyway: two keys that read alike in a log are worse than a
            // rule that is slightly stricter than the wire requires.
            WearPlaybackProtocol.KEY_ROW_ID,
            WearPlaybackProtocol.KEY_ROW_TITLE,
            WearPlaybackProtocol.KEY_ROW_ARTIST,
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `no current row is not a valid index`() {
        // The watch turns this into "nothing is playing" by testing it against the row indices, so a
        // non-negative sentinel would highlight a row instead.
        assertTrue(WearPlaybackProtocol.NO_CURRENT_ROW < 0)
    }

    @Test
    fun `the crate window is bounded and useful`() {
        assertTrue(WearPlaybackProtocol.MAX_CRATE_ROWS > 0)
        // A data item's map is capped at 100 KB by Google Play services and a row costs roughly 200
        // bytes, so the cap has to stay well under five hundred rows however much anyone wants to
        // scroll. It is set for what a person will read, not for the ceiling.
        assertTrue(WearPlaybackProtocol.MAX_CRATE_ROWS <= 200)
    }

    @Test
    fun `the publishing window leaves room to renew`() {
        // DataLayerPlaybackClient renews at a third of this. Anything under a handful of seconds
        // would put a Bluetooth message on the wire faster than a watch screen stays awake.
        assertTrue(WearPlaybackProtocol.STATE_WINDOW_MS >= 15_000L)
    }

    /**
     * The wire, written out.
     *
     * If this test fails, do not "fix" it by copying the new value in: the phone's mirror is what the
     * value has to agree with, and changing a path breaks every installed watch app until it is
     * updated too. Change both copies and both tests, deliberately.
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
        // The watch's manifest scopes its one WearableListenerService on this prefix. A sync path that
        // escaped it would never wake the watch, and the transfer would silently never start.
        for (path in syncPaths + syncPrefixes) {
            assertTrue(
                "expected the " + WearPlaybackProtocol.SYNC_NAMESPACE + " namespace: " + path,
                path.startsWith(WearPlaybackProtocol.SYNC_NAMESPACE),
            )
        }
    }

    @Test
    fun `the sync namespace is inside the Needler namespace`() {
        // So the phone's existing manifest filter, which names /needler, already covers the nudge - which
        // is why no manifest change was needed on that side.
        assertTrue(WearPlaybackProtocol.SYNC_NAMESPACE.startsWith(WearPlaybackProtocol.NAMESPACE))
    }

    @Test
    fun `both item prefixes end in a slash`() {
        // A track key is appended directly. Without the trailing slash the path would read
        // "/needler/sync/track" followed by the mbid, which matches no manifest prefix anyone would write.
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
        // Same reason PATH_REQUEST_STATE is not: it touches nothing a user can hear and is safe to
        // repeat, and a future "drop commands while busy" rule must not drop it.
        assertTrue(WearPlaybackProtocol.PATH_SYNC_NUDGE !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `the selection and offer items are not under the item prefixes`() {
        // The watch ingests everything under the track and cover prefixes and skips the other two by
        // name. A selection that sat under the track prefix would be ingested as audio.
        for (prefix in syncPrefixes) {
            assertTrue(!WearPlaybackProtocol.PATH_SYNC_SELECTION.startsWith(prefix))
            assertTrue(!WearPlaybackProtocol.PATH_SYNC_OFFER.startsWith(prefix))
        }
    }

    @Test
    fun `sync keys are distinct, including the nested ones`() {
        // A duplicated key is the failure that looks like a working build: the second write wins and one
        // field silently carries another's value. The nested offer-row keys are checked against the outer
        // ones for the reason the crate's are - two keys that read alike in a log are worse than a rule
        // stricter than the wire needs.
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
        // KEY_ALBUM ("album") against KEY_TRACK_ALBUM ("trackAlbum") is the pair this catches: they mean
        // the same thing on two different items, and giving them one name would make any future combined
        // map impossible to read.
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
        // They are read off one byte. Equal values would make "copy now" indistinguishable from "only if
        // charging", so the charger rule would either never apply or never be overridable.
        assertTrue(WearPlaybackProtocol.NUDGE_NOW != WearPlaybackProtocol.NUDGE_WHEN_CHARGING)
    }

    @Test
    fun `the separators cannot be confused with each other or with a field`() {
        assertTrue(WearPlaybackProtocol.FINGERPRINT_SEPARATOR.isNotEmpty())
        assertTrue(WearPlaybackProtocol.FINGERPRINT_ABSENT.isNotEmpty())
        assertTrue(
            WearPlaybackProtocol.FINGERPRINT_ABSENT != WearPlaybackProtocol.FINGERPRINT_SEPARATOR,
        )
        // A held-track entry is split on its own separator and each half is then a whole field, so the
        // two separators must differ or one entry would split into three parts and be dropped - and a
        // dropped entry reads as "the watch does not hold this", which re-transfers the track for ever.
        assertTrue(
            WearPlaybackProtocol.HELD_TRACK_SEPARATOR != WearPlaybackProtocol.FINGERPRINT_SEPARATOR,
        )
        // A track key is hex, digits and slashes. If the separator could appear in one, the phone would
        // drop every entry the watch sent.
        assertTrue(WearPlaybackProtocol.HELD_TRACK_SEPARATOR != "/")
    }

    @Test
    fun `the store agrees with the wire about the held-track separator`() {
        // WearTrackRecord names it again so the store package does not depend on the protocol object for
        // one character. This assertion is the only thing keeping the two spellings together.
        assertEquals(WearPlaybackProtocol.HELD_TRACK_SEPARATOR, WearTrackRecord.HELD_SEPARATOR)
    }

    @Test
    fun `the sync bounds are sane`() {
        // The offer and the held list both ride a DataMap, which Google Play services caps at 100 KB. An
        // offer row is roughly 150 bytes and a held row roughly 80, so both caps keep two orders of
        // magnitude of headroom - they are set for what a person will scroll and for what one album
        // contains, not for the ceiling.
        assertTrue(WearPlaybackProtocol.MAX_OFFER_ALBUMS in 1..400)
        assertTrue(WearPlaybackProtocol.MAX_HELD_TRACKS in 1..400)
        assertTrue(WearPlaybackProtocol.MAX_WANTED_ALBUMS in 1..64)

        // Two in flight keeps the link busy while the watch writes and keeps the unit of loss at one
        // track. One serialises the write against the transfer; several hands Play services an album of
        // assets to interleave, none of them playable and all lost together.
        assertTrue(WearPlaybackProtocol.MAX_TRACKS_IN_FLIGHT in 1..4)

        // Large enough for a lossless album side, small enough that one track cannot hold the link for an
        // hour.
        assertTrue(WearPlaybackProtocol.MAX_TRACK_BYTES > 8L * 1024L * 1024L)
        assertTrue(WearPlaybackProtocol.MAX_TRACK_BYTES <= 128L * 1024L * 1024L)
    }

    /**
     * The sync wire, written out.
     *
     * Same rule as the transport's: if this fails, do not copy the new value in. The phone's mirror is
     * what these have to agree with, and a changed path breaks every installed watch app until it is
     * updated too.
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
