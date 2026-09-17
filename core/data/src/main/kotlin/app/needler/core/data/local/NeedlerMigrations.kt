package app.needler.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations, from version 1 onwards.
 *
 * ## Why there is no destructive fallback
 *
 * `fallbackToDestructiveMigration()` deletes the database and recreates it. For Needler that means
 * discarding the pins, the cache index and therefore - once the orphaned files are swept - a
 * multi-gigabyte audio cache, and forcing a full re-sync of the whole metadata mirror over whatever
 * connection the user happens to have. REQUIREMENTS.md forbids it: "Migrations are written by hand
 * from the first release, since a destructive fallback would throw away a multi-gigabyte cache and
 * force a full re-sync." [NeedlerDatabase.create] therefore never calls it, and a missing migration
 * must surface as a crash in development rather than as silent data loss in the field.
 *
 * ## Version 1 is the first release, so this list is empty
 *
 * It is not a placeholder: an empty array wired into the builder is what makes the *next* schema
 * change a one-line addition here instead of a decision about whether to add migrations at all.
 *
 * ## Writing one
 *
 * Rules that apply to every migration in this database, learned from the shape of the schema:
 *
 *  1. **Re-create the FTS triggers whenever `album`, `track`, `album_fts` or `track_fts` is
 *     rewritten.** Room's schema validation ignores triggers, so a migration that drops and
 *     recreates a content table will pass validation with a silently dead search index. Call
 *     [FtsTriggers.dropAll], then [FtsTriggers.createAll], then [FtsTriggers.rebuildAll].
 *  2. **Never rebuild a table by copy-rename while a foreign key points at it.** `track`,
 *     `playlist_track` and the cascade from `album` mean a naive
 *     "create new, copy, drop old, rename" cycle deletes child rows on the drop. Use
 *     `PRAGMA foreign_keys = OFF` for the duration (Room runs migrations outside its own foreign-key
 *     enforcement, but an explicit `PRAGMA legacy_alter_table` may also be needed for renames), and
 *     verify the child tables still hold rows afterwards.
 *  3. **Never touch `audio_cache.file_path` semantics without a file sweep.** Rows and files are
 *     two halves of one fact; a migration that changes how paths are built must either move the
 *     files or clear the rows, and clearing the rows means the bytes must be deleted too.
 *  4. **Add columns with a `defaultValue`** on the entity as well as in the SQL, or Room's exported
 *     schema and the live table will disagree on the default and validation will fail on the *next*
 *     version rather than this one.
 *
 * A migration looks like this:
 *
 * ```
 * internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE album ADD COLUMN label TEXT")
 *         // Recreate the search triggers if a content table was rewritten:
 *         // FtsTriggers.dropAll(db); FtsTriggers.createAll(db); FtsTriggers.rebuildAll(db)
 *     }
 * }
 * ```
 *
 * and is added to [ALL]. Export the new schema JSON in the same commit: the exported schema is what
 * makes a Room migration test able to prove the migration produces the schema the entities describe.
 */
public object NeedlerMigrations {

    /**
     * Every migration, in ascending order. Passed to `addMigrations` as a whole, so Room can also
     * compose them to skip versions.
     */
    public val ALL: Array<Migration> = emptyArray()

    /**
     * Convenience for a migration that has rewritten `album` or `track`: drops the search triggers,
     * recreates them and rebuilds both indexes from their content tables.
     *
     * Kept here rather than inline in each migration so that a future migration cannot recreate
     * seven of the eight triggers and leave search subtly wrong for updates only.
     */
    public fun refreshSearchIndex(db: SupportSQLiteDatabase) {
        FtsTriggers.dropAll(db)
        FtsTriggers.createAll(db)
        FtsTriggers.rebuildAll(db)
    }
}
