// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards, and the underlying type has carried an opt-in marker through several Kotlin
// releases. Opting in costs a warning if it turns out not to be needed, and avoids a build break if
// it is.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.ReleaseGroupMbid
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivations behind the Settings screen, tested without rendering anything.
 *
 * Every line on screen 12 that is computed rather than stored is here: the byte figures a user
 * decides what to delete from, the relative sync time, the labels that stand in for a value the
 * server has not supplied yet, and the two guards that stop an action being offered when it is
 * certain to do nothing.
 *
 * These are pure functions of a data class, so nothing in this file needs Robolectric, a Hilt
 * graph, a `DataStore` or a server - which is the whole reason [SettingsUiState] holds raw values
 * and derives its strings rather than being handed pre-formatted ones by the ViewModel.
 */
class SettingsUiStateTest {

    // ---- sizes --------------------------------------------------------------

    @Test
    fun `a size under a kilobyte reads in bytes rather than rounding to zero`() {
        // REQUIREMENTS.md: "a 'remove' that leaves the usage figure unchanged is the one thing that
        // would make this whole screen untrustworthy". "0 KB freed" is that line in another form.
        assertEquals("512 B", SettingsFormat.bytes(512L))
        assertEquals("1023 B", SettingsFormat.bytes(1_023L))
    }

    @Test
    fun `nothing on the device reads as zero bytes, not as an empty string`() {
        assertEquals("0 B", SettingsFormat.bytes(0L))
    }

    @Test
    fun `the packs own 2 point 1 GB figure round-trips`() {
        // design/html/12-Settings.html draws "2.1 GB" against "Music kept on device".
        assertEquals("2.1 GB", SettingsFormat.bytes(2_254_857_830L))
    }

    @Test
    fun `above ten of a unit the decimal is dropped as noise`() {
        assertEquals("42 GB", SettingsFormat.bytes(45_097_156_608L))
    }

    // ---- relative time ------------------------------------------------------

    @Test
    fun `last synced reads the way the pack draws it`() {
        // design/html/12-Settings.html: "2 min ago".
        val now: Instant = Instant.fromEpochSeconds(10_000L)
        assertEquals("2 min ago", SettingsFormat.relativeTime(Instant.fromEpochSeconds(9_880L), now))
    }

    @Test
    fun `a sync seconds ago is just now rather than zero minutes`() {
        val now: Instant = Instant.fromEpochSeconds(10_000L)
        assertEquals("just now", SettingsFormat.relativeTime(Instant.fromEpochSeconds(9_996L), now))
    }

    @Test
    fun `a clock that moved backwards does not produce a negative age`() {
        val now: Instant = Instant.fromEpochSeconds(10_000L)
        assertEquals("just now", SettingsFormat.relativeTime(Instant.fromEpochSeconds(20_000L), now))
    }

    @Test
    fun `hours and days are stepped, and anything older is not counted`() {
        val now: Instant = Instant.fromEpochSeconds(10_000_000L)
        assertEquals("1 hr ago", SettingsFormat.relativeTime(Instant.fromEpochSeconds(9_996_400L), now))
        assertEquals("3 days ago", SettingsFormat.relativeTime(Instant.fromEpochSeconds(9_740_800L), now))
        assertEquals(
            "over a month ago",
            SettingsFormat.relativeTime(Instant.fromEpochSeconds(1_000L), now),
        )
    }

    @Test
    fun `a mirror that has never synced says so`() {
        // Unknown is not zero: "just now" would be a lie about the one figure a user checks after a
        // connection they are not sure worked.
        assertNull(SettingsFormat.relativeTime(null, Instant.fromEpochSeconds(10_000L)))
        assertEquals("Never", ServerSectionState().lastSyncedLabel)
    }

    @Test
    fun `a sync in flight replaces the age rather than showing a stale one`() {
        val state = ServerSectionState(
            lastSyncedAt = Instant.fromEpochSeconds(9_880L),
            syncing = true,
            renderedAt = Instant.fromEpochSeconds(10_000L),
        )
        assertEquals("Syncing…", state.lastSyncedLabel)
        assertFalse("a sync already running must not offer another", state.canSyncNow)
    }

    @Test
    fun `sync now is not offered before a server is configured`() {
        assertFalse(ServerSectionState().canSyncNow)
        assertTrue(ServerSectionState(host = "music.yourhome.net").canSyncNow)
    }

    // ---- playing ------------------------------------------------------------

    @Test
    fun `the crossfade row names the four stops screen 20 offers`() {
        assertEquals("Off", PlayingSectionState(crossfade = CrossfadeDuration.OFF).crossfadeLabel)
        assertEquals(
            "4 s",
            PlayingSectionState(crossfade = CrossfadeDuration.FOUR_SECONDS).crossfadeLabel,
        )
        assertEquals(
            "12 s",
            PlayingSectionState(crossfade = CrossfadeDuration.TWELVE_SECONDS).crossfadeLabel,
        )
    }

    @Test
    fun `a switched-off equaliser reads Off rather than naming the preset it would apply`() {
        val state = PlayingSectionState(equaliserEnabled = false, equaliserPreset = EqPreset.BASS)
        assertEquals("Off", state.equaliserLabel)
    }

