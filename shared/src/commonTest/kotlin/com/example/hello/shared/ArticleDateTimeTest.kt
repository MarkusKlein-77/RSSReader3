package com.example.hello.shared

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleDateTimeTest {
    @Test
    fun formatsInstantInViewerTimeZone() {
        assertEquals(
            "2024-01-01 02:30:00",
            formatPublishedAt("2024-01-01T00:30:00Z", TimeZone.of("UTC+02:00")),
        )
    }

    @Test
    fun convertsTimestampWithExplicitOffset() {
        assertEquals(
            "2024-01-01 07:30:00",
            formatPublishedAt("2024-01-01T00:30:00-05:00", TimeZone.of("UTC+02:00")),
        )
    }

    @Test
    fun preservesUnparseableTimestamp() {
        assertEquals("unknown", formatPublishedAt("unknown", TimeZone.UTC))
    }
}
