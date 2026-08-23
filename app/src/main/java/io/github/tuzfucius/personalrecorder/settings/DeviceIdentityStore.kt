package io.github.tuzfucius.personalrecorder.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.deviceIdentityDataStore by preferencesDataStore(name = "device_identity")

class DeviceIdentityStore(context: Context) {
    private val appContext = context.applicationContext

    suspend fun getOrCreateId(): String {
        val existing = appContext.deviceIdentityDataStore.data.first()[DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        appContext.deviceIdentityDataStore.edit { preferences ->
            if (preferences[DEVICE_ID].isNullOrBlank()) preferences[DEVICE_ID] = generated
        }
        return appContext.deviceIdentityDataStore.data.first()[DEVICE_ID] ?: generated
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_instance_id")
    }
}
