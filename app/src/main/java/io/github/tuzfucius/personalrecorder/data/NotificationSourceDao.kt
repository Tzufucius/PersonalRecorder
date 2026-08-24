package io.github.tuzfucius.personalrecorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationSourceDao {
    @Query("SELECT * FROM notification_sources WHERE packageName = :packageName LIMIT 1")
    suspend fun getNotificationSource(packageName: String): NotificationSourceEntity?

    @Query("SELECT * FROM notification_sources ORDER BY lastSeenAt DESC, packageName ASC")
    fun observeNotificationSources(): Flow<List<NotificationSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotificationSource(source: NotificationSourceEntity)

    @Query(
        "UPDATE notification_sources SET " +
            "lastSeenAt = :now, " +
            "observedNotificationCount = observedNotificationCount + 1, " +
            "lastKnownLabel = COALESCE(:label, lastKnownLabel), " +
            "lastKnownHasLauncher = COALESCE(:hasLauncher, lastKnownHasLauncher) " +
            "WHERE packageName = :packageName"
    )
    suspend fun updateObservedSource(
        packageName: String,
        now: Long,
        label: String?,
        hasLauncher: Boolean?,
    )

    /** Insert once, then increment atomically so concurrent notifications are not lost. */
    @Transaction
    suspend fun observeNotificationSource(
        packageName: String,
        now: Long,
        label: String?,
        hasLauncher: Boolean?,
    ) {
        insertNotificationSource(
            NotificationSourceEntity(
                packageName = packageName,
                lastKnownLabel = label,
                firstSeenAt = now,
                lastSeenAt = now,
                observedNotificationCount = 0,
                lastKnownHasLauncher = hasLauncher,
            )
        )
        updateObservedSource(packageName, now, label, hasLauncher)
    }
}
