package io.github.tuzfucius.personalrecorder.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2PreservesRowsAndDefaultsGroupSummary() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    "old", 1_000L, "notification", "source.app", "title", "content", null,
                    "", "key", 1, null, null, null, 0, 1, 1_000L
                )
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, AppDatabase.MIGRATION_1_2)
            .query("SELECT id, isGroupSummary FROM events")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("old", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
        }
    }

    @Test
    fun migrate2To3CreatesArchiveTables() {
        helper.createDatabase("migration-test-v2.db", 2).close()

        helper.runMigrationsAndValidate(
            "migration-test-v2.db",
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        ).query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('archive_segments', 'archive_sync_states') ORDER BY name"
        ).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("archive_segments", "archive_sync_states"), names)
        }
    }

    @Test
    fun migrate3To4CreatesConflictTableWithoutDroppingArchiveTables() {
        helper.createDatabase("migration-test-v3.db", 3).close()

        helper.runMigrationsAndValidate(
            "migration-test-v3.db",
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        ).query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('archive_segments', 'archive_sync_states', 'archive_conflicts') ORDER BY name"
        ).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("archive_conflicts", "archive_segments", "archive_sync_states"), names)
        }
    }

    @Test
    fun migrate4To5AddsVerifiedArchiveStatus() {
        helper.createDatabase("migration-test-v4.db", 4).apply {
            execSQL(
                "INSERT INTO archive_segments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("segment", "2026-08-23", "FIRST_HALF", "archive/a.jsonl", 1L, 2L, 0, "sha", 1, 1L),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            "migration-test-v4.db",
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        ).query(
            "SELECT verificationStatus FROM archive_segments LIMIT 1"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("VERIFIED", cursor.getString(0))
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test.db"
    }
}
