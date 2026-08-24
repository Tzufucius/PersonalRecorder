package io.github.tuzfucius.personalrecorder.ui

import io.github.tuzfucius.personalrecorder.data.NotificationSourceEntity
import java.util.Locale

data class FilterLauncherApp(
    val packageName: String,
    val label: String?,
)

data class FilterAppItem(
    val packageName: String,
    val label: String,
    val observed: Boolean,
    val observedNotificationCount: Long,
    val lastSeenAt: Long?,
    val hasLauncher: Boolean?,
    val selected: Boolean,
)

fun buildFilterAppCatalog(
    observedSources: List<NotificationSourceEntity>,
    launcherApps: List<FilterLauncherApp>,
    selectedPackages: Set<String>,
    ownPackageName: String,
): List<FilterAppItem> {
    val sourcesByPackage = observedSources.associateBy { it.packageName }
    val launcherByPackage = launcherApps.associateBy { it.packageName }
    val packageNames = (sourcesByPackage.keys + launcherByPackage.keys + selectedPackages)
        .asSequence()
        .filterNot { it == ownPackageName }
        .distinct()

    return packageNames
        .map { packageName ->
            val source = sourcesByPackage[packageName]
            val launcher = launcherByPackage[packageName]
            FilterAppItem(
                packageName = packageName,
                label = source?.lastKnownLabel?.takeIf { it.isNotBlank() }
                    ?: launcher?.label?.takeIf { it.isNotBlank() }
                    ?: packageName,
                observed = source != null,
                observedNotificationCount = source?.observedNotificationCount ?: 0L,
                lastSeenAt = source?.lastSeenAt,
                hasLauncher = launcher?.let { true } ?: source?.lastKnownHasLauncher,
                selected = packageName in selectedPackages,
            )
        }
        .sortedWith(
            compareByDescending<FilterAppItem> { it.observed }
                .thenByDescending { it.lastSeenAt ?: Long.MIN_VALUE }
                .thenBy { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.packageName },
        )
        .toList()
}

fun filterAppItems(
    apps: List<FilterAppItem>,
    query: String,
): List<FilterAppItem> = apps.filter { app ->
    query.isBlank() || app.label.contains(query, ignoreCase = true) ||
        app.packageName.contains(query, ignoreCase = true)
}
