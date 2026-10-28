package me.rerere.rikkahub.data.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.buildProactiveWakeupTool
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProactiveWakeupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val setting = ProactiveMessageSetting(enabled = true, allowAiScheduledWakeup = true,
        activeStartHour = 8, activeEndHour = 23)
    private fun time(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    private val now = time("2026-10-28T19:00:00+08:00")
    private fun appointment(id: String = "chat-a", at: String = "2026-10-28T20:30:00+08:00") =
        ProactiveWakeup("role-a", id, time(at), "晚饭后聊聊")

    @Before fun reset() { ProactiveWakeupStore(context).clear() }

    @Test fun `old settings keep opt in disabled`() {
        val decoded = Json.decodeFromString<ProactiveMessageSetting>("""{"enabled":true}""")
        assertFalse(decoded.allowAiScheduledWakeup)
    }

    @Test fun `one appointment per conversation persists replacement and role isolation`() {
        val store = ProactiveWakeupStore(context)
        val first = appointment()
        val other = appointment("chat-b").copy(assistantId = "role-b")
        val replacement = appointment(at = "2026-10-28T22:00:00+08:00")
        store.put(first); store.put(other); store.put(replacement)
        val reopened = ProactiveWakeupStore(context)
        assertEquals(setOf(other, replacement), reopened.all().toSet())
        assertFalse(reopened.consume(first))
        assertEquals(2, reopened.all().size)
        assertTrue(reopened.cancel("chat-a"))
        assertEquals(listOf(other), reopened.all())
    }

    @Test fun `alarm and worker can consume a due appointment only once`() {
        val store = ProactiveWakeupStore(context)
        val pending = appointment()
        store.put(pending)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { ProactiveWakeupStore(context).consume(pending) } })
            assertEquals(1, results.count { it.get() })
            assertTrue(store.all().isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun `appointments override interval and wait until exact time`() {
        val late = appointment()
        val early = appointment("chat-b", "2026-10-28T19:01:00+08:00")
        assertEquals(early, nextProactiveWakeup(listOf(late, early), setting, now, zone))
        assertEquals(early.timeMillis, proactiveTriggerTime(early, now + 30_000, setting, now, zone))
        assertEquals(late.timeMillis, proactiveTriggerTime(late, now + 30_000, setting, now, zone))
    }

    @Test fun `quiet hours postpone appointment to active start and fallback remains unchanged`() {
        val pending = appointment(at = "2026-10-29T01:30:00+08:00")
        assertEquals(time("2026-10-29T08:00:00+08:00"),
            proactiveTriggerTime(pending, now + 60_000, setting, now, zone))
        assertEquals(now + 90 * 60_000L, proactiveTriggerTime(null, now + 90 * 60_000L, setting, now, zone))
        assertEquals(now + 60_000, proactiveTriggerTime(appointment(at = "2026-10-28T18:00:00+08:00"),
            now + 90 * 60_000L, setting, now, zone))
    }

    @Test fun `master switch and separate opt in both gate appointment routing`() {
        val pending = listOf(appointment())
        assertNull(nextProactiveWakeup(pending, setting.copy(enabled = false), now, zone))
        assertNull(nextProactiveWakeup(pending, setting.copy(allowAiScheduledWakeup = false), now, zone))
        assertNotNull(nextProactiveWakeup(pending, setting, now, zone))
    }

    @Test fun `execution waits until due and final recheck respects changed consent and quiet hours`() {
        val pending = appointment()
        assertFalse(canRunProactiveWakeup(pending, setting, pending.timeMillis - 1, zone))
        assertTrue(canRunProactiveWakeup(pending, setting, pending.timeMillis, zone))
        assertFalse(canRunProactiveWakeup(pending, setting.copy(enabled = false), pending.timeMillis, zone))
        assertFalse(canRunProactiveWakeup(pending, setting.copy(allowAiScheduledWakeup = false), pending.timeMillis, zone))
        val night = time("2026-10-28T23:01:00+08:00")
        assertFalse(canRunProactiveWakeup(pending, setting, night, zone))
        assertTrue(canRunProactiveWakeup(pending, setting, time("2026-10-29T08:00:00+08:00"), zone))
        assertTrue(canRunProactiveWakeup(null, setting.copy(allowAiScheduledWakeup = false), night, zone, manual = true))
        assertFalse(canRunProactiveWakeup(null, setting.copy(enabled = false), night, zone, manual = true))
    }

    @Test fun `consuming earliest conversation preserves and schedules remaining conversations`() {
        val store = ProactiveWakeupStore(context)
        val first = appointment("chat-a", "2026-10-28T20:00:00+08:00")
        val second = appointment("chat-b", "2026-10-28T20:01:00+08:00").copy(assistantId = "role-b")
        store.put(second); store.put(first)
        assertEquals(first, nextProactiveWakeup(store.all(), setting, now, zone))
        assertTrue(store.consume(first))
        assertEquals(second, nextProactiveWakeup(store.all(), setting, first.timeMillis, zone))
        assertEquals(second.timeMillis, proactiveTriggerTime(second, now, setting, first.timeMillis, zone))
        store.clear()
        assertNull(nextProactiveWakeup(ProactiveWakeupStore(context).all(), setting, now, zone))
    }

    @Test fun `DST gap appointment respects local active start`() {
        val pending = appointment().copy(timeMillis = time("2026-03-08T01:30:00-05:00"))
        val localSetting = setting.copy(activeStartHour = 3, activeEndHour = 23)
        assertEquals(time("2026-03-08T03:00:00-04:00"), proactiveTriggerTime(pending, now, localSetting,
            time("2026-03-07T20:00:00-05:00"), ZoneId.of("America/New_York")))
    }

    @Test fun `actual model tool schedules replaces and cancels durable appointment`() = runBlocking {
        val store = ProactiveWakeupStore(context)
        val tool = buildProactiveWakeupTool(now = { now }, zone = zone) { millis, reason ->
            if (millis == null) store.cancel("chat-a") else store.put(
                ProactiveWakeup("role-a", "chat-a", millis, reason))
            "saved"
        }
        for (hour in listOf("20", "21")) {
            tool.execute(buildJsonObject {
                put("action", "schedule"); put("time", "2026-10-28T$hour:30:00+08:00"); put("reason", "饭后聊聊")
            })
        }
        assertEquals(1, store.all().size)
        assertEquals(time("2026-10-28T21:30:00+08:00"), store.all().single().timeMillis)
        assertEquals("饭后聊聊", store.all().single().reason)
        tool.execute(buildJsonObject { put("action", "cancel") })
        assertTrue(store.all().isEmpty())
    }

    @Test fun `malformed past or timezone free tool input cannot mutate storage`() = runBlocking {
        var writes = 0
        val tool = buildProactiveWakeupTool(now = { now }, zone = zone) { _, _ -> writes++; "saved" }
        for (at in listOf("2026-10-28T18:00:00+08:00", "2026-10-28T20:30:00", "tomorrow", "")) {
            assertTrue(runCatching {
                tool.execute(buildJsonObject { put("action", "schedule"); put("time", at); put("reason", "chat") })
            }.isFailure)
        }
        assertTrue(runCatching {
            tool.execute(buildJsonObject { put("action", "schedule"); put("time", "2026-10-28T20:30:00+08:00") })
        }.isFailure)
        assertTrue(runCatching { tool.execute(buildJsonObject { put("action", "unknown") }) }.isFailure)
        assertEquals(0, writes)
    }
}
