package io.github.tuzfucius.personalrecorder.background

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backgroundSettingsDataStore by preferencesDataStore(name = "background_settings")

class BackgroundSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val statusNotificationEnabled: Flow<Boolean> = appContext.backgroundSettingsDataStore.data
        .map { it[STATUS_NOTIFICATION_ENABLED] ?: false }

    suspend fun setStatusNotificationEnabled(enabled: Boolean) {
        appContext.backgroundSettingsDataStore.edit { it[STATUS_NOTIFICATION_ENABLED] = enabled }
    }

    private companion object {
        val STATUS_NOTIFICATION_ENABLED = booleanPreferencesKey("status_notification_enabled")
    }
}
