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
    private val selection = MutableStateFlow(StatisticsSelection())
    private val detailsExpanded = MutableStateFlow(false)
    private val otherAppsExpanded = MutableStateFlow(false)

    private val rows = selectedRange.flatMapLatest { range ->
        val bounds = calculator.bounds(range)
        dao.getStatisticsEvents(bounds.startMillis, bounds.endMillis, application.packageName)
    }

    private val calculatedState = combine(
        selectedRange,
        selection,
        filterStore.state,
        rows,
    ) { range, currentSelection, settingsState, eventRows ->
        if (settingsState is FilterSettingsState.Error) {
            return@combine StatisticsUiState(
                range = range,
                selection = currentSelection,
                selectedHour = currentSelection.hour,
                errorMessage = "筛选配置读取失败，无法生成统计"
            )
        }
        val settings = (settingsState as FilterSettingsState.Ready).settings
        calculator.calculate(eventRows, range, application.packageName, settings, currentSelection)
    }

    val uiState: StateFlow<StatisticsUiState> = combine(
        calculatedState,
        detailsExpanded,
        otherAppsExpanded,
    ) { state, detailsOpen, otherOpen ->
        state.copy(isDetailsExpanded = detailsOpen, isOtherAppsExpanded = otherOpen)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState(isLoading = true))

    fun selectRange(range: StatisticsRange) {
        selection.value = selection.value.withoutTimeFilters()
        selectedRange.value = range
    }

    fun selectHour(hour: Int?) {
        selection.value = selection.value.copy(hour = if (selection.value.hour == hour) null else hour)
    }

    fun selectDate(date: java.time.LocalDate?) {
        selection.value = selection.value.copy(date = if (selection.value.date == date) null else date)
    }

    fun selectApp(packageName: String?) {
        selection.value = selection.value.copy(
            app = if (selection.value.app == packageName) null else packageName
        )
    }

    fun toggleDetails() {
        detailsExpanded.value = !detailsExpanded.value
    }

    fun toggleOtherApps() {
        otherAppsExpanded.value = !otherAppsExpanded.value
    }

    fun clearSelection() {
        selection.value = StatisticsSelection()
    }
}
