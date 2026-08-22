package io.github.tuzfucius.personalrecorder.statistics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tuzfucius.personalrecorder.data.AppDatabase
import io.github.tuzfucius.personalrecorder.settings.FilterSettingsState
import io.github.tuzfucius.personalrecorder.settings.FilterSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class StatisticsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).eventDao()
    private val filterStore = FilterSettingsStore(application)
    private val calculator = StatisticsCalculator()
    private val selectedRange = MutableStateFlow(StatisticsRange.TODAY)
    private val selectedHour = MutableStateFlow<Int?>(null)

    private val rows = selectedRange.flatMapLatest { range ->
        val bounds = calculator.bounds(range)
        dao.getStatisticsEvents(bounds.startMillis, bounds.endMillis, application.packageName)
    }

    val uiState: StateFlow<StatisticsUiState> = combine(
        selectedRange,
        selectedHour,
        filterStore.state,
        rows
    ) { range, hour, settingsState, eventRows ->
        if (settingsState is FilterSettingsState.Error) {
            return@combine StatisticsUiState(
                range = range,
                selectedHour = hour,
                errorMessage = "筛选配置读取失败，无法生成统计"
            )
        }
        val settings = (settingsState as FilterSettingsState.Ready).settings
        val state = calculator.calculate(eventRows, range, application.packageName, settings)
        calculator.withSelectedHour(state, hour, eventRows, application.packageName, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState(isLoading = true))

    fun selectRange(range: StatisticsRange) {
        selectedHour.value = null
        selectedRange.value = range
    }

    fun selectHour(hour: Int?) {
        selectedHour.value = hour
    }
}
