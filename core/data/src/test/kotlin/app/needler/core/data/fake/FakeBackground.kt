package app.needler.core.data.fake

import android.app.Notification
import app.needler.core.data.background.BackgroundStateStore
import app.needler.core.data.background.BackgroundWorkScheduler
import app.needler.core.data.background.NeedlerNotification
import app.needler.core.data.background.NeedlerNotifier
import app.needler.core.data.background.NotificationPermissionState
import app.needler.core.data.background.PollCadence
import app.needler.core.data.background.PollMemory
import app.needler.core.data.background.SyncTrigger
import app.needler.core.data.background.TrackByteSource
import app.needler.core.network.NetworkError
import app.needler.core.network.media.RangeDownloadResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Fakes for the background half.
 *
 * Everything here exists so the polling, download and notification rules can be exercised on the
 * JVM. Nothing in this file touches `WorkManager`, a notification manager or a socket - which is
 * also a statement about the production code: if any of those rules needed a device to test, they
 * would be in the wrong class.
 */

/**
 * A byte source that writes bytes rather than fetching them, and can be told to fail.
 *
 * The important thing it models is **resume**: [lastResumeOffsets] records how many bytes were
 * already on disk each time it was called, which is what a `Range` header would have asked the
 * server to skip.
 */
public class FakeTrackByteSource(
    /** Total size of every track this source serves. */
    public var completeLength: Long = 64L,
) : TrackByteSource {

    /** Failures to throw, one per call, in order. A null entry means "succeed this time". */
    public val failures: ArrayDeque<Throwable?> = ArrayDeque()

    /** File ids asked for, in order. */
    public val requested: MutableList<String> = ArrayList()

    /** Bytes already on disk at the start of each call: the offset a resume would begin at. */
    public val lastResumeOffsets: MutableList<Long> = ArrayList()

    /** How many bytes each call writes. Fewer than [completeLength] models a truncated body. */
    public var bytesPerCall: Long = Long.MAX_VALUE

    override suspend fun download(
        fileId: String,
        target: File,
        resume: Boolean,
        onProgress: ((bytesOnDisk: Long, completeLength: Long?) -> Unit)?,
    ): RangeDownloadResult {
        requested.add(fileId)
        val existing: Long = if (resume && target.isFile) target.length() else 0L
        lastResumeOffsets.add(existing)

        failures.removeFirstOrNull()?.let { throw it }

        if (!resume && target.isFile) target.delete()
        val start: Long = if (resume) existing else 0L
        val remaining: Long = (completeLength - start).coerceAtLeast(0L)
        val toWrite: Long = minOf(remaining, bytesPerCall)

        target.parentFile?.mkdirs()
        java.io.FileOutputStream(target, start > 0).use { out ->
            out.write(ByteArray(toWrite.toInt()) { 7 })
        }
        val total: Long = start + toWrite
        onProgress?.invoke(total, completeLength)

        return RangeDownloadResult(
            bytesWritten = toWrite,
            totalBytes = total,
            completeLength = completeLength,
            resumed = start > 0,
            complete = total >= completeLength,
            contentType = "audio/flac",
        )
    }

    /** Queues a `416`, which is what a partial file whose length no longer matches produces. */
    public fun failNextWithRangeMismatch() {
        failures.addLast(NetworkError.RangeNotSatisfiable(completeLength = null))
    }
}

/** Remembers what was posted, and can pretend the permission was refused. */
public class FakeNeedlerNotifier(
    public var permission: NotificationPermissionState = NotificationPermissionState.GRANTED,
) : NeedlerNotifier {

    public val posted: MutableList<NeedlerNotification> = ArrayList()
    public val cancelled: MutableList<String> = ArrayList()
    public val progressUpdates: MutableList<Triple<String, Int, Int>> = ArrayList()
    public var channelsCreated: Int = 0

    override fun ensureChannels() {
        channelsCreated += 1
    }

    override fun permissionState(): NotificationPermissionState = permission

    override fun post(notification: NeedlerNotification) {
        if (!permission.canPost) return
        posted.add(notification)
    }

    override fun cancel(tag: String) {
        cancelled.add(tag)
    }

    override fun downloadProgress(
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ): Notification = error("no platform notification in a JVM test")

    override fun showDownloadProgress(
        tag: String,
        albumTitle: String,
        tracksComplete: Int,
        tracksTotal: Int,
    ) {
        if (!permission.canPost) return
        progressUpdates.add(Triple(tag, tracksComplete, tracksTotal))
    }
}

/** In-memory background bookkeeping. */
public class FakeBackgroundStateStore(
    initial: PollMemory = PollMemory.Empty,
) : BackgroundStateStore {

    public var memory: PollMemory = initial
    private val asked: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public var pullPlaced: Boolean = false
    public var cleared: Int = 0

    override suspend fun pollMemory(): PollMemory = memory

    override suspend fun savePollMemory(memory: PollMemory) {
        this.memory = memory
    }

    override fun observeNotificationPermissionAsked(): Flow<Boolean> = asked.asStateFlow()

    override suspend fun notificationPermissionAsked(): Boolean = asked.value

    override suspend fun markNotificationPermissionAsked() {
        asked.value = true
    }

    override suspend fun firstPullPlaced(): Boolean = pullPlaced

    override suspend fun markPullPlaced() {
        pullPlaced = true
    }

    override suspend fun clear() {
        cleared += 1
        memory = PollMemory.Empty
        asked.value = false
        pullPlaced = false
    }
}

/** Records what would have been enqueued. */
public class RecordingWorkScheduler : BackgroundWorkScheduler {

    public val downloads: MutableList<String> = ArrayList()
    public val cancellations: MutableList<String> = ArrayList()
    public val cadences: MutableList<PollCadence> = ArrayList()
    public val syncs: MutableList<SyncTrigger> = ArrayList()
    public var expeditedPolls: Int = 0

    override suspend fun scheduleAlbumDownload(releaseGroupMbid: String) {
        downloads.add(releaseGroupMbid)
    }

    override suspend fun cancelAlbumDownload(releaseGroupMbid: String) {
        cancellations.add(releaseGroupMbid)
    }

    override suspend fun schedulePollAfterPull() {
        expeditedPolls += 1
    }

    override suspend fun schedulePeriodicPoll(cadence: PollCadence) {
        cadences.add(cadence)
    }

    override suspend fun scheduleSync(trigger: SyncTrigger) {
        syncs.add(trigger)
    }
}
