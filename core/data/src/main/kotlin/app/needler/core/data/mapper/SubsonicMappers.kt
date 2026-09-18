package app.needler.core.data.mapper

import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.staleness.ServerTrackMetadata
import app.needler.core.data.local.TrackKeyDb
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.ArtistId3Dto
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.GenreDto
import app.needler.core.network.subsonic.dto.PlaylistDto

/**
 * The owned-library lane: OpenSubsonic DTOs to mirror rows.
 *
 * Subsonic content always lands in the mirror first and is read back from there, because the mirror
 * is the read path. Nothing here produces a domain object directly - `EntityMappers` does that from
 * the row - so there is exactly one shape the UI ever sees for an owned album, whether it arrived
 * from a sync ten minutes ago or from the album screen's own refresh.
 *
 * The two exceptions are `Genre` and `ServerTrackMetadata`: neither has a table, one being a derived
 * bucket and the other being the transient right-hand side of the staleness comparison.
 */
public object SubsonicMappers {

    // ------------------------------------------------------------------- artists

    public fun artistEntity(dto: ArtistId3Dto, now: Long, monitored: Boolean = false): ArtistEntity? {
        val mbid: ArtistMbid = SubsonicIds.artistMbid(dto.id, dto.musicBrainzId) ?: return null
        val name: String = dto.name.ifBlank { mbid.value }
        val sortName: String = dto.sortName?.takeIf { it.isNotBlank() } ?: name
        return ArtistEntity(
            artistMbid = mbid.value,
            name = name,
            sortName = sortName,
            sortNameNormalised = SortKeys.normalise(sortName),
            albumCount = dto.albumCount ?: dto.album.size,
            artUrl = null,
            monitored = monitored,
            updatedAt = now,
        )
    }

    // -------------------------------------------------------------------- albums

    /**
     * An owned album.
     *
     * [state] is passed in rather than derived: the row may already be pinned or mid-pull, and the
     * server's album payload says nothing about either. Sync passes the state it computed from the
     * `pin` and `pull` tables; a plain refresh passes what the row already carried.
     */
    public fun albumEntity(
        dto: AlbumId3Dto,
        now: Long,
        state: AlbumStateDb = AlbumStateDb.OWNED,
        quality: TrackQualitySummary = TrackQualitySummary.Empty,
        sizeBytes: Long? = null,
    ): AlbumEntity? {
        val mbid: ReleaseGroupMbid = SubsonicIds.releaseGroupMbid(dto.id, dto.musicBrainzId) ?: return null
        val title: String = dto.name.ifBlank { mbid.value }
        val artistName: String = dto.displayArtist
            ?: dto.artist
            ?: dto.artists.firstOrNull()?.name
            ?: ""
        val genres: List<String> = buildList {
            dto.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
            dto.genres.forEach { entry -> if (entry.name.isNotBlank()) add(entry.name) }
        }
        val discCount: Int? = dto.discTitles.maxOfOrNull { it.disc }
            ?: dto.song.mapNotNull { it.discNumber }.maxOrNull()
        return AlbumEntity(
            releaseGroupMbid = mbid.value,
            artistMbid = SubsonicIds.artistMbid(dto.artistId, dto.artists.firstOrNull()?.musicBrainzId)?.value,
            artistName = artistName,
            title = title,
            titleNormalised = SortKeys.normalise(dto.sortName ?: title),
            artistNormalised = SortKeys.normalise(artistName),
            year = dto.year ?: dto.originalReleaseDate?.year?.takeIf { it > 0 },
            trackCount = dto.songCount ?: dto.song.size.takeIf { it > 0 },
            discCount = discCount,
            durationMs = dto.duration?.let { it.toLong() * 1_000L },
            format = quality.format,
            bitrateKbps = quality.bitrateKbps,
            state = state,
            inLibrary = state.isInLibrary,
            addedAt = WireTime.toEpochMillis(WireTime.fromIso(dto.created)),
            sizeBytes = sizeBytes ?: quality.totalSizeBytes,
            coverArtId = dto.coverArt ?: dto.id.takeIf { it.isNotBlank() },
            genres = GenreCodec.encode(genres),
            qualityPolicySummary = null,
            updatedAt = now,
        )
    }

