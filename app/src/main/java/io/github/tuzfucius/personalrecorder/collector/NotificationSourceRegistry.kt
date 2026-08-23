package io.github.tuzfucius.personalrecorder.collector

import android.content.Context
import android.content.pm.PackageManager
import io.github.tuzfucius.personalrecorder.data.NotificationSourceDao

data class NotificationSourceMetadata(
    val label: String?,
    val hasLauncher: Boolean?,
)

fun interface NotificationSourceMetadataResolver {
    fun resolve(packageName: String): NotificationSourceMetadata
}

/** Records only package-level notification-source metadata, never notification content. */
class NotificationSourceRegistry(
    private val dao: NotificationSourceDao,
    private val metadataResolver: NotificationSourceMetadataResolver,
) {
    suspend fun observe(
        packageName: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val metadata = runCatching { metadataResolver.resolve(packageName) }
            .getOrDefault(NotificationSourceMetadata(label = null, hasLauncher = null))
        dao.observeNotificationSource(
            packageName = packageName,
            now = now,
            label = metadata.label,
            hasLauncher = metadata.hasLauncher,
        )
    }
}

class AndroidNotificationSourceMetadataResolver(
    context: Context,
) : NotificationSourceMetadataResolver {
    private val packageManager = context.applicationContext.packageManager

    override fun resolve(packageName: String): NotificationSourceMetadata = NotificationSourceMetadata(
        label = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(packageManager)
                .toString()
                .takeIf { it.isNotBlank() }
        }.getOrNull(),
        hasLauncher = runCatching {
            packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrNull(),
    )
}
