package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ProactivePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val setting = ProactiveMessageSetting()
    private fun time(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `overnight active range includes midnight but excludes end`() {
        assertTrue(isProactiveActiveTime(setting, time("2026-09-19T16:30:00Z"), zone))
        assertFalse(isProactiveActiveTime(setting, time("2026-09-19T17:00:00Z"), zone))
        assertTrue(isProactiveActiveTime(setting, time("2026-09-19T00:00:00Z"), zone))
    }

    @Test fun `quiet time advances to local morning exactly`() {
        assertEquals(time("2026-09-20T00:00:00Z"), nextProactiveActiveTime(
            setting, time("2026-09-19T18:20:34Z"), zone,
        ))
    }

    @Test fun `same day active time preserves exact timestamp`() {
        val now = time("2026-09-19T04:25:34Z")
        assertEquals(now, nextProactiveActiveTime(setting, now, zone))
    }

    @Test fun `daytime range advances evening to next day`() {
        assertEquals(time("2026-09-20T01:00:00Z"), nextProactiveActiveTime(
            setting.copy(activeStartHour = 9, activeEndHour = 17), time("2026-09-19T12:00:00Z"), zone,
        ))
    }

    @Test fun `disabled and equal endpoints are all day`() {
        val now = time("2026-09-19T20:00:00Z")
        assertEquals(now, nextProactiveActiveTime(setting.copy(activeHoursEnabled = false), now, zone))
        assertEquals(now, nextProactiveActiveTime(setting.copy(activeStartHour = 8, activeEndHour = 8), now, zone))
    }

    @Test fun `tool tier has exact boundary and configurable endpoints`() {
        assertTrue(useFullProactiveTools(setting, 19))
        assertFalse(useFullProactiveTools(setting, 20))
        assertFalse(useFullProactiveTools(setting.copy(fullToolChancePercent = 0), 0))
        assertTrue(useFullProactiveTools(setting.copy(fullToolChancePercent = 100), 99))
    }

    @Test fun `default migration does not enable timeline access`() {
        val old = kotlinx.serialization.json.Json.decodeFromString<ProactiveMessageSetting>("{\"enabled\":true}")
        assertFalse(old.includeAppTimeline)
        assertFalse(old.allowProactiveAppUsage)
        assertEquals(20, old.fullToolChancePercent)
    }

    @Test fun `background execution cannot bypass configured approval`() {
        assertFalse(canExecuteProactiveTool(needsApproval = true, forceConfirm = false, autoApprove = false))
        assertFalse(canExecuteProactiveTool(needsApproval = false, forceConfirm = true, autoApprove = false))
        assertTrue(canExecuteProactiveTool(needsApproval = false, forceConfirm = false, autoApprove = false))
        assertTrue(canExecuteProactiveTool(needsApproval = true, forceConfirm = true, autoApprove = true))
    }
}
