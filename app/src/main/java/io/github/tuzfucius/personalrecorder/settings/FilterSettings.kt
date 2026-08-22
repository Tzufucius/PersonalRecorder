package io.github.tuzfucius.personalrecorder.settings

enum class FilterMode {
    ALL,
    WHITELIST,
    BLACKLIST
}

data class FilterSettings(
    val mode: FilterMode = FilterMode.ALL,
    val selectedPackages: Set<String> = emptySet()
)

sealed interface FilterSettingsState {
    data class Ready(val settings: FilterSettings) : FilterSettingsState
    data class Error(val cause: Throwable) : FilterSettingsState
}
