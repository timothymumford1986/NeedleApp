package app.needler.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.dao.FavouriteDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.PlaylistDao
import app.needler.core.data.local.dao.PullDao
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.dao.WriteQueueDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumFtsEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.entity.TrackFtsEntity
import app.needler.core.data.local.entity.WriteQueueEntity

/**
 * The one Room database: metadata mirror, pins, cache index, write queue and sync state.
 *
 * **Secrets are not in here.** The companion bearer, the app-password, the server URL and any
 * pinned certificate fingerprint live in `EncryptedSharedPreferences` under a Keystore master key -
 * see `app.needler.core.data.security.SecureCredentialStore`. Nothing in this database may ever hold
 * a credential, and `write_queue.payload` in particular carries only MBIDs, ids and timestamps.
 *
 * Every screen reads from here. Repositories serve queries from Room and write to it from sync, so
 * the UI never awaits a network call to render and offline needs no separate code path.
 */
@Database(
    version = NeedlerDatabase.VERSION,
    exportSchema = true,
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        AlbumFtsEntity::class,
        TrackFtsEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavouriteEntity::class,
        PinEntity::class,
        AudioCacheEntity::class,
        PullEntity::class,
        WriteQueueEntity::class,
        SyncStateEntity::class,
    ],
)
@TypeConverters(NeedlerTypeConverters::class)
public abstract class NeedlerDatabase : RoomDatabase() {

    public abstract fun artistDao(): ArtistDao

    public abstract fun albumDao(): AlbumDao

    public abstract fun trackDao(): TrackDao

    public abstract fun playlistDao(): PlaylistDao

    public abstract fun favouriteDao(): FavouriteDao

    public abstract fun pinDao(): PinDao

    public abstract fun audioCacheDao(): AudioCacheDao

    public abstract fun pullDao(): PullDao

    public abstract fun writeQueueDao(): WriteQueueDao

    public abstract fun syncStateDao(): SyncStateDao

    /**
     * Drops the mirror, the pins, the cache index, the write queue and the sync state, because the
     * user has pointed Needler at a different server.
     *
     * ## Why a server change is destructive on purpose
     *
     * REQUIREMENTS.md: "Changing server identity drops the mirror and the cache deliberately,
     * because MBIDs are global but `file_id` values and playlist IDs are not." Concretely:
     *
     *  * release-group, artist and recording MBIDs would still be valid, but
     *  * `track.file_id` and `audio_cache.source_file_id` are row ids in *that* server's
     *    track-files table, so keeping them would have the app fetch and cache-validate against ids
     *    the new server assigned to entirely different music;
     *  * `playlist.playlist_id` and `playlist_track` are server-local in the same way;
     *  * `pin.download_state`, `pull` and `write_queue` all describe work queued against the old
     *    server, and replaying a pull request against a stranger's library is not a recoverable
     *    mistake.
     *
     * Trying to keep the MBID-keyed half and re-key the rest would leave a mirror that is partly
     * about one server and partly about another, with no way to tell which rows are which.
     *
     * ## What the caller must do with the result
     *
     * The audio files themselves are *not* deleted here: this class cannot touch the filesystem
     * safely inside a transaction. The returned paths are every file the cache index knew about,
     * collected before the rows were dropped. The caller must delete them (and then the artwork
     * cache) after this function returns. Anything missed becomes an orphaned file that only a
     * sweep or an uninstall will reclaim, so do not skip it.
     *
     * Settings in DataStore are **not** cleared: quality, EQ, crossfade and notification
     * preferences are properties of the device and the person, not of the server.
     *
     * @param newServerIdentity the new server's identity string
     *   (`app.needler.core.network.ServerUrl.baseUrl`), or null to leave the database unclaimed.
     * @param now epoch milliseconds, recorded on the fresh sync-state row.
     * @return the file paths of every cached audio file, for the caller to delete.
     */
    public open suspend fun clearForServerChange(
        newServerIdentity: String?,
        now: Long,
    ): List<String> = withTransaction {
        // Collected first: after the deletes there is no record of what was on disk.
        val filePaths: List<String> = audioCacheDao().getAllRowsForRemoval().map { it.filePath }

        // Child-to-parent order. The cascades would handle most of this, but an explicit order
        // keeps the statement count predictable and does not depend on foreign keys being enabled.
        writeQueueDao().clear()
        playlistDao().clearTracks()
        playlistDao().clear()
        favouriteDao().clear()
        pinDao().clear()
        pullDao().clear()
        audioCacheDao().clear()
        trackDao().clear()
        albumDao().clear()
        artistDao().clear()
        syncStateDao().clear()

        // One transaction, so a crash mid-way leaves the old mirror *and* the old identity, and the
        // next connect simply wipes again rather than trusting half-dropped data.
        syncStateDao().upsert(
            SyncStateEntity(
                id = SyncStateEntity.SINGLETON_ID,
                serverIdentity = newServerIdentity,
                libraryRevision = null,
                lastFullSyncAt = null,
                lastDeltaSyncAt = null,
                lastScanAt = null,
                downloadsRevision = null,
                serverAlbumCount = null,
                serverSizeBytes = null,
                updatedAt = now,
            ),
        )
        filePaths
    }

