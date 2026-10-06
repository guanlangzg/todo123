package app.arttodo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The single persistent store. Kotlin owns every read and write here; Rust never touches it
 * (架构契约 §1 and §4).
 *
 * `exportSchema` is on because the exported schema JSON under `app/schemas` must be committed: it
 * is the evidence migrations and backup compatibility depend on (工程布局与版本锁定.md §1).
 */
@Database(
    entities = [
        TaskEntity::class,
        TaskTitleRevisionEntity::class,
        DailyOccurrenceEntity::class,
        OccurrenceCompletionEventEntity::class,
        DailyOccurrenceViewEntity::class,
        TemporaryCompletionEventEntity::class,
        FocusSessionEntity::class,
        SessionSegmentEntity::class,
        ActiveSessionSlotEntity::class,
        SessionHeartbeatEntity::class,
        LedgerEntryEntity::class,
        LedgerEntryAuditEntity::class,
        ZoneEpochEntity::class,
        CommandLogEntity::class,
        ArtAssetEntity::class,
        AppSettingEntity::class,
        BackupRecordEntity::class,
    ],
    // Keep the literal in step with SCHEMA_VERSION below; DatabaseMigrationTest fails if the
    // migration chain does not reach the version Room actually created.
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun titleRevisionDao(): TitleRevisionDao

    abstract fun occurrenceDao(): OccurrenceDao

    abstract fun sessionDao(): SessionDao

    abstract fun ledgerDao(): LedgerDao

    abstract fun supportDao(): SupportDao

    companion object {
        const val DATABASE_NAME = "app.db"

        /**
         * The version Room is expected to create. Must equal the `version` on `@Database`; the
         * database tests assert the migration chain covers `1..SCHEMA_VERSION`, so a bump without a
         * migration fails loudly instead of depending on the destructive fallback.
         */
        const val SCHEMA_VERSION = 2

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context, DATABASE_NAME).also { instance = it }
        }

        /**
         * Opens one database file with exactly the production configuration.
         *
         * Tests deliberately go through this instead of calling `Room.databaseBuilder` themselves:
         * the foreign-key pragma and the migration list are part of what the data layer promises,
         * so a test that built its own database would not be testing this class.
         */
        fun build(context: Context, databaseName: String = DATABASE_NAME): AppDatabase = Room
            .databaseBuilder(context.applicationContext, AppDatabase::class.java, databaseName)
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*AppMigrations.ALL)
            .addCallback(foreignKeyCallback)
            .build()

        /**
         * Foreign keys are load-bearing for this schema: they are what makes a second active session
         * physically impossible (`active_session_slot` keyed at 1) and what cascades a deleted
         * occurrence to its derived view. SQLite defaults them OFF per connection, so this is
         * asserted explicitly rather than left to a default.
         */
        private val foreignKeyCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}