    // -------------------------------------------------------------------- tracks

    /**
     * An album's songs, in disc then track order, with a stable [TrackKeyDb] for each.
     *
     * A track's identity is (release group, disc, track number) and never `tr-<file id>`: the server
     * upgrades files in place, so a key built on the file id would silently point at bytes that are
     * no longer the track's. A song with no `track` number falls back to its ordinal within its disc,
     * which keeps the key stable for the common case of a server that omits the field entirely.
     */
    public fun trackEntities(
        releaseGroupMbid: ReleaseGroupMbid,
        songs: List<ChildDto>,
        now: Long,
    ): List<TrackEntity> {
        val nextOrdinal: MutableMap<Int, Int> = LinkedHashMap()
        val rows: MutableList<TrackEntity> = ArrayList(songs.size)
        val seen: MutableSet<String> = LinkedHashSet(songs.size)
        for (song in songs) {
            if (song.isDir) continue
            val disc: Int = song.discNumber?.takeIf { it >= 1 } ?: 1
            val ordinal: Int = (nextOrdinal[disc] ?: 0) + 1
            nextOrdinal[disc] = ordinal
            val trackNo: Int = song.track?.takeIf { it >= 1 } ?: ordinal
            val key = TrackKeyDb(releaseGroupMbid.value, disc, trackNo)
            // A duplicate (disc, track) pair would collide on the primary key and lose a row. It
            // happens on sloppily tagged rips; fall back to the ordinal so both tracks survive.
            val resolved: TrackKeyDb = if (seen.add(key.canonical)) {
                key
            } else {
                TrackKeyDb(releaseGroupMbid.value, disc, ordinal).also { seen.add(it.canonical) }
            }
            rows.add(trackEntity(resolved, song, now))
        }
        return rows.sortedWith(compareBy({ it.discNo }, { it.trackNo }))
    }

    public fun trackEntity(key: TrackKeyDb, song: ChildDto, now: Long): TrackEntity {
        val title: String = song.title.ifBlank { "Track " + key.trackNo }
        return TrackEntity(
            releaseGroupMbid = key.releaseGroupMbid,
            discNo = key.discNo,
            trackNo = key.trackNo,
            title = title,
            titleNormalised = SortKeys.normalise(song.sortName ?: title),
            artistName = song.displayArtist ?: song.artist ?: song.artists.firstOrNull()?.name,
            durationMs = song.duration?.let { it.toLong() * 1_000L },
            recordingMbid = song.musicBrainzId?.takeIf { it.isNotBlank() },
            // The fetch handle, stored so playback and scrobbling can build `tr-<file id>`, and so
            // the staleness check has something to notice moving. It is never a key.
            fileId = SubsonicIds.fileId(song.id)?.value,
            sizeBytes = song.size,
            format = formatToken(song.suffix ?: song.contentType),
            bitrateKbps = song.bitRate,
            updatedAt = now,
        )
    }

    /**
     * The right-hand side of the staleness comparison: the file **as the server describes it now**.
     *
     * This is built from the server payload and never from the `track` row, because sync overwrites
     * that row with these same values - comparing against it compares the mirror with itself and
     * reports "unchanged" every time.
     */
    public fun serverTrackMetadata(row: TrackEntity): ServerTrackMetadata = ServerTrackMetadata(
        key = TrackKeyDb(row.releaseGroupMbid, row.discNo, row.trackNo),
        fileId = row.fileId,
        sizeBytes = row.sizeBytes,
        durationMs = row.durationMs,
        format = row.format,
        bitrateKbps = row.bitrateKbps,
    )

    // ----------------------------------------------------------------- playlists

    public fun playlistEntity(dto: PlaylistDto, now: Long, localOnly: Boolean = false): PlaylistEntity? {
        val id: String = SubsonicIds.playlistIdOrNull(dto.id)?.value ?: return null
        val name: String = dto.name.ifBlank { id }
        return PlaylistEntity(
            playlistId = id,
            name = name,
            nameNormalised = SortKeys.normalise(name),
            trackCount = if (dto.entry.isNotEmpty()) dto.entry.size else dto.songCount,
            durationMs = dto.duration?.let { it.toLong() * 1_000L },
            owner = dto.owner,
            isPublic = dto.isPublic ?: false,
            comment = dto.comment,
            coverArtId = dto.coverArt,
            createdAt = WireTime.toEpochMillis(WireTime.fromIso(dto.created)),
            changedAt = WireTime.toEpochMillis(WireTime.fromIso(dto.changed)),
            localOnly = localOnly,
            updatedAt = now,
        )
    }

