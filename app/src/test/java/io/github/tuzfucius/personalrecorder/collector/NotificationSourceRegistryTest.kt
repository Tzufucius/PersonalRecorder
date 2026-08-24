package io.github.tuzfucius.personalrecorder.collector

import io.github.tuzfucius.personalrecorder.data.NotificationSourceDao
import io.github.tuzfucius.personalrecorder.data.NotificationSourceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourceRegistryTest {
    @Test
    fun firstAndSecondObservationKeepFirstSeenAndIncrementCount() = runBlocking {
        val dao = FakeNotificationSourceDao()
        val registry = NotificationSourceRegistry(
            dao = dao,
            metadataResolver = NotificationSourceMetadataResolver {
                NotificationSourceMetadata(label = "Hidden app", hasLauncher = false)
            },
        )

        registry.observe("com.example.hidden", now = 1_000L)
        registry.observe("com.example.hidden", now = 2_000L)

        assertEquals(
            NotificationSourceEntity(
                packageName = "com.example.hidden",
                lastKnownLabel = "Hidden app",
                firstSeenAt = 1_000L,
                lastSeenAt = 2_000L,
                observedNotificationCount = 2L,
                lastKnownHasLauncher = false,
            ),
            dao.getNotificationSource("com.example.hidden"),
        )
    }

    @Test
    fun resolverFailureDoesNotEraseExistingLabel() = runBlocking {
        val dao = FakeNotificationSourceDao()
        val registry = NotificationSourceRegistry(
            dao = dao,
            metadataResolver = NotificationSourceMetadataResolver { packageName ->
                if (packageName == "com.example.hidden") {
                    throw SecurityException("package visibility")
                }
                NotificationSourceMetadata(label = null, hasLauncher = null)
            },
        )
        dao.observeNotificationSource("com.example.hidden", 1_000L, "Existing label", true)

        registry.observe("com.example.hidden", now = 2_000L)

        val source = dao.getNotificationSource("com.example.hidden")
        assertEquals("Existing label", source?.lastKnownLabel)
        assertEquals(2L, source?.observedNotificationCount)
        assertTrue(source?.lastKnownHasLauncher == true)
    }

    private class FakeNotificationSourceDao : NotificationSourceDao {
        private val sources = linkedMapOf<String, NotificationSourceEntity>()
        private val state = MutableStateFlow(emptyList<NotificationSourceEntity>())

        override suspend fun getNotificationSource(packageName: String): NotificationSourceEntity? = sources[packageName]

        override fun observeNotificationSources(): Flow<List<NotificationSourceEntity>> = state

        override suspend fun insertNotificationSource(source: NotificationSourceEntity) {
            sources.putIfAbsent(source.packageName, source)
            state.value = sources.values.toList()
        }

        override suspend fun updateObservedSource(
            packageName: String,
            now: Long,
            label: String?,
            hasLauncher: Boolean?,
        ) {
            val current = sources.getValue(packageName)
            sources[packageName] = current.copy(
                lastSeenAt = now,
                observedNotificationCount = current.observedNotificationCount + 1,
                lastKnownLabel = label ?: current.lastKnownLabel,
                lastKnownHasLauncher = hasLauncher ?: current.lastKnownHasLauncher,
            )
            state.value = sources.values.toList()
        }

        override suspend fun observeNotificationSource(
            packageName: String,
            now: Long,
            label: String?,
            hasLauncher: Boolean?,
        ) {
            insertNotificationSource(
                NotificationSourceEntity(packageName, label, now, now, 0L, hasLauncher),
            )
            updateObservedSource(packageName, now, label, hasLauncher)
        }
    }
}