    @Test
    fun `a switched-on equaliser names its preset, as the pack draws Flat`() {
        assertEquals(
            "Flat",
            PlayingSectionState(equaliserEnabled = true, equaliserPreset = EqPreset.FLAT)
                .equaliserLabel,
        )
        assertEquals(
            "Custom",
            PlayingSectionState(equaliserEnabled = true, equaliserPreset = EqPreset.CUSTOM)
                .equaliserLabel,
        )
    }

    @Test
    fun `the scrobble row never hard-codes a destination`() {
        // REQUIREMENTS.md "Design pack discrepancies": screen 12's "Scrobble to ListenBrainz"
        // hard-codes a destination the server may not use. The label comes from the server, and
        // before it answers the row describes what the toggle governs instead.
        val unknown = PlayingSectionState(scrobbleTargets = emptyList())
        assertEquals("Report plays to your server", unknown.scrobbleLabel)
        assertEquals("Your server decides where they go.", unknown.scrobbleSubtitle)

        val one = PlayingSectionState(scrobbleTargets = listOf("ListenBrainz"))
        assertEquals("Scrobble to ListenBrainz", one.scrobbleLabel)
        assertNull(one.scrobbleSubtitle)

        val both = PlayingSectionState(scrobbleTargets = listOf("ListenBrainz", "Last.fm"))
        assertEquals("Scrobble to ListenBrainz and Last.fm", both.scrobbleLabel)
    }

    @Test
    fun `stream quality always reports Original`() {
        // REQUIREMENTS.md, rule 1 of "Streaming". The only other value the domain models is the
        // metered transcode, which is a separate switch.
        assertEquals("Original", PlayingSectionState().streamQualityLabel)
    }

    // ---- storage ------------------------------------------------------------

    @Test
    fun `clearing the cache is not offered when it would free nothing`() {
        assertFalse(StorageSectionState(cachedBytes = 0L).canClearCache)
        assertTrue(StorageSectionState(cachedBytes = 1L).canClearCache)
        assertFalse(
            "a clear already running must not be startable twice",
            StorageSectionState(cachedBytes = 1L, working = true).canClearCache,
        )
    }

    @Test
    fun `removing everything is not offered on a device holding nothing`() {
        assertFalse(StorageSectionState().canRemoveAll)
        assertTrue(StorageSectionState(artworkBytes = 4_096L).canRemoveAll)
    }

    @Test
    fun `a device above the floor gets no warning at all`() {
        assertNull(StorageSectionState(lowOnSpace = false, shortfallBytes = 0L).lowOnSpaceMessage)
    }

    @Test
    fun `a device below the floor is told how much to free, not just that it is full`() {
        val message: String? = StorageSectionState(
            lowOnSpace = true,
            shortfallBytes = 734_003_200L,
        ).lowOnSpaceMessage
        assertTrue("the warning must name an amount", message?.contains("700 MB") == true)
        // REQUIREMENTS.md: "A device below the floor produces a warning, not an eviction of
        // downloads." The copy has to point at removal as the user's action, not the app's.
        assertTrue(message?.contains("Removing a downloaded album") == true)
    }

    @Test
    fun `the downloaded-album header counts and totals what is listed`() {
        val state = StorageSectionState(downloadedAlbums = listOf(album(1_073_741_824L), album(0L)))
        assertEquals("2 albums · 1.0 GB", state.downloadedAlbumsTrailing)
    }

    @Test
    fun `one album is not pluralised`() {
        val state = StorageSectionState(downloadedAlbums = listOf(album(1_048_576L)))
        assertEquals("1 album · 1.0 MB", state.downloadedAlbumsTrailing)
    }

    // ---- about --------------------------------------------------------------

    @Test
    fun `the version row carries the build number, which is what tells two builds apart`() {
        assertEquals("0.1.0 (10100)", AboutSectionState("0.1.0", 10_100L).versionLabel)
    }

    @Test
    fun `a package that could not be read says Unknown rather than rendering nothing`() {
        assertEquals("Unknown", AboutSectionState().versionLabel)
    }

    @Test
    fun `a version with no code renders the name alone`() {
        assertEquals("0.0.0-dev", AboutSectionState("0.0.0-dev", 0L).versionLabel)
    }

    // ---- destructive actions ------------------------------------------------

    @Test
    fun `signing out does not claim to delete the music on the device`() {
        // SessionRepository.signOut clears the credentials and nothing else. The mirror and the
        // audio store go only when the server identity changes, which is a different event.
        val prompt: String = DestructiveSettingsAction.SignOut.prompt
        assertTrue(prompt.contains("Music already on the device is left alone"))
    }

    @Test
    fun `removing everything promises the library survives, because it does`() {
        // REQUIREMENTS.md: "Remove all from device ... clears both audio tiers and the artwork cache
        // but never the metadata mirror - removing the mirror would leave the app unable to browse."
        val prompt: String = DestructiveSettingsAction.RemoveAllFromDevice.prompt
        assertTrue(prompt.contains("library stays browsable"))
    }

    private fun album(sizeBytes: Long): DownloadedAlbum = DownloadedAlbum(
        releaseGroupMbid = ReleaseGroupMbid("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f" + sizeBytes % 10L),
        title = "Submarine",
        artistName = "The Marías",
        sizeBytes = sizeBytes,
        pinnedAt = Instant.fromEpochSeconds(1_700_000_000L),
    )
}
