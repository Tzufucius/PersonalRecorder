package io.github.tuzfucius.personalrecorder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSourceDaoInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: NotificationSourceDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.notificationSourceDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun concurrentObservationsIncrementAtomically() = runBlocking {
        coroutineScope {
            repeat(20) { index ->
                launch {
                    dao.observeNotificationSource("com.example.concurrent", 1_000L + index, null, null)
                }
            }
        }

        val source = dao.getNotificationSource("com.example.concurrent")
        assertEquals(20L, source?.observedNotificationCount)
        val nonNullSource = source ?: error("source missing")
        assertTrue(nonNullSource.firstSeenAt in 1_000L..1_019L)
        assertTrue(nonNullSource.lastSeenAt in 1_000L..1_019L)
        assertEquals(20L, dao.observeNotificationSources().first().single().observedNotificationCount)
    }
}
