package io.github.tuzfucius.personalrecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EventEntity::class, ArchiveSegmentEntity::class, ArchiveSyncStateEntity::class, ArchiveConflictEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "personal_recorder.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE events ADD COLUMN isGroupSummary INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS archive_segments (
                        segmentId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        slot TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        startMillis INTEGER NOT NULL,
                        endMillis INTEGER NOT NULL,
                        eventCount INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        closed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(segmentId)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_segments_date ON archive_segments(date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_segments_closed ON archive_segments(closed)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS archive_sync_states (
                        segmentId TEXT NOT NULL,
                        backend TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        lastAttemptAt INTEGER,
                        lastError TEXT,
                        remoteId TEXT,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(segmentId, backend)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_sync_states_status ON archive_sync_states(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_sync_states_backend_status ON archive_sync_states(backend, status)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS archive_conflicts (
                        conflictId TEXT NOT NULL,
                        segmentId TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        localFilePath TEXT NOT NULL,
                        remoteFilePath TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        resolved INTEGER NOT NULL,
                        PRIMARY KEY(conflictId)
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_conflicts_segmentId ON archive_conflicts(segmentId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_archive_conflicts_resolved ON archive_conflicts(resolved)")
            }
        }
    }
}