    /**
     * True when this database already belongs to [serverIdentity], or to nobody yet.
     *
     * The connect flow calls this before syncing: a false answer means
     * [clearForServerChange] must run first.
     */
    public open suspend fun belongsToServer(serverIdentity: String): Boolean {
        val stored: String? = syncStateDao().getServerIdentity()
        return stored == null || stored == serverIdentity
    }

    /**
     * Clears on-device audio only - "Remove all from device" on screen 12.
     *
     * The metadata mirror is deliberately untouched: clearing it "would leave the app unable to
     * browse". Returns the file paths to delete, for the same reason as
     * [clearForServerChange]. Pins are removed as well, because the user asked for everything to go;
     * keeping pin rows would have the downloader immediately fetch it all again.
     */
    public open suspend fun clearAllAudio(): List<String> = withTransaction {
        val filePaths: List<String> = audioCacheDao().getAllRowsForRemoval().map { it.filePath }
        audioCacheDao().clear()
        pinDao().clear()
        filePaths
    }

    /** Rebuilds both FTS indexes from their content tables. Maintenance only; see [FtsTriggers]. */
    public open fun rebuildSearchIndex() {
        FtsTriggers.rebuildAll(openHelper.writableDatabase)
    }

    public companion object {

        /**
         * Schema version 1: the first release. Bumping this requires a hand-written migration in
         * [NeedlerMigrations.ALL] - there is no destructive fallback, by requirement.
         */
        public const val VERSION: Int = 1

        public const val DATABASE_NAME: String = "needler.db"

        /**
         * Builds the database.
         *
         * Note what is *not* called here: `fallbackToDestructiveMigration()`. A missing migration
         * must fail loudly, because the alternative is silently deleting a multi-gigabyte cache and
         * every pin the user set.
         *
         * Room enables foreign-key enforcement itself, which the `album` -> `track` and
         * `playlist` -> `playlist_track` cascades rely on.
         */
        public fun create(context: Context): NeedlerDatabase =
            Room.databaseBuilder(context, NeedlerDatabase::class.java, DATABASE_NAME)
                .addMigrations(*NeedlerMigrations.ALL)
                .addCallback(Callback)
                .build()

        /**
         * In-memory instance for instrumented tests. Migrations are not applied to an in-memory
         * database, so this is no substitute for a Room migration test over the exported schemas.
         */
        public fun createInMemory(context: Context): NeedlerDatabase =
            Room.inMemoryDatabaseBuilder(context, NeedlerDatabase::class.java)
                .addCallback(Callback)
                .build()

        /**
         * Creates the hand-written parts of the schema that Room does not generate: the
         * external-content FTS triggers, and the singleton `sync_state` row.
         *
         * The sync-state row is inserted here so that every `UPDATE ... WHERE id = 0` in
         * [SyncStateDao] has a row to hit. Without it the first delta sync would update zero rows
         * and report success.
         */
        internal val Callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                FtsTriggers.createAll(db)
                db.execSQL(
                    "INSERT OR IGNORE INTO sync_state (id, updated_at) VALUES (" +
                        SyncStateEntity.SINGLETON_ID + ", 0)",
                )
            }

            /**
             * Defensive: a migration that forgot to recreate the triggers would otherwise leave
             * offline search permanently and silently empty. `CREATE TRIGGER IF NOT EXISTS` makes
             * this a no-op on every normal open.
             */
            override fun onOpen(db: SupportSQLiteDatabase) {
                FtsTriggers.createAll(db)
            }
        }
    }
}
