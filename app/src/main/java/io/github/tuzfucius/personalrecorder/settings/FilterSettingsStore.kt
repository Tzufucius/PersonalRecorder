package io.github.tuzfucius.personalrecorder.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.filterDataStore by preferencesDataStore(name = "filter_settings")

class FilterSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    val state: Flow<FilterSettingsState> = appContext.filterDataStore.data
        .map { preferences ->
            FilterSettingsState.Ready(
                FilterSettings(
                    mode = preferences[MODE_KEY]
                        ?.let { value -> runCatching { FilterMode.valueOf(value) }.getOrNull() }
                        ?: FilterMode.ALL,
                    selectedPackages = preferences[PACKAGES_KEY].orEmpty()
                )
            ) as FilterSettingsState
        }
        .catch { emit(FilterSettingsState.Error(it)) }

    suspend fun readSettings(): Result<FilterSettings> = runCatching {
        when (val value = state.first()) {
            is FilterSettingsState.Ready -> value.settings
            is FilterSettingsState.Error -> throw value.cause
        }
    }

    suspend fun setMode(mode: FilterMode) {
        appContext.filterDataStore.edit { it[MODE_KEY] = mode.name }
    }

    suspend fun setSelectedPackages(packages: Set<String>) {
        appContext.filterDataStore.edit { it[PACKAGES_KEY] = packages }
    }

    suspend fun update(settings: FilterSettings) {
        appContext.filterDataStore.edit {
            it[MODE_KEY] = settings.mode.name
            it[PACKAGES_KEY] = settings.selectedPackages
        }
    }

    private companion object {
        val MODE_KEY = stringPreferencesKey("mode")
        val PACKAGES_KEY = stringSetPreferencesKey("selected_packages")
    }
}
