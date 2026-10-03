// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards. See SettingsUiStateTest for the same note.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.ReleaseGroupMbid
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Downloaded albums to render Settings and the Downloaded albums screen against.
 *
 * Shared because the case that motivated this work is a library bigger than the Settings section can
 * hold, and both screens have to be shown handling the same one: Settings truncating it, and
 * [DownloadsScreen] holding all of it. A fixture written twice is two fixtures that will eventually
 * disagree about which album is largest, which is the one property both goldens are about.
 *
 * [MANY] is **already in size order**, because `PinRepository.observeDownloadedAlbums` orders in SQL
 * and a fixture that arrived shuffled would be testing the re-sort rather than the screens. The
 * ordering of a shuffled list is covered by `DownloadsUiStateTest` instead, where it can be asserted
 * on rather than looked at.
 */
internal object DownloadedAlbumFixtures {

    /** `2026-09-23T09:58:00Z`, so every fixture pins at a fixed instant. */
    val PINNED_AT: Instant = Instant.fromEpochSeconds(1_790_157_480L)

    /**
     * Three albums: a device with a handful, which is what most devices are and what the device this
     * defect was reported from had. Settings draws all three inline and offers no "See all".
     */
    val FEW: List<DownloadedAlbum> = listOf(
        album("Submarine", "The Marías", 1_181_116_006L, "0a1b"),
        album("Con Todo El Mundo", "Khruangbin", 734_003_200L, "1b2c"),
        album("Fetch the Bolt Cutters", "Fiona Apple", 339_738_624L, "2c3d"),
    )

    /**
     * Fourteen albums: the library that outgrew the section.
     *
     * Fourteen rather than fifty because the failure is visible by the eighth - the section is past
     * the point where the rest of Settings is reachable without scrolling through it - and a golden
     * nobody can read is a golden nobody checks.
     */
    val MANY: List<DownloadedAlbum> = listOf(
        album("Submarine", "The Marías", 1_181_116_006L, "0a01"),
        album("Con Todo El Mundo", "Khruangbin", 734_003_200L, "0a02"),
        album("In Rainbows", "Radiohead", 612_368_384L, "0a03"),
        album("Black Messiah", "D'Angelo and The Vanguard", 559_939_584L, "0a04"),
        album("Fetch the Bolt Cutters", "Fiona Apple", 487_587_840L, "0a05"),
        album("Mirrored", "Battles", 441_450_496L, "0a06"),
        album("The Epic", "Kamasi Washington", 402_653_184L, "0a07"),
        album("Blonde", "Frank Ocean", 356_515_840L, "0a08"),
        album("A Love Supreme", "John Coltrane", 268_435_456L, "0a09"),
        album("Hounds of Love", "Kate Bush", 234_881_024L, "0a10"),
        album("Spirit of Eden", "Talk Talk", 201_326_592L, "0a11"),
        album("Spiderland", "Slint", 167_772_160L, "0a12"),
        album("Dummy", "Portishead", 134_217_728L, "0a13"),
        album("Rejoicing in the Hands", "Devendra Banhart", 131_072_000L, "0a14"),
    )

    fun album(
        title: String,
        artist: String,
        sizeBytes: Long,
        suffix: String,
    ): DownloadedAlbum = DownloadedAlbum(
        releaseGroupMbid = ReleaseGroupMbid("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a$suffix"),
        title = title,
        artistName = artist,
        sizeBytes = sizeBytes,
        pinnedAt = PINNED_AT,
    )
}
