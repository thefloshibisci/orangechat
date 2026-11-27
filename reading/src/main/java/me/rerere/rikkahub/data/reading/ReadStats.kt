package me.rerere.rikkahub.data.reading

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReadStats(
    val todaySeconds: Long,
    val totalSeconds: Long,
    val streak: Int,
    val dailySeconds: Map<LocalDate, Long>,
    val sittings: List<ReadRecord>,
    val assistantFragments: Int,
    val assistantCharacters: Long,
)

fun readStats(records: List<ReadRecord>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): ReadStats {
    val sittings = records.filter { it.kind == "human_reading" && it.seconds > 0 }
    val daily = sittings.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .mapValues { (_, rows) -> rows.sumOf { it.seconds.toLong() } }
    var cursor = if ((daily[today] ?: 0) > 0) today else today.minusDays(1)
    var streak = 0
    while ((daily[cursor] ?: 0) > 0) { streak++; cursor = cursor.minusDays(1) }
    val receipts = records.filter { it.kind == "assistant_reading" }
    return ReadStats(daily[today] ?: 0, daily.values.sum(), streak, daily, sittings,
        receipts.size, receipts.sumOf { it.characters.toLong() })
}