    /**
     * A playlist's entries, resolved to stable track keys.
     *
     * `playlist_track` points at tracks by (release group, disc, track) and never by `file_id`, and
     * has no foreign key to `track`, so a delta sync that briefly drops an album cannot empty a
     * user's playlist. An entry whose album id is unreadable is dropped rather than stored against a
     * key that cannot be joined.
     */
    public fun playlistTrackEntities(playlistId: String, entries: List<ChildDto>): List<PlaylistTrackEntity> {
        val rows: MutableList<PlaylistTrackEntity> = ArrayList(entries.size)
        var position = 0
        for (entry in entries) {
            val album: ReleaseGroupMbid =
                SubsonicIds.releaseGroupMbid(entry.albumId, entry.parent) ?: continue
            val disc: Int = entry.discNumber?.takeIf { it >= 1 } ?: 1
            val trackNo: Int = entry.track?.takeIf { it >= 1 } ?: (position + 1)
            rows.add(
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    position = position,
                    releaseGroupMbid = album.value,
                    discNo = disc,
                    trackNo = trackNo,
                    sourceFileId = SubsonicIds.fileId(entry.id)?.value,
                ),
            )
            position++
        }
        return rows
    }

    // -------------------------------------------------------------------- genres

    public fun genre(dto: GenreDto): Genre? {
        val name: String = dto.value.trim()
        if (name.isEmpty()) return null
        return Genre(name = name, albumCount = dto.albumCount, trackCount = dto.songCount)
    }

    // ------------------------------------------------------------------ quality

    /**
     * The FLAC / MP3 320 badge for an album, summarised from its tracks.
     *
     * Denormalised onto the album row so a 5,000-row grid never loads a track. A mixed album reports
     * its *lowest* quality, because the badge is a promise about what the user will hear and the
     * worst track is the one that breaks it.
     */
    public fun qualitySummary(songs: List<ChildDto>): TrackQualitySummary {
        if (songs.isEmpty()) return TrackQualitySummary.Empty
        val formats: List<AudioFormat> = songs.mapNotNull { song ->
            AudioFormat.fromServerToken(song.suffix ?: song.contentType).takeIf { it != AudioFormat.UNKNOWN }
        }
        val lossless: Boolean = formats.isNotEmpty() && formats.all { format ->
            format == AudioFormat.FLAC || format == AudioFormat.ALAC || format == AudioFormat.WAV
        }
        val format: AudioFormat? = when {
            formats.isEmpty() -> null
            lossless -> formats.first()
            else -> formats.firstOrNull { entry ->
                entry != AudioFormat.FLAC && entry != AudioFormat.ALAC && entry != AudioFormat.WAV
            } ?: formats.first()
        }
        val bitrates: List<Int> = songs.mapNotNull { it.bitRate }.filter { it > 0 }
        val sizes: List<Long> = songs.mapNotNull { it.size }.filter { it > 0 }
        return TrackQualitySummary(
            format = format?.let { formatToken(it.name) },
            bitrateKbps = if (lossless) null else bitrates.minOrNull(),
            totalSizeBytes = if (sizes.isEmpty()) null else sizes.sum(),
        )
    }

    /** Normalises a server suffix or content type to the token stored in `format` columns. */
    public fun formatToken(token: String?): String? {
        val format: AudioFormat = AudioFormat.fromServerToken(token)
        if (format == AudioFormat.UNKNOWN) return token?.trim()?.lowercase()?.ifEmpty { null }
        return when (format) {
            AudioFormat.OGG_VORBIS -> "ogg"
            else -> format.name.lowercase()
        }
    }
}

/** The denormalised quality and size an album row carries, summarised from its tracks. */
public data class TrackQualitySummary(
    val format: String?,
    val bitrateKbps: Int?,
    val totalSizeBytes: Long?,
) {
    public companion object {
        public val Empty: TrackQualitySummary = TrackQualitySummary(null, null, null)
    }
}
