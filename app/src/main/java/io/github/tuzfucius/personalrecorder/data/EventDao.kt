package io.github.tuzfucius.personalrecorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int = 30): Flow<List<EventEntity>>

    @Query(
        "SELECT * FROM events " +
            "WHERE timestamp >= :startMillis AND timestamp < :endMillis " +
            "ORDER BY timestamp DESC"
    )
    fun getTodayEvents(startMillis: Long, endMillis: Long): Flow<List<EventEntity>>

    @Query(
        "SELECT COUNT(*) FROM events " +
            "WHERE timestamp >= :startMillis AND timestamp < :endMillis"
    )
    fun getEventCount(startMillis: Long, endMillis: Long): Flow<Int>

    @Delete
    suspend fun deleteEvent(event: EventEntity)
}
