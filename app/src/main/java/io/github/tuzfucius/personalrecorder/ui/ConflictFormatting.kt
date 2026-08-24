package io.github.tuzfucius.personalrecorder.ui

private val conflictCountPattern = Regex("\\d+")

/** Reads the language-independent count embedded in legacy conflict summaries. */
internal fun extractConflictCount(summary: String): Int? =
    conflictCountPattern.find(summary)?.value?.toIntOrNull()
