package io.github.tuzfucius.personalrecorder.ui

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun formatLocalizedDate(context: Context, date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(context.resources.configuration.locales[0])
        .format(date)
