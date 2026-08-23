package io.github.tuzfucius.personalrecorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Query(
        "SELECT timestamp, packageName, isOngoing, isGroupSummary FROM events " +
            "WHERE timestamp >= :startMillis AND timestamp < :endMillis " +
            "AND packageName != :excludedPackageName ORDER BY timestamp ASC"
    )
    fun getStatisticsEvents(
        startMillis: Long,
        endMillis: Long,
        excludedPackageName: String
    ): Flow<List<StatisticsEventRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventsIgnore(events: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): EventEntity?

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

    @Upsert
    suspend fun upsertArchiveSegment(segment: ArchiveSegmentEntity)

    @Query("SELECT * FROM archive_segments ORDER BY date ASC, startMillis ASC")
    fun getArchiveSegments(): Flow<List<ArchiveSegmentEntity>>

    @Query("SELECT segmentId FROM archive_segments")
    suspend fun getArchivedSegmentIds(): List<String>

    @Query(
        "SELECT * FROM archive_segments WHERE closed = 1 " +
            "AND segmentId NOT IN (SELECT segmentId FROM archive_sync_states WHERE backend = :backend AND status = 'SYNCED') " +
            "ORDER BY startMillis ASC"
    )
    fun getPendingArchiveSegments(backend: String): Flow<List<ArchiveSegmentEntity>>

    @Query(
        "SELECT * FROM archive_segments WHERE closed = 1 " +
            "AND segmentId NOT IN (SELECT segmentId FROM archive_sync_states WHERE backend = :backend AND status = 'SYNCED') " +
            "AND (:includeFailed OR segmentId NOT IN (SELECT segmentId FROM archive_sync_states WHERE backend = :backend AND status = 'FAILED')) " +
            "ORDER BY startMillis ASC"
    )
    suspend fun getPendingArchiveSegmentsForSync(backend: String, includeFailed: Boolean): List<ArchiveSegmentEntity>

    @Query("SELECT * FROM archive_segments WHERE date = :date ORDER BY startMillis ASC")
    suspend fun getArchiveSegmentsForDate(date: String): List<ArchiveSegmentEntity>

    @Query("SELECT * FROM archive_segments WHERE segmentId = :segmentId LIMIT 1")
    suspend fun getArchiveSegment(segmentId: String): ArchiveSegmentEntity?

    @Query(
        "SELECT * FROM events WHERE timestamp >= :startMillis AND timestamp < :endMillis " +
            "ORDER BY timestamp ASC, id ASC"
    )
    suspend fun getEventsForArchive(startMillis: Long, endMillis: Long): List<EventEntity>

    @Query("SELECT MIN(timestamp) AS minTimestamp, MAX(timestamp) AS maxTimestamp FROM events")
    suspend fun getEventTimestampBounds(): EventTimestampBounds

    @Upsert
    suspend fun upsertArchiveSyncState(state: ArchiveSyncStateEntity)

    @Upsert
    suspend fun upsertArchiveConflict(conflict: ArchiveConflictEntity)

    @Query("SELECT COUNT(*) FROM archive_conflicts WHERE resolved = 0")
    fun getUnresolvedConflictCount(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM archive_sync_states WHERE backend = :backend " +
            "AND status IN ('PENDING', 'PENDING_UPLOAD')"
    )
    suspend fun countPendingUploads(backend: String): Int

    @Query(
        "SELECT COUNT(*) FROM archive_sync_states WHERE backend = :backend " +
            "AND status = 'PENDING_DOWNLOAD'"
    )
    suspend fun countPendingDownloads(backend: String): Int

    @Query("SELECT COUNT(*) FROM events WHERE timestamp >= :startMillis AND timestamp < :endMillis")
    suspend fun countEventsBetween(startMillis: Long, endMillis: Long): Int

    @Query("SELECT * FROM archive_conflicts WHERE resolved = 0 ORDER BY createdAt DESC")
    fun getUnresolvedConflicts(): Flow<List<ArchiveConflictEntity>>

    @Query(
        "SELECT * FROM archive_sync_states WHERE segmentId = :segmentId AND backend = :backend LIMIT 1"
    )
    suspend fun getArchiveSyncState(segmentId: String, backend: String): ArchiveSyncStateEntity?

    @Query("SELECT * FROM archive_sync_states WHERE backend = :backend AND status = :status")
    fun getArchiveSyncStates(backend: String, status: String): Flow<List<ArchiveSyncStateEntity>>

    @Query("SELECT MAX(updatedAt) FROM archive_sync_states WHERE backend = :backend AND status = 'SYNCED'")
    fun getLastSyncedAt(backend: String): Flow<Long?>
}

data class StatisticsEventRow(
    val timestamp: Long,
    val packageName: String,
    val isOngoing: Boolean,
    val isGroupSummary: Boolean
)

data class EventTimestampBounds(
    val minTimestamp: Long?,
    val maxTimestamp: Long?,
)
