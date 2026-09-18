package app.needler.core.data.di

import android.content.Context
import app.needler.core.data.local.NeedlerDatabase
import app.needler.core.data.local.cache.AudioCacheStoreWriter
import app.needler.core.data.local.cache.CacheIndex
import app.needler.core.data.local.cache.DeviceFreeSpace
import app.needler.core.data.local.cache.StatFsDeviceFreeSpace
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
import app.needler.core.data.platform.AndroidNetworkMonitor
import app.needler.core.data.platform.AppStateStore
import app.needler.core.data.platform.ArtworkCacheSize
import app.needler.core.data.platform.DataStoreAppStateStore
import app.needler.core.data.platform.DirectoryArtworkCacheSize
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.repository.DefaultFavouriteRepository
import app.needler.core.data.repository.DefaultLibraryRepository
import app.needler.core.data.repository.DefaultPinRepository
import app.needler.core.data.repository.DefaultPlaybackSettingsRepository
import app.needler.core.data.repository.DefaultPlaylistRepository
import app.needler.core.data.repository.DefaultPullRepository
import app.needler.core.data.repository.DefaultSearchRepository
import app.needler.core.data.repository.DefaultSessionRepository
import app.needler.core.data.repository.DefaultSyncRepository
import app.needler.core.data.security.SecureCredentialStore
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.sync.AlbumSyncer
import app.needler.core.data.sync.LibrarySyncEngine
import app.needler.core.data.writequeue.DefaultWriteQueueExecutor
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.data.writequeue.WriteQueueExecutor
import app.needler.core.data.writequeue.WriteQueueFlusher
import app.needler.core.domain.cache.AudioCacheWriter
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.repository.SyncRepository
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.subsonic.DefaultSubsonicApi
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.tls.CertificatePinStore
import app.needler.core.network.tls.MutableCertificatePinStore
import app.needler.core.network.v1.DefaultV1Api
import app.needler.core.network.v1.V1Api
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Everything `:core:data` contributes to the dependency graph.
 *
 * `:app` binds nothing about this module's insides: it injects the nine interfaces declared in
 * `:core:domain` and never names an implementation. That is what keeps the layering rule from being
 * decoration - a feature module depends on `:core:domain` and `:core:design`, never on `:core:data`
 * or `:core:network`, and the only place the concrete types appear is here.
 *
 * The transport is provided here too, even though it lives in `:core:network`. That module is a leaf
 * by design and declares no Hilt bindings, which is also why `CredentialProvider` is declared there
 * and implemented here: declaring the interface in the network module is what keeps it a leaf.
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataModule {

    // ------------------------------------------------------------------ storage

    @Provides
    @Singleton
    public fun provideDatabase(@ApplicationContext context: Context): NeedlerDatabase =
        NeedlerDatabase.create(context)

    @Provides
    public fun provideArtistDao(database: NeedlerDatabase): ArtistDao = database.artistDao()

    @Provides
    public fun provideAlbumDao(database: NeedlerDatabase): AlbumDao = database.albumDao()

    @Provides
    public fun provideTrackDao(database: NeedlerDatabase): TrackDao = database.trackDao()

    @Provides
    public fun providePlaylistDao(database: NeedlerDatabase): PlaylistDao = database.playlistDao()

    @Provides
    public fun provideFavouriteDao(database: NeedlerDatabase): FavouriteDao = database.favouriteDao()

    @Provides
    public fun providePinDao(database: NeedlerDatabase): PinDao = database.pinDao()

    @Provides
    public fun provideAudioCacheDao(database: NeedlerDatabase): AudioCacheDao = database.audioCacheDao()

    @Provides
    public fun providePullDao(database: NeedlerDatabase): PullDao = database.pullDao()

    @Provides
    public fun provideWriteQueueDao(database: NeedlerDatabase): WriteQueueDao = database.writeQueueDao()

    @Provides
    public fun provideSyncStateDao(database: NeedlerDatabase): SyncStateDao = database.syncStateDao()

    /**
     * The scope DataStore's readers live in.
     *
     * Application-scoped deliberately: it must outlive every reader, and a DataStore whose scope was
     * cancelled would fail every subsequent read for the life of the process.
     */
    @Provides
    @Singleton
    @DataScope
    public fun provideDataScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    public fun provideSettingsStore(
        @ApplicationContext context: Context,
        @DataScope scope: CoroutineScope,
    ): NeedlerSettingsStore = NeedlerSettingsStore.create(context, scope)

    @Provides
    @Singleton
    public fun provideAppStateStore(
        @ApplicationContext context: Context,
        @DataScope scope: CoroutineScope,
    ): AppStateStore = DataStoreAppStateStore.create(context, scope)

    // --------------------------------------------------------------- audio store

    /**
     * Where cached and downloaded audio lives.
     *
     * App-private internal storage: no permissions are needed, and it all disappears on uninstall.
     */
    @Provides
    @Singleton
    @AudioDirectory
    public fun provideAudioDirectory(@ApplicationContext context: Context): File =
        File(context.filesDir, AUDIO_DIRECTORY_NAME).apply { mkdirs() }

    /**
     * Free space, read from the volume the audio directory **actually sits on**.
     *
     * Not "the device": adopted storage and multi-user devices can put app-private storage somewhere
     * other than the data partition, and measuring the wrong volume is how a cache overshoots.
     */
    @Provides
    @Singleton
    public fun provideDeviceFreeSpace(@AudioDirectory directory: File): DeviceFreeSpace =
        StatFsDeviceFreeSpace(directory)

    @Provides
    @Singleton
    public fun provideCacheIndex(
        audioCacheDao: AudioCacheDao,
        deviceFreeSpace: DeviceFreeSpace,
    ): CacheIndex = CacheIndex(audioCacheDao, deviceFreeSpace)

    @Provides
    @Singleton
    public fun provideArtworkCacheSize(@ApplicationContext context: Context): ArtworkCacheSize =
        DirectoryArtworkCacheSize(File(context.cacheDir, ARTWORK_DIRECTORY_NAME))

    @Provides
    @Singleton
    public fun provideAudioCacheWriter(
        audioCacheDao: AudioCacheDao,
        cacheIndex: CacheIndex,
        @AudioDirectory directory: File,
    ): AudioCacheWriter = AudioCacheStoreWriter(
        audioCacheDao = audioCacheDao,
        cacheIndex = cacheIndex,
        audioDirectory = directory,
    )

    // ---------------------------------------------------------------- transport

    @Provides
    @Singleton
    public fun provideCredentialStore(@ApplicationContext context: Context): SecureCredentialStore =
        SecureCredentialStore.create(context)

    @Provides
    public fun provideCredentialProvider(store: SecureCredentialStore): CredentialProvider = store

    /**
     * The certificate pin store.
     *
     * Deliberately **not** part of `CredentialProvider`: a pinned fingerprint is not a credential. It
     * is never sent on a request, it is consulted by the TLS trust manager during the handshake, and
     * it is read on an entirely different schedule. Folding it in would hand every interceptor a
     * handle to it for no reason at all.
     */
    @Provides
    @Singleton
    public fun provideCertificatePinStore(): CertificatePinStore = MutableCertificatePinStore()

    @Provides
    @Singleton
    public fun provideHttpClient(
        credentials: CredentialProvider,
        pinStore: CertificatePinStore,
    ): NeedlerHttpClient = NeedlerHttpClient(credentials = credentials, pinStore = pinStore)

    @Provides
    @Singleton
    public fun provideV1Api(
        http: NeedlerHttpClient,
        credentials: CredentialProvider,
    ): V1Api = DefaultV1Api(http, credentials)

    @Provides
    @Singleton
    public fun provideSubsonicApi(
        http: NeedlerHttpClient,
        credentials: CredentialProvider,
    ): SubsonicApi = DefaultSubsonicApi(http, credentials)

    @Provides
    @Singleton
    public fun provideCapabilityProbe(v1: V1Api, subsonic: SubsonicApi): CapabilityProbe =
        CapabilityProbe(v1, subsonic)

    @Provides
    @Singleton
    public fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        AndroidNetworkMonitor(context)

    // -------------------------------------------------------------- write queue

    @Provides
    @Singleton
    public fun provideWriteQueue(dao: WriteQueueDao): WriteQueue = WriteQueue(dao)

    @Provides
    @Singleton
    public fun provideWriteQueueExecutor(
        v1: V1Api,
        subsonic: SubsonicApi,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
        favouriteDao: FavouriteDao,
    ): WriteQueueExecutor = DefaultWriteQueueExecutor(
        v1 = v1,
        subsonic = subsonic,
        trackDao = trackDao,
        playlistDao = playlistDao,
        favouriteDao = favouriteDao,
    )

    @Provides
    @Singleton
    public fun provideWriteQueueFlusher(
        queue: WriteQueue,
        executor: WriteQueueExecutor,
        albumDao: AlbumDao,
        trackDao: TrackDao,
    ): WriteQueueFlusher = WriteQueueFlusher(
        queue = queue,
        executor = executor,
        albumDao = albumDao,
        trackDao = trackDao,
    )

    // --------------------------------------------------------------------- sync

    @Provides
    @Singleton
    public fun provideAlbumSyncer(
        subsonic: SubsonicApi,
        albumDao: AlbumDao,
        trackDao: TrackDao,
        audioCacheDao: AudioCacheDao,
        pinDao: PinDao,
    ): AlbumSyncer = AlbumSyncer(
        subsonic = subsonic,
        albumDao = albumDao,
        trackDao = trackDao,
        audioCacheDao = audioCacheDao,
        pinDao = pinDao,
    )

    @Provides
    @Singleton
    public fun provideLibrarySyncEngine(
        subsonic: SubsonicApi,
        artistDao: ArtistDao,
        albumDao: AlbumDao,
        syncStateDao: SyncStateDao,
        albumSyncer: AlbumSyncer,
    ): LibrarySyncEngine = LibrarySyncEngine(
        subsonic = subsonic,
        artistDao = artistDao,
        albumDao = albumDao,
        syncStateDao = syncStateDao,
        albumSyncer = albumSyncer,
    )

    // ------------------------------------------------------------- repositories

    @Provides
    @Singleton
    public fun provideLibraryRepository(
        albumDao: AlbumDao,
        artistDao: ArtistDao,
        trackDao: TrackDao,
        pinDao: PinDao,
        pullDao: PullDao,
        favouriteDao: FavouriteDao,
        syncStateDao: SyncStateDao,
        albumSyncer: AlbumSyncer,
        v1: V1Api,
    ): LibraryRepository = DefaultLibraryRepository(
        albumDao = albumDao,
        artistDao = artistDao,
        trackDao = trackDao,
        pinDao = pinDao,
        pullDao = pullDao,
        favouriteDao = favouriteDao,
        syncStateDao = syncStateDao,
        albumSyncer = albumSyncer,
        v1 = v1,
    )

    @Provides
    @Singleton
    public fun provideSearchRepository(
        albumDao: AlbumDao,
        trackDao: TrackDao,
        artistDao: ArtistDao,
        appStateStore: AppStateStore,
        v1: V1Api,
    ): SearchRepository = DefaultSearchRepository(
        albumDao = albumDao,
        trackDao = trackDao,
        artistDao = artistDao,
        appStateStore = appStateStore,
        v1 = v1,
    )

    @Provides
    @Singleton
    public fun providePullRepository(
        pullDao: PullDao,
        albumDao: AlbumDao,
        appStateStore: AppStateStore,
        writeQueue: WriteQueue,
        networkMonitor: NetworkMonitor,
        v1: V1Api,
    ): PullRepository = DefaultPullRepository(
        pullDao = pullDao,
        albumDao = albumDao,
        appStateStore = appStateStore,
        writeQueue = writeQueue,
        networkMonitor = networkMonitor,
        v1 = v1,
    )

    @Provides
    @Singleton
    public fun providePlaylistRepository(
        playlistDao: PlaylistDao,
        trackDao: TrackDao,
        writeQueueDao: WriteQueueDao,
        writeQueue: WriteQueue,
        networkMonitor: NetworkMonitor,
        subsonic: SubsonicApi,
    ): PlaylistRepository = DefaultPlaylistRepository(
        playlistDao = playlistDao,
        trackDao = trackDao,
        writeQueueDao = writeQueueDao,
        writeQueue = writeQueue,
        networkMonitor = networkMonitor,
        subsonic = subsonic,
    )

    @Provides
    @Singleton
    public fun provideFavouriteRepository(
        favouriteDao: FavouriteDao,
        trackDao: TrackDao,
        writeQueue: WriteQueue,
        networkMonitor: NetworkMonitor,
        subsonic: SubsonicApi,
    ): FavouriteRepository = DefaultFavouriteRepository(
        favouriteDao = favouriteDao,
        trackDao = trackDao,
        writeQueue = writeQueue,
        networkMonitor = networkMonitor,
        subsonic = subsonic,
    )

    @Provides
    @Singleton
    public fun providePinRepository(
        database: NeedlerDatabase,
        pinDao: PinDao,
        audioCacheDao: AudioCacheDao,
        albumDao: AlbumDao,
        trackDao: TrackDao,
        cacheIndex: CacheIndex,
        deviceFreeSpace: DeviceFreeSpace,
        settingsStore: NeedlerSettingsStore,
        artworkCacheSize: ArtworkCacheSize,
    ): PinRepository = DefaultPinRepository(
        database = database,
        pinDao = pinDao,
        audioCacheDao = audioCacheDao,
        albumDao = albumDao,
        trackDao = trackDao,
        cacheIndex = cacheIndex,
        deviceFreeSpace = deviceFreeSpace,
        settingsStore = settingsStore,
        artworkCacheSize = artworkCacheSize,
    )

    @Provides
    @Singleton
    public fun provideSessionRepository(
        credentials: SecureCredentialStore,
        v1: V1Api,
        capabilityProbe: CapabilityProbe,
        networkMonitor: NetworkMonitor,
    ): SessionRepository = DefaultSessionRepository(
        credentials = credentials,
        v1 = v1,
        capabilityProbe = capabilityProbe,
        networkMonitor = networkMonitor,
    )

    @Provides
    @Singleton
    public fun providePlaybackSettingsRepository(
        settingsStore: NeedlerSettingsStore,
        appStateStore: AppStateStore,
        trackDao: TrackDao,
        writeQueue: WriteQueue,
        networkMonitor: NetworkMonitor,
        subsonic: SubsonicApi,
        v1: V1Api,
    ): PlaybackSettingsRepository = DefaultPlaybackSettingsRepository(
        settingsStore = settingsStore,
        appStateStore = appStateStore,
        trackDao = trackDao,
        writeQueue = writeQueue,
        networkMonitor = networkMonitor,
        subsonic = subsonic,
        v1 = v1,
    )

    @Provides
    @Singleton
    public fun provideSyncRepository(
        database: NeedlerDatabase,
        syncStateDao: SyncStateDao,
        syncEngine: LibrarySyncEngine,
        albumSyncer: AlbumSyncer,
        writeQueue: WriteQueue,
        writeQueueFlusher: WriteQueueFlusher,
        playlistRepository: PlaylistRepository,
        favouriteRepository: FavouriteRepository,
    ): SyncRepository = DefaultSyncRepository(
        database = database,
        syncStateDao = syncStateDao,
        syncEngine = syncEngine,
        albumSyncer = albumSyncer,
        writeQueue = writeQueue,
        writeQueueFlusher = writeQueueFlusher,
        playlistRepository = playlistRepository,
        favouriteRepository = favouriteRepository,
    )

    private const val AUDIO_DIRECTORY_NAME: String = "audio"
    private const val ARTWORK_DIRECTORY_NAME: String = "artwork"
}

/** The application-scoped coroutine scope DataStore readers live in. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class DataScope

/** App-private internal storage for cached and downloaded audio. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class AudioDirectory
