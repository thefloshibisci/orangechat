package me.rerere.rikkahub.ui.pages.stats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.UsageSummary
import me.rerere.rikkahub.data.db.dao.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class UsageStatsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T06:12:00Z"), ZoneId.of("Asia/Shanghai"))

    @Test fun `ranges start at device local midnight and include exactly seven or thirty calendar dates`() {
        fun midnight(day: String) = LocalDate.parse(day).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        assertEquals(midnight("2026-10-25"), UsageTimeRange.TODAY.window(clock).since)
        assertEquals(midnight("2026-10-19"), UsageTimeRange.WEEK.window(clock).since)
        assertEquals(midnight("2026-09-26"), UsageTimeRange.MONTH.window(clock).since)
        assertEquals(0L, UsageTimeRange.ALL.window(clock).since)
        assertEquals(clock.millis() + 1, UsageTimeRange.TODAY.window(clock).until)
        assertEquals("%Y-%m", UsageTimeRange.ALL.window(clock).format)
    }

    @Test fun `daylight saving boundaries use calendar arithmetic rather than twenty four hour subtraction`() {
        val dstClock = Clock.fixed(Instant.parse("2026-03-09T16:00:00Z"), ZoneId.of("America/New_York"))
        val start = UsageTimeRange.WEEK.window(dstClock).since
        val expected = LocalDate.parse("2026-03-03").atStartOfDay(dstClock.zone).toInstant().toEpochMilli()
        assertEquals(expected, start)
        val today = UsageTimeRange.TODAY.window(dstClock).since
        assertEquals(6 * 24 * 60 * 60 * 1000L - 60 * 60 * 1000L, today - start)
    }

    @Test fun `missing reports do not turn into zero consumption and real totals remain authoritative`() {
        assertEquals("未返回用量", reportedUsageLabel(2, 2, 0))
        assertEquals("已报告 ${usageNumber(11)} token", reportedUsageLabel(2, 1, 11))
        assertEquals("已报告 0 token", reportedUsageLabel(1, 0, 0))
        assertEquals("其他", usageSourceLabel("UNRECOGNIZED_FUTURE_PURPOSE"))
        assertEquals("对话压缩", usageSourceLabel("COMPRESSION"))
    }

    @Test fun `switching ranges cancels pending old query and only publishes new scope`() = runTest {
        val ranges = MutableStateFlow(UsageTimeRange.TODAY)
        val revisions = MutableStateFlow(1L)
        val states = mutableListOf<RequestUsageState>()
        var cancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestUsageStates(ranges, revisions, clock) { window ->
                if (window.since != 0L) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                summary(7)
            }.collect { states += it }
        }
        runCurrent()
        ranges.value = UsageTimeRange.ALL
        runCurrent()
        assertTrue(cancelled)
        assertEquals(UsageTimeRange.ALL, states.last().range)
        assertEquals(7L, states.last().summary?.totals?.requestCount)
        assertFalse(states.any { it.range == UsageTimeRange.TODAY && it.summary != null })
    }

    @Test fun `consecutive record invalidations cancel pending read and refresh final result`() = runTest {
        val revisions = MutableSharedFlow<Long>(replay = 1).apply { tryEmit(1) }
        val states = mutableListOf<RequestUsageState>()
        val started = CompletableDeferred<Unit>()
        var queryCount = 0
        var cancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestUsageStates(MutableStateFlow(UsageTimeRange.ALL), revisions, clock) {
                queryCount++
                if (queryCount == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
                summary(queryCount.toLong())
            }.collect { states += it }
        }
        started.await()
        revisions.emit(2)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(2L, states.last().summary?.totals?.requestCount)
        revisions.emit(3)
        runCurrent()
        assertEquals(3L, states.last().summary?.totals?.requestCount)
    }

    @Test fun `range refresh retains visible totals with their original scope until new data arrives`() = runTest {
        val ranges = MutableStateFlow(UsageTimeRange.TODAY)
        val next = CompletableDeferred<UsageSummary>()
        val states = mutableListOf<RequestUsageState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestUsageStates(ranges, MutableStateFlow(1L), clock) { window ->
                if (window.since == 0L) next.await() else summary(2)
            }.collect { states += it }
        }
        runCurrent()
        ranges.value = UsageTimeRange.ALL
        runCurrent()
        assertTrue(states.last().isLoading)
        assertEquals(UsageTimeRange.ALL, states.last().range)
        assertEquals(UsageTimeRange.TODAY, states.last().summaryRange)
        assertEquals(2L, states.last().summary?.totals?.requestCount)
        next.complete(summary(9))
        runCurrent()
        assertFalse(states.last().isLoading)
        assertEquals(UsageTimeRange.ALL, states.last().summaryRange)
        assertEquals(9L, states.last().summary?.totals?.requestCount)
    }

    @Test fun `read failures expose retryable state and later invalidation recovers`() = runTest {
        val revisions = MutableStateFlow(1L)
        val states = mutableListOf<RequestUsageState>()
        var first = true
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            requestUsageStates(MutableStateFlow(UsageTimeRange.ALL), revisions, clock) {
                if (first) { first = false; error("private database detail") }
                summary(1)
            }.collect { states += it }
        }
        runCurrent()
        assertTrue(states.last().error?.contains("重试") == true)
        assertFalse(states.last().error?.contains("private") == true)
        revisions.value = 2
        runCurrent()
        assertEquals(null, states.last().error)
        assertEquals(1L, states.last().summary?.totals?.requestCount)
    }

    private fun summary(requests: Long) = UsageSummary(UsageTotals(requestCount = requests), emptyList(), emptyList(), emptyList())
}
