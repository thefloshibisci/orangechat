package me.rerere.rikkahub.data.reading

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReadStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 12)
    private fun record(day: LocalDate, seconds: Int) = ReadRecord(day.toString(), "book", "chat", "ai",
        "human_reading", createdAt = day.atStartOfDay(zone).toInstant().toEpochMilli(), seconds = seconds)

    @Test fun totalsUseLocalDaysAndNeverCountAssistantTime() {
        val rows = listOf(record(today, 60), record(today.minusDays(1), 120), record(today.minusDays(2), 30),
            record(today.minusDays(4), 40), ReadRecord("ai", "book", "chat", "ai", "assistant_reading",
                seconds = 0, characters = 27, author = "assistant"))
        val stats = readStats(rows, today, zone)
        assertEquals(60L, stats.todaySeconds)
        assertEquals(250L, stats.totalSeconds)
        assertEquals(3, stats.streak)
        assertEquals(1, stats.assistantFragments)
        assertEquals(27L, stats.assistantCharacters)
        assertEquals(4, stats.sittings.size)
    }

    @Test fun yesterdayKeepsStreakUntilTodayIsOverButAnOlderGapBreaksIt() {
        assertEquals(2, readStats(listOf(record(today.minusDays(1), 20), record(today.minusDays(2), 20)), today, zone).streak)
        assertEquals(0, readStats(listOf(record(today.minusDays(2), 20)), today, zone).streak)
        assertEquals(0, readStats(emptyList(), today, zone).streak)
    }
}
