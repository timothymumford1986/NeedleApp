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
 * ## Version 2: `stream_override`
 *
 * [MIGRATION_1_2] adds one table and touches nothing that exists. Per-item stream-quality overrides
 * had nowhere to live: the mode rungs are settings and sit in `DataStore`, while "this record streams
 * lossless wherever I am" is a row per item. Nothing is copied, no table is rewritten, and the search
 * triggers are untouched because neither `album` nor `track` is involved.
 *
 * ## Version 3: `pull.request_kind` and `pull.recording_mbid`
 *
 * [MIGRATION_2_3] adds two columns to `pull` and touches nothing else. `DELETE
 * /api/v1/requests/active/{mbid}` takes a `request_kind` of `album` or `track`, and the id in its
 * path is the *recording* MBID for a track request while `pull` is keyed on the release group - so
 * cancelling or retrying a track request was impossible to get right without storing both. See
 * [app.needler.core.data.local.entity.PullEntity.requestKind] for why the alternative, declining to
 * offer cancel on a track row, cannot be built without the same column.
 *
 * `pull` has no foreign key and is not an FTS content table, so rules 1 to 3 below do not apply. The
 * `album` and `track` triggers are untouched.
 *
 * ## Version 4: splitting the composite genre strings already stored
 *
 * [MIGRATION_3_4] rewrites data and changes no schema at all, which is why its exported schema is
 * version 3's with a new number on it. The server joins several genres into one field with a
 * semicolon and the mirror stored the whole string as one genre, so the device's Genres screen
 * reported 138 genres with a five-name row among them; `GenreCodec` now splits on ingest.
 *
 * **Re-ingesting on the next sync is not enough, and that is the whole reason this migration
 * exists.** A delta sync asks `getIndexes` with `ifModifiedSince` and rewrites only the albums the
 * server reports as changed - see `SyncDecision`, where a full sync happens on first connect or a
 * server identity change and at no other time - so an album nobody re-tags keeps its composite
 * column for ever. `GenreCodec.decode` splitting on read fixes the *list* without this, but not the
 * queries behind it: `album.genres LIKE '%|Indie Rock|%'` cannot match `|Acoustic Rock;Indie Rock|`,
 * so every tapped genre would have opened an empty screen. Rejected alternatives: issuing four
 * `LIKE` patterns per genre to cover the delimiter pairs, which multiplies every genre query by four
 * for ever to paper over one upgrade; and forcing a full sync on upgrade, which re-downloads the
 * whole mirror over whatever connection the user happens to be on to fix one column.
 *
 * `album` *is* an FTS content table, so rule 1 deserves an explicit answer: it does not apply,
 * because nothing is dropped, created or renamed. `UPDATE` fires `album_fts_before_update` and
 * `album_fts_after_update`, which delete and reinsert the row's index entry, so the index follows the
 * write rather than going stale behind it - and `genres` is not an indexed column in any case.
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
 * internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
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
     * Adds `stream_override`: one row per track or album the user has pinned a stream rung to.
     *
     * The DDL matches exactly what Room generates for `StreamOverrideEntity` - column order, the
     * `NOT NULL`s, the composite primary key and the backtick quoting - because Room validates the
     * live table against its own idea of the schema on the next open and fails the migration
     * otherwise. `IF NOT EXISTS` so that a build installed over a partially migrated database does
     * not abort on the table already being there.
     *
     * Nothing is dropped, copied or renamed, so rules 1 to 3 in this file's header do not apply:
     * no foreign key points at this table, no content table is rewritten, and no file path changes.
     */
    internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `stream_override` (" +
                    "`scope` TEXT NOT NULL, " +
                    "`item_id` TEXT NOT NULL, " +
                    "`rung` TEXT NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`scope`, `item_id`)" +
                    ")",
            )
        }
    }

    /**
     * Adds `pull.request_kind` and `pull.recording_mbid`, so a *track* request can be cancelled and
     * retried against the id its endpoint actually takes.
     *
     * `ALTER TABLE ... ADD COLUMN` only, which SQLite does in place: no table is copied, renamed or
     * dropped, so no child row can be lost and no FTS trigger needs recreating. Nothing is
     * back-filled either - every existing row is an album request, which is exactly what the
     * `DEFAULT 'album'` makes it, and no existing row has a recording MBID to recover.
     *
     * The default is written here **and** declared on
     * [app.needler.core.data.local.entity.PullEntity.requestKind] as `defaultValue`, per rule 4 in
     * this file's header: Room reads `dflt_value` back out of `PRAGMA table_info` on the next open
     * and compares it against its own exported schema, so a default present in one place and absent
     * from the other fails validation on the *following* version rather than on this one. The
     * quoting matters for the same reason - a TEXT default is `'album'` with the quotes, which is
     * what SQLite reports back.
     *
     * `recording_mbid` is deliberately nullable with no default. It is meaningful only on a track
     * row, and `NOT NULL DEFAULT ''` would make "this row has no recording MBID" and "this row is an
     * album" the same value, which is the distinction the column exists to make.
     */
    internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `pull` ADD COLUMN `request_kind` TEXT NOT NULL DEFAULT 'album'")
            db.execSQL("ALTER TABLE `pull` ADD COLUMN `recording_mbid` TEXT")
        }
    }

    /**
     * Rewrites `album.genres` so a composite value already stored becomes several delimited genres.
     *
     * One `UPDATE`, no schema change: the stored form is `|a;b;c|` and the wanted form is `|a|b|c|`,
     * which is three nested `REPLACE` calls rather than the recursive CTE a general split would need.
     * The first two collapse a space either side of the separator, because `|Rock; Pop|` would
     * otherwise become `|Rock| Pop|` and `GenreCodec.likePattern` trims the term it brackets, so
     * `%|Pop|%` would not match `| Pop|`. The third does the actual substitution.
     *
     * `WHERE genres LIKE '%;%'` so the statement touches only rows that need it. Without it every
     * album row in the mirror is rewritten, every one fires the two `album_fts` update triggers, and
     * a library of five thousand albums pays for that on an upgrade that changes nothing for it.
     *
     * De-duplication is deliberately not attempted in SQL. A composite naming the same genre twice
     * becomes `|Rock|rock|`, which `GenreCodec.decode` folds to one and which `observeAlbumCountForGenre`
     * counts once anyway because it counts album rows, not occurrences - so the only thing SQL could
     * add here is a way to get it wrong.
     *
     * See this file's header for why this runs at all rather than waiting for the next sync, and for
     * why rule 1 does not apply to an `UPDATE` on a content table.
     */
    internal val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE `album` SET `genres` = " +
                    "REPLACE(REPLACE(REPLACE(`genres`, ' ;', ';'), '; ', ';'), ';', '|') " +
                    "WHERE `genres` LIKE '%;%'",
            )
        }
    }

    /**
     * Every migration, in ascending order. Passed to `addMigrations` as a whole, so Room can also
     * compose them to skip versions.
     */
    public val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
