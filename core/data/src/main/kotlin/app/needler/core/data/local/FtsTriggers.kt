package app.needler.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The triggers that keep the external-content FTS4 indexes in step with `album` and `track`.
 *
 * ## Why these are hand-written
 *
 * `album_fts` and `track_fts` are declared with `contentEntity`, so Room creates them with FTS4's
 * `content=<table>` option: the index stores the inverted index only and reads column values back
 * from the content table. SQLite does **not** maintain such an index automatically, and Room does
 * not generate the triggers for it either - it only issues the `CREATE VIRTUAL TABLE`. Without the
 * statements below, `album_fts` stays empty for every row written after creation and offline search
 * silently returns nothing. That failure has no error message anywhere, which is why this file is
 * one of the two places a broken search should be looked for.
 *
 * ## The shape is the one from SQLite's own FTS4 documentation
 *
 * Delete on BEFORE UPDATE and BEFORE DELETE, insert on AFTER UPDATE and AFTER INSERT. The pairing
 * matters: deleting in a BEFORE trigger means the index row is removed while the old content row is
 * still readable, and inserting in an AFTER trigger means the new values are already committed to
 * the content table when FTS reads them back.
 *
 * `INSERT OR REPLACE` on a content table fires the delete and insert triggers in that order, so the
 * index stays correct even then - but the DAOs use `@Upsert` anyway, because REPLACE on `album`
 * would cascade its tracks away.
 *
 * ## Where they are installed
 *
 * [createAll] runs from the database's `onCreate` callback, and must also run from any migration
 * that recreates `album`, `track` or either FTS table. Room's schema validation does not look at
 * triggers, so adding them cannot break the identity-hash check.
 */
public object FtsTriggers {

    /** Statements that create every trigger, in a fixed order so a migration can replay them. */
    public val CREATE_STATEMENTS: List<String> = listOf(
        // album -> album_fts (title, artist_name)
        """
        CREATE TRIGGER IF NOT EXISTS album_fts_before_update BEFORE UPDATE ON album BEGIN
            DELETE FROM album_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS album_fts_before_delete BEFORE DELETE ON album BEGIN
            DELETE FROM album_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS album_fts_after_update AFTER UPDATE ON album BEGIN
            INSERT INTO album_fts(docid, title, artist_name)
            VALUES (new.rowid, new.title, new.artist_name);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS album_fts_after_insert AFTER INSERT ON album BEGIN
            INSERT INTO album_fts(docid, title, artist_name)
            VALUES (new.rowid, new.title, new.artist_name);
        END
        """.trimIndent(),
        // track -> track_fts (title)
        """
        CREATE TRIGGER IF NOT EXISTS track_fts_before_update BEFORE UPDATE ON track BEGIN
            DELETE FROM track_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS track_fts_before_delete BEFORE DELETE ON track BEGIN
            DELETE FROM track_fts WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS track_fts_after_update AFTER UPDATE ON track BEGIN
            INSERT INTO track_fts(docid, title) VALUES (new.rowid, new.title);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS track_fts_after_insert AFTER INSERT ON track BEGIN
            INSERT INTO track_fts(docid, title) VALUES (new.rowid, new.title);
        END
        """.trimIndent(),
    )

    /** Drops every trigger. Needed before a migration rewrites a content table. */
    public val DROP_STATEMENTS: List<String> = listOf(
        "DROP TRIGGER IF EXISTS album_fts_before_update",
        "DROP TRIGGER IF EXISTS album_fts_before_delete",
        "DROP TRIGGER IF EXISTS album_fts_after_update",
        "DROP TRIGGER IF EXISTS album_fts_after_insert",
        "DROP TRIGGER IF EXISTS track_fts_before_update",
        "DROP TRIGGER IF EXISTS track_fts_before_delete",
        "DROP TRIGGER IF EXISTS track_fts_after_update",
        "DROP TRIGGER IF EXISTS track_fts_after_insert",
    )

    /**
     * FTS4's own maintenance command. Discards the index and rebuilds it from the content table.
     *
     * The correct way to empty or repair an external-content index: a plain `DELETE FROM album_fts`
     * is not supported on a `content=` table.
     */
    public val REBUILD_STATEMENTS: List<String> = listOf(
        "INSERT INTO album_fts(album_fts) VALUES('rebuild')",
        "INSERT INTO track_fts(track_fts) VALUES('rebuild')",
    )

    public fun createAll(db: SupportSQLiteDatabase) {
        CREATE_STATEMENTS.forEach(db::execSQL)
    }

    public fun dropAll(db: SupportSQLiteDatabase) {
        DROP_STATEMENTS.forEach(db::execSQL)
    }

    /** Rebuilds both indexes from their content tables. Cheap enough for a few thousand albums. */
    public fun rebuildAll(db: SupportSQLiteDatabase) {
        REBUILD_STATEMENTS.forEach(db::execSQL)
    }
}
