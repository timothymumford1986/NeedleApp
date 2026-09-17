package app.needler.core.data.local

import androidx.room.TypeConverter
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.FavouriteTypeDb
import app.needler.core.data.local.entity.PinSourceDb
import app.needler.core.data.local.entity.PullStatusDb
import app.needler.core.data.local.entity.WriteOperationTypeDb

/**
 * Every type conversion the database performs, written out one pair at a time.
 *
 * No reflection, no generic `enumValueOf` helper and no `Instant` converter:
 *
 *  * Enums store an explicit, frozen `dbValue` string rather than `name`, so a Kotlin rename can
 *    never become an accidental data migration. Reading is total - an unrecognised value degrades to
 *    a documented fallback instead of throwing inside a Flow that the UI is collecting.
 *  * Timestamps are plain `Long` epoch milliseconds in the entities, so they need no converter at
 *    all. `kotlinx.datetime.Instant` appears in `:core:domain`; turning one into the other is the
 *    mappers' job. A column holding a converted `Instant` would also make every SQL comparison
 *    (`last_played_at < ?`) depend on the converter being correct.
 *  * Booleans are Room-native (INTEGER 0/1) and are likewise not converted here.
 *
 * Registered on the database with an explicit `@TypeConverters`, never per-column.
 */
public class NeedlerTypeConverters {

    @TypeConverter
    public fun fromAlbumState(value: AlbumStateDb): String = value.dbValue

    @TypeConverter
    public fun toAlbumState(value: String): AlbumStateDb = AlbumStateDb.fromDbValue(value)

    @TypeConverter
    public fun fromPinSource(value: PinSourceDb): String = value.dbValue

    @TypeConverter
    public fun toPinSource(value: String): PinSourceDb = PinSourceDb.fromDbValue(value)

    @TypeConverter
    public fun fromDownloadState(value: DownloadStateDb): String = value.dbValue

    @TypeConverter
    public fun toDownloadState(value: String): DownloadStateDb = DownloadStateDb.fromDbValue(value)

    @TypeConverter
    public fun fromPullStatus(value: PullStatusDb): String = value.dbValue

    @TypeConverter
    public fun toPullStatus(value: String): PullStatusDb = PullStatusDb.fromDbValue(value)

    @TypeConverter
    public fun fromFavouriteType(value: FavouriteTypeDb): String = value.dbValue

    @TypeConverter
    public fun toFavouriteType(value: String): FavouriteTypeDb = FavouriteTypeDb.fromDbValue(value)

    @TypeConverter
    public fun fromWriteOperationType(value: WriteOperationTypeDb): String = value.dbValue

    @TypeConverter
    public fun toWriteOperationType(value: String): WriteOperationTypeDb =
        WriteOperationTypeDb.fromDbValue(value)
}
