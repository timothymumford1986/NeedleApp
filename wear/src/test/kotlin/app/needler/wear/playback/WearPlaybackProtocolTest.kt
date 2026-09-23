package app.needler.wear.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing in this module that a test can meaningfully hold still.
 *
 * [WearPlaybackProtocol] is a contract between two separately installed APKs that cannot share a
 * module - `:core:domain` is a pure Kotlin/JVM library and these are Android data-layer paths, so the
 * phone side has to declare the same strings again. Nothing at compile time connects the two copies.
 * What is checked here are the invariants that would break the wire silently if someone edited a
 * constant: a path that stops beginning with a slash is rejected by Google Play services at runtime
 * and nowhere earlier, and two commands that collide route a Next to whatever the phone happens to
 * match first.
 *
 * The rest of this module is deliberately not unit-tested. [DataLayerPlaybackClient] is a thin wrapper
 * over Google Play services with no logic worth mocking a `DataClient` for, and the screen is
 * stateless and previewed - it is a screenshot test's job, once there is a Wear renderer, not a JUnit
 * one's.
 */
class WearPlaybackProtocolTest {

    @Test
    fun `every path is absolute`() {
        val paths: List<String> =
            listOf(WearPlaybackProtocol.PATH_NOW_PLAYING) + WearPlaybackProtocol.COMMAND_PATHS
        for (path in paths) {
            assertTrue("data layer paths must begin with a slash: " + path, path.startsWith("/"))
        }
    }

    @Test
    fun `every path is namespaced to Needler`() {
        // So a prefix filter on the phone side can never pick up another app's items, and so a
        // Needler path is recognisable in a data layer dump.
        val paths: List<String> =
            listOf(WearPlaybackProtocol.PATH_NOW_PLAYING) + WearPlaybackProtocol.COMMAND_PATHS
        for (path in paths) {
            assertTrue("expected the /needler/ namespace: " + path, path.startsWith("/needler/"))
        }
    }

    @Test
    fun `commands are distinct, and the command set is complete`() {
        val commands: List<String> = listOf(
            WearPlaybackProtocol.PATH_PLAY_PAUSE,
            WearPlaybackProtocol.PATH_NEXT,
            WearPlaybackProtocol.PATH_PREVIOUS,
        )
        assertEquals(commands.size, commands.toSet().size)
        assertEquals(commands.toSet(), WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `no command collides with the state path`() {
        assertTrue(WearPlaybackProtocol.PATH_NOW_PLAYING !in WearPlaybackProtocol.COMMAND_PATHS)
    }

    @Test
    fun `data map keys are distinct`() {
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
}
