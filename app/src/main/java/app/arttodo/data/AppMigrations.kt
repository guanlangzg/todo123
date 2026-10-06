package app.arttodo.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema history. Every version bump gets a real [Migration] here; there is deliberately
 * no `fallbackToDestructiveMigration` anywhere in the app, so a missing step makes Room throw
 * instead of silently dropping the user's history.
 *
 * The exported JSON next to each step lives in `app/schemas/app.arttodo.data.AppDatabase/` and is
 * committed, which is what makes these migrations testable (see `DatabaseMigrationTest`).
 */
object AppMigrations {

    /**
     * 1 -> 2: one title revision per (task, instant).
     *
     * Slicing labels each piece of an interval with the title in force at the piece's start
     * (AC-14), and it resolves that title from the revisions of the task. Two revisions carrying
     * the same `effective_wall_ms` therefore make the label of the piece starting exactly then
     * depend on which row the query returns first. Schema 2 makes the instant unique so the label
     * is a function of the data alone.
     *
     * Existing rows can contain such pairs (schema 1 allowed them), so the migration repairs them
     * first: for one instant the newest revision wins, which matches the un-repaired behaviour of
     * "last title written is the title in force". No row is dropped except a same-instant
     * duplicate; every distinct instant survives.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM task_title_revision WHERE revision_seq NOT IN (" +
                    "SELECT MAX(revision_seq) FROM task_title_revision " +
                    "GROUP BY task_id, effective_wall_ms)",
            )
            // The index exists in schema 1 under the same generated name; it must be recreated
            // unique, and Room validates the result against the entity declaration afterwards.
            db.execSQL("DROP INDEX IF EXISTS index_task_title_revision_task_id_effective_wall_ms")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_task_title_revision_task_id_effective_wall_ms " +
                    "ON task_title_revision (task_id, effective_wall_ms)",
            )
        }
    }

    /** Oldest version this app can open without a migration. */
    const val BASE_VERSION = 1

    /** All steps, in order. Room needs every step from the stored version up to the current one. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
