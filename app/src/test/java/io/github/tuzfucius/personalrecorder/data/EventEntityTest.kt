package io.github.tuzfucius.personalrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EventEntityTest {
    @Test
    fun stringListConverterPreservesLines() {
        val converter = StringListConverter()
        val lines = listOf("第一行", "第二行")

        assertEquals(lines, converter.toStringList(converter.fromStringList(lines)))
    }

    @Test
    fun emptyStringListConvertsToEmptyList() {
        val converter = StringListConverter()

        assertEquals(emptyList<String>(), converter.toStringList(converter.fromStringList(emptyList())))
    }
}
