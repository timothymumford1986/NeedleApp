package app.needler.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import app.needler.core.data.mapper.GenreCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration list, and what the newest migration actually executes.
 *
 * ## What this can and cannot prove
 *
 * Proving that a migration produces the schema the entities describe needs Room's
 * `MigrationTestHelper`, which is instrumented: a real SQLite file, a real device or emulator. This
 * module has no such test for [NeedlerMigrations.MIGRATION_1_2] either, and one written here could
 * not be run on a machine with no device attached - so what is asserted instead is the part that is
 * pure, and it is the part that has a history of going wrong:
 *
 *  * **the list is contiguous and complete.** [NeedlerDatabase] never calls
 *    `fallbackToDestructiveMigration`, by requirement - REQUIREMENTS.md "Secrets and migrations":
 *    "migrations are written by hand from the first release, since a destructive fallback would
 *    throw away a multi-gigabyte cache and force a full re-sync". So bumping
 *    [NeedlerDatabase.VERSION] without adding a migration is not a caught mistake, it is a crash on
 *    the next upgrade in the field, and nothing else in the build notices;
 *  * **the SQL is additive.** Rule 2 in [NeedlerMigrations]' header forbids rebuilding a table by
 *    copy-rename while a foreign key points at it, and rule 1 requires the FTS triggers be
 *    recreated whenever `album` or `track` is rewritten. A migration that only adds columns to
 *    `pull` needs neither, and this asserts that it is in fact only adding columns - which is what
 *    makes skipping both rules correct rather than an omission;
 *  * **the TEXT default carries its quotes.** Rule 4: Room reads `dflt_value` back out of
 *    `PRAGMA table_info` and compares it against its own exported schema, so `DEFAULT album`
 *    instead of `DEFAULT 'album'` fails validation on the *next* version rather than on this one -
 *    which is the worst possible place for it to surface;
 *  * **the data rewrite agrees with the codec it exists to catch up with.**
 *    [NeedlerMigrations.MIGRATION_3_4] changes no schema at all, so Room's own validation has
 *    nothing to say about it. What can go wrong is drift: `GenreCodec` changing which character it
 *    splits on while the migration keeps substituting the old one, which leaves a genre list that is
 *    right and every genre screen behind it empty. The statement is therefore asserted against the
 *    codec's constants rather than against its own literals.
 */
public class NeedlerMigrationsTest {

    @Test
    public fun `every version step from 1 to the current one has a migration`() {
        val steps: List<Pair<Int, Int>> = NeedlerMigrations.ALL
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertEquals(
            (1 until NeedlerDatabase.VERSION).map { it to it + 1 },
            steps,
        )
    }

    @Test
    public fun `MIGRATION_2_3 only adds columns to pull`() {
        val statements: List<String> = statementsOf(NeedlerMigrations.MIGRATION_2_3)

        assertEquals(2, statements.size)
        statements.forEach { sql ->
            assertTrue("not additive: " + sql, sql.contains("ADD COLUMN"))
            assertTrue("not on pull: " + sql, sql.contains("`pull`"))
            // Rules 1 to 3 in NeedlerMigrations are skipped on the strength of this: nothing is
            // dropped, copied or renamed, so no child row is lost and no FTS trigger goes stale.
            listOf("DROP", "RENAME", "INSERT INTO", "CREATE TABLE").forEach { forbidden ->
                assertFalse(forbidden + " in: " + sql, sql.contains(forbidden))
            }
        }
    }

    @Test
    public fun `request_kind is added NOT NULL with a quoted album default`() {
        val sql: String = statementsOf(NeedlerMigrations.MIGRATION_2_3)
            .single { it.contains("request_kind") }

        assertTrue(sql, sql.contains("TEXT NOT NULL"))
        // Quoted, or SQLite reports a default of `album` as an unquoted identifier and Room's
        // comparison against the exported schema fails on the version after this one.
        assertTrue(sql, sql.contains("DEFAULT 'album'"))
    }

    /**
     * `recording_mbid` is nullable on purpose, with no default.
     *
     * It is meaningful only on a track row, and `NOT NULL DEFAULT ''` would make "this row has no
     * recording MBID" and "this row is an album request" the same value - which is exactly the
     * distinction the column exists to draw.
     */
    @Test
    public fun `recording_mbid is added nullable and with no default`() {
        val sql: String = statementsOf(NeedlerMigrations.MIGRATION_2_3)
            .single { it.contains("recording_mbid") }

        assertFalse(sql, sql.contains("NOT NULL"))
        assertFalse(sql, sql.contains("DEFAULT"))
    }

    /**
     * [NeedlerMigrations.MIGRATION_3_4] rewrites data and nothing else.
     *
     * The guard is the part worth pinning. Without `WHERE genres LIKE '%;%'` the statement rewrites
     * every album row in the mirror and fires the two `album_fts` update triggers for each, which on
     * a five-thousand-album library is a long upgrade to change nothing.
     */
    @Test
    public fun `MIGRATION_3_4 rewrites only the album rows holding a composite genre`() {
        val sql: String = statementsOf(NeedlerMigrations.MIGRATION_3_4).single()

        assertTrue(sql, sql.contains("UPDATE `album`"))
        assertTrue(sql, sql.contains("WHERE `genres` LIKE '%;%'"))
        // Rule 1 in NeedlerMigrations is skipped on the strength of this: an UPDATE keeps the FTS
        // index in step through the triggers, where a drop-and-recreate would not.
        listOf("DROP", "RENAME", "CREATE", "DELETE", "ALTER").forEach { forbidden ->
            assertFalse(forbidden + " in: " + sql, sql.contains(forbidden))
        }
    }

    /**
     * The migration substitutes exactly the separator [GenreCodec] splits on, for exactly the
     * delimiter it encodes with.
     *
     * Asserted against the constants rather than against the literals, so that changing either one
     * in the codec and leaving the migration behind fails here instead of on a device, where the
     * symptom is a genre list that is right and a genre screen that is empty.
     */
    @Test
    public fun `MIGRATION_3_4 substitutes the codec's separator for the codec's delimiter`() {
        val sql: String = statementsOf(NeedlerMigrations.MIGRATION_3_4).single()
        val separator: String = GenreCodec.COMPOSITE_SEPARATOR
        val delimiter: String = GenreCodec.DELIMITER

        // The outermost REPLACE is the substitution itself...
        assertTrue(sql, sql.contains("'" + separator + "', '" + delimiter + "'"))
        // ...and the two inner ones collapse a space on either side of the separator first, because
        // `likePattern` trims the term it brackets and `| Pop|` would then match nothing.
        assertTrue(sql, sql.contains("' " + separator + "', '" + separator + "'"))
        assertTrue(sql, sql.contains("'" + separator + " ', '" + separator + "'"))
    }

    /** Every `execSQL` a migration issues, in order. */
    private fun statementsOf(migration: androidx.room.migration.Migration): List<String> {
        val db: SupportSQLiteDatabase = mockk(relaxed = true)
        val captured = slot<String>()
        val statements: MutableList<String> = mutableListOf()
        every { db.execSQL(capture(captured)) } answers { statements.add(captured.captured); Unit }

        migration.migrate(db)

        verify(atLeast = 1) { db.execSQL(any<String>()) }
        return statements
    }
}
