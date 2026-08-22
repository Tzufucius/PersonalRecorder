package io.github.tuzfucius.personalrecorder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDaoInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: EventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.eventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndQueryRecentEvents() = runBlocking {
        dao.insertEvent(sampleEvent(id = "one", timestamp = 1_000L))
        dao.insertEvent(sampleEvent(id = "two", timestamp = 2_000L))

        val recent = dao.getRecentEvents().first()

        assertEquals(listOf("two", "one"), recent.map { it.id })
    }

    @Test
    fun countUsesTimeWindow() = runBlocking {
        dao.insertEvent(sampleEvent(id = "inside", timestamp = 2_000L))
        dao.insertEvent(sampleEvent(id = "outside", timestamp = 9_000L))

        assertEquals(1, dao.getEventCount(1_000L, 3_000L).first())
    }

    private fun sampleEvent(id: String, timestamp: Long) = EventEntity(
        id = id,
        timestamp = timestamp,
        source = "notification",
        packageName = "source.app",
        title = "title",
        content = "content",
        bigText = null,
        textLines = emptyList(),
        notificationKey = id,
        notificationId = 1,
        category = null,
        channelId = null,
        groupKey = null,
        isOngoing = false,
        isGroupSummary = false,
        isClearable = true,
        createdAt = timestamp
    )
}
