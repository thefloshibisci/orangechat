package me.rerere.rikkahub.workflow

import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.trigger.TimeCronTriggerFamily
import me.rerere.rikkahub.workflow.ui.IntervalUnit
import me.rerere.rikkahub.workflow.ui.TriggerChoice
import me.rerere.rikkahub.workflow.ui.editorChoice
import me.rerere.rikkahub.workflow.ui.intervalTriggerSummary
import me.rerere.rikkahub.workflow.ui.toTrigger
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkflowScheduleTest {
    @Test fun `interval editor round trips without changing its duration`() {
        for ((amount, unit, expectedMs) in listOf(
            Triple("30", IntervalUnit.MINUTES, 1_800_000L),
            Triple("2", IntervalUnit.HOURS, 7_200_000L),
            Triple("3", IntervalUnit.DAYS, 259_200_000L),
        )) {
            val trigger = TriggerChoice.INTERVAL.toTrigger("08:00", "", TriggerSpec.Manual, amount, unit, "", "20") as TriggerSpec.TimeCron
            val parsed = parse(trigger) as WorkflowJson.ParseResult.Ok
            assertEquals(trigger, parsed.definition.trigger)
            assertEquals(TriggerChoice.INTERVAL, trigger.editorChoice())
            assertEquals(expectedMs, TimeCronTriggerFamily.derivePeriodMs(trigger))
            assertTrue(intervalTriggerSummary(trigger.cron)?.startsWith("每隔 $amount") == true)
        }
    }

    @Test fun `new interval drafts reject too short and overflowing values`() {
        for (amount in listOf("0", "14", "999999999999999999999999")) {
            assertTrue(runCatching {
                TriggerChoice.INTERVAL.toTrigger("08:00", "", TriggerSpec.Manual, amount, IntervalUnit.MINUTES, "", "20")
            }.isFailure)
        }
    }

    @Test fun `malformed tool intervals and timezones are rejected without overflow or silent hourly fallback`() {
        for (cron in listOf("@every 0m", "@every 1s", "@every 9223372036854775807d", "@every 999999999999999999999999m")) {
            assertTrue(cron, parse(TriggerSpec.TimeCron(cron = cron)) is WorkflowJson.ParseResult.Err)
        }
        assertTrue(parse(TriggerSpec.TimeCron(timeOfDay = "08:00", timezone = "invalid/zone")) is WorkflowJson.ParseResult.Err)
    }

    @Test fun `daily time retains wall clock across daylight saving boundary`() {
        val zone = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, zone)
        val spec = TriggerSpec.TimeCron(timeOfDay = "09:00")
        val next = TimeCronTriggerFamily.computeNextFireMs(spec, zone, before.toInstant().toEpochMilli())
        assertEquals(before.plusDays(1).toInstant().toEpochMilli(), next)
        assertEquals(23 * 60 * 60 * 1000L, next!! - before.toInstant().toEpochMilli())
        assertNull(TimeCronTriggerFamily.derivePeriodMs(spec))
    }

    @Test fun `weekday and calendar aliases calculate the next real schedule`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val friday = ZonedDateTime.of(2026, 10, 23, 10, 0, 0, 0, zone)
        val expectedMonday = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedMonday, TimeCronTriggerFamily.computeNextFireMs(TriggerSpec.TimeCron(timeOfDay = "09:00", daysOfWeek = listOf(1)), zone, friday.toInstant().toEpochMilli()))
        val nextDaily = TimeCronTriggerFamily.computeNextFireMs(TriggerSpec.TimeCron(cron = "@daily"), zone, friday.toInstant().toEpochMilli())
        assertEquals(friday.plusDays(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), nextDaily)
    }

    @Test fun `unsupported bluetooth drafts preserve storage compatibility without advertising creation`() {
        val spec = TriggerSpec.BluetoothDeviceConnected("AA:BB:CC:DD:EE:FF")
        assertNull(spec.editorChoice())
        assertEquals(spec, WorkflowJson.parseStored(WorkflowJson.encode(definition(spec)))?.trigger)
    }

    @Test fun `editing existing schedule preserves timezone weekday and legacy interval metadata`() {
        val weekly = TriggerSpec.TimeCron(cron = "0 9 * * 1", timezone = "America/New_York")
        assertEquals(weekly, TriggerChoice.CRON.toTrigger("08:00", "", weekly, "30", IntervalUnit.MINUTES, weekly.cron!!, "20"))
        val legacy = TriggerSpec.TimeCron(cron = "@every 2m", timezone = "Asia/Shanghai")
        assertEquals(legacy, TriggerChoice.INTERVAL.toTrigger("08:00", "", legacy, "2", IntervalUnit.MINUTES, "", "20"))
        val daily = TriggerSpec.TimeCron(timeOfDay = "09:00", daysOfWeek = listOf(1, 3), timezone = "Asia/Shanghai")
        assertEquals(daily, TriggerChoice.TIME.toTrigger("09:00", "", daily, "30", IntervalUnit.MINUTES, "", "20"))
    }

    @Test fun `invalid stored schedules never silently execute hourly`() {
        val zone = ZoneId.of("Asia/Shanghai")
        for (spec in listOf(
            TriggerSpec.TimeCron(cron = "unsupported"),
            TriggerSpec.TimeCron(cron = "@every 0m"),
            TriggerSpec.TimeCron(timeOfDay = "27:70"),
            TriggerSpec.TimeCron(cron = "0 0 30 2 *"),
        )) assertNull(TimeCronTriggerFamily.computeNextFireMs(spec, zone, 1_000_000L))
        assertNotEquals(TimeCronTriggerFamily.scheduleFingerprint(TriggerSpec.TimeCron(cron = "@every 30m")), TimeCronTriggerFamily.scheduleFingerprint(TriggerSpec.TimeCron(cron = "@every 1h")))
    }

    private fun definition(trigger: TriggerSpec) = WorkflowDefinition("schedule-test", "Schedule test", trigger = trigger, actions = listOf(WorkflowAction("test_action", buildJsonObject {})))
    private fun parse(trigger: TriggerSpec) = WorkflowJson.parse(WorkflowJson.encode(definition(trigger)), setOf("test_action"))
}
