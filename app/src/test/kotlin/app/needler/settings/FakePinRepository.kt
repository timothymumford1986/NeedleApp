// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards, which is the type PinRepository.recordPlayed declares. See SettingsUiStateTest.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.StoppedDownload
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.DownloadedAlbumOrder
import app.needler.core.domain.repository.PinRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A scriptable [PinRepository] for the Downloaded albums screen.
 *
 * Only the three members that screen uses are implemented - [observeDownloadedAlbums], [unpinAlbum]
 * and [pinAlbum], which is the removal's Undo. Everything else throws rather than returning a
 * plausible-looking default, for the reason the whole project refuses inert callbacks: a fake that
 * quietly answers a call the screen was never supposed to make turns "this screen reached past its
 * own concern" into a passing test.
 *
 * A successful [unpinAlbum] removes the album from [observeDownloadedAlbums] as the real one does,
 * since REQUIREMENTS.md "Storage, and why there is no budget" is emphatic that the removal is the
 * point and not a side effect - "a 'remove' that leaves the usage figure unchanged is the one thing
 * that would make this whole screen untrustworthy" - so a fake that left the row in place could not
 * tell a working screen from a broken one.
 */
internal class FakePinRepository(
    albums: List<DownloadedAlbum> = emptyList(),
) : PinRepository {

    private val albums: MutableStateFlow<List<DownloadedAlbum>> = MutableStateFlow(albums)

    /** Every album [unpinAlbum] was called for, in order. */
    val unpinned: MutableList<ReleaseGroupMbid> = mutableListOf()

    /** Every album [pinAlbum] was called for, in order. The Undo on a finished removal. */
    val pinned: MutableList<ReleaseGroupMbid> = mutableListOf()

    /**
     * The albums [pinAlbum] can put back, keyed by MBID.
     *
     * Seeded from the constructor's list, so an album removed in a test can be restored by the same
     * test without the fake having to invent one. The real repository re-creates the pin row and the
     * download follows; here the row simply returns to the list, which is what the screen observes.
     */
    private val known: MutableMap<ReleaseGroupMbid, DownloadedAlbum> =
        // `this.albums`, so this reads the property declared above and not the constructor parameter
        // it shadows; the two have different types and the mix-up would only show as a compile error
        // on a later edit.
        this.albums.value.associateBy { it.releaseGroupMbid }.toMutableMap()

    /** What [pinAlbum] answers. Null is a success that puts the album back on the list. */
    var pinOutcome: Outcome<Unit>? = null

    /** [unpinGate]'s counterpart, to hold an undo in flight. */
    var pinGate: CompletableDeferred<Unit>? = null

    /** The orders [observeDownloadedAlbums] was asked for, so a test can assert the screen's query. */
    val ordersRequested: MutableList<DownloadedAlbumOrder> = mutableListOf()

    /** What [unpinAlbum] answers. Keyed by nothing: the screen only ever removes one at a time. */
    var unpinOutcome: Outcome<RemovedDownload>? = null

    /**
     * Held open to make a removal "in flight".
     *
     * Non-null suspends [unpinAlbum] until it is completed, which is how the second-tap guard and the
     * disabled remove controls are tested without any timing.
     */
    var unpinGate: CompletableDeferred<Unit>? = null

    override fun observeDownloadedAlbums(
        order: DownloadedAlbumOrder,
        limit: Int,
        offset: Int,
    ): Flow<List<DownloadedAlbum>> {
        ordersRequested += order
        return albums
    }

    override suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<RemovedDownload> {
        unpinned += mbid
        unpinGate?.await()
        val outcome: Outcome<RemovedDownload> = unpinOutcome ?: Outcome.Success(
            RemovedDownload(
                releaseGroupMbid = mbid,
                removedTracks = 12,
                freedBytes = 503_316_480L,
            ),
        )
        if (outcome is Outcome.Success) {
            albums.value = albums.value.filterNot { it.releaseGroupMbid == mbid }
        }
        return outcome
    }

    // ---- everything the Downloaded albums screen does not touch ---------------

    override fun observePins(): Flow<List<Pin>> = emptyFlow()

    override fun observePin(mbid: ReleaseGroupMbid): Flow<Pin?> = emptyFlow()

    override fun observeDownloadState(mbid: ReleaseGroupMbid): Flow<OfflineDownloadState> = emptyFlow()

    override fun observeStorageUsage(): Flow<StorageUsage> = emptyFlow()

    override fun observeStoragePreferences(): Flow<StoragePreferences> = emptyFlow()

    override fun observeCachedAudio(key: TrackKey): Flow<CachedAudio?> = emptyFlow()

    /**
     * The Undo on a finished removal: the pin comes back, and with it the row.
     *
     * The real one starts a download too, which is why the screen's copy says the server is sending
     * the album again rather than claiming the bytes were never gone. Nothing here models the
     * download, because the screen does not draw it - it draws the list, and the list is what the pin
     * restores.
     */
    override suspend fun pinAlbum(mbid: ReleaseGroupMbid, source: PinSource): Outcome<Unit> {
        pinned += mbid
        pinGate?.await()
        val outcome: Outcome<Unit> = pinOutcome ?: Outcome.Success(Unit)
        if (outcome is Outcome.Success) {
            val album: DownloadedAlbum? = known[mbid]
            if (album != null && albums.value.none { it.releaseGroupMbid == mbid }) {
                albums.value = albums.value + album
            }
        }
        return outcome
    }

    override suspend fun retryPinnedDownload(mbid: ReleaseGroupMbid): Outcome<Unit> =
        unreachable("retryPinnedDownload")

    /**
     * Unreachable despite the interface having a default, on this file's own rule.
     *
     * The default on [PinRepository.stopPinnedDownload] answers "nothing stopped" so that every test
     * double keeps compiling, which is right for the interface and wrong for this fake: the whole
     * point of the throws above is that a quietly plausible answer turns "this screen reached past
     * its own concern" into a passing test. The Storage screen removes downloads and reports what
     * that freed; stopping one in flight belongs to the album screen, which is where the progress is
     * shown.
     */
    override suspend fun stopPinnedDownload(mbid: ReleaseGroupMbid): Outcome<StoppedDownload> =
        unreachable("stopPinnedDownload")

    override suspend fun getCachedAudio(key: TrackKey): CachedAudio? = unreachable("getCachedAudio")

    override suspend fun recordPlayed(key: TrackKey, at: Instant): Unit = unreachable("recordPlayed")

    override suspend fun evictCachedAudio(
        key: TrackKey,
        reason: CacheEvictionReason,
    ): Outcome<Unit> = unreachable("evictCachedAudio")

    override suspend fun invalidateIfStale(key: TrackKey, current: TrackFetchHandle): Boolean =
        unreachable("invalidateIfStale")

    override suspend fun enforceFreeSpaceFloor(): Outcome<EvictionReport> =
        unreachable("enforceFreeSpaceFloor")

    override suspend fun canCacheBytes(bytes: Long): Boolean = unreachable("canCacheBytes")

    override suspend fun clearCachedAudio(): Outcome<EvictionReport> = unreachable("clearCachedAudio")

    override suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean): Outcome<Unit> =
        unreachable("setKeepPulledAlbumsOnDevice")

    override suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean): Outcome<Unit> =
        unreachable("setDownloadToDeviceOnWifiOnly")

    override suspend fun removeAllFromDevice(): Outcome<EvictionReport> =
        unreachable("removeAllFromDevice")

    private fun unreachable(name: String): Nothing =
        error("DownloadsViewModel must not call PinRepository." + name)
}
