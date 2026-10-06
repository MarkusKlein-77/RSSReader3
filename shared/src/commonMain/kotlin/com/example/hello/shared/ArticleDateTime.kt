package com.example.hello.shared

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun formatPublishedAt(
    publishedAt: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val localDateTime = try {
        Instant.parse(publishedAt).toLocalDateTime(timeZone)
    } catch (_: IllegalArgumentException) {
        return publishedAt
    }

    val date = localDateTime.date
    val time = localDateTime.time
    return "${date.year.toString().padStart(4, '0')}-${date.monthNumber.toString().padStart(2, '0')}-" +
        "${date.dayOfMonth.toString().padStart(2, '0')} " +
        "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}:" +
        time.second.toString().padStart(2, '0')
}
