package me.rerere.rikkahub.data.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProactiveWakeupHistoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun store(process: String = "current") = ProactiveWakeupStore(context, process)
    private fun appointment(chat: String = "chat-a") = ProactiveWakeup("role-a", chat, 123456789L, "晚饭后聊聊")
    private fun record(token: String) = store().snapshot().single { it.appointment.token == token }

    @Before fun reset() {
        context.getSharedPreferences("proactive_wakeups", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `legacy pending records remain unchanged and executable`() {
        val old = appointment()
        context.getSharedPreferences("proactive_wakeups", Context.MODE_PRIVATE).edit()
            .putString("pending", Json.encodeToString(listOf(old))).commit()
        assertEquals(listOf(old), store().all())
        assertEquals(ProactiveWakeupStatus.Pending, record(old.token).status)
        assertTrue(store().consume(old))
        assertEquals(ProactiveWakeupStatus.Running, record(old.token).status)
    }

    @Test fun `claimed appointment persists final send and never becomes pending again`() {
        val pending = appointment()
        store().put(pending)
        assertTrue(store().consume(pending))
        assertTrue(store().all().isEmpty())
        assertEquals(ProactiveWakeupStatus.Running, record(pending.token).status)
        store().finish(pending, "sent")
        assertEquals(ProactiveWakeupStatus.Sent, record(pending.token).status)
        store().finish(pending, "failed")
        assertEquals(ProactiveWakeupStatus.Sent, record(pending.token).status)
        assertFalse(store().consume(pending))
        assertTrue(store().all().isEmpty())
    }

    @Test fun `failure and interruption remain visible without repeating a paid generation`() {
        listOf("failed", "cancelled", "no_model", "pass", "wait", "stop", "user_returned",
            "appointment_conversation_removed", "follow_up_limit", "disabled_or_quiet_hours").forEach { outcome ->
            val pending = appointment()
            store().put(pending)
            store().consume(pending)
            store().finish(pending, outcome)
            val result = record(pending.token)
            assertEquals(outcome, ProactiveWakeupStatus.Failed, result.status)
            assertTrue(result.outcome.isNotBlank())
            assertTrue(store().all().isEmpty())
        }
    }

    @Test fun `replacement records old agreement and stale UI cannot cancel new agreement`() {
        val old = appointment()
        val replacement = appointment().copy(timeMillis = old.timeMillis + 60_000)
        store().put(old)
        store().put(replacement)
        assertEquals(ProactiveWakeupStatus.Cancelled, record(old.token).status)
        assertEquals("已由新约定替换", record(old.token).outcome)
        assertFalse(store().cancel(old.conversationId, old.token))
        assertEquals(listOf(replacement), store().all())
        assertTrue(store().cancel(replacement.conversationId, replacement.token))
        assertEquals(ProactiveWakeupStatus.Cancelled, record(replacement.token).status)
        assertTrue(store().all().isEmpty())
    }

    @Test fun `finishing old run never removes a newer agreement or another role`() {
        val running = appointment()
        val replacement = appointment()
        val other = appointment("chat-b").copy(assistantId = "role-b")
        store().put(running)
        store().consume(running)
        store().put(replacement)
        store().put(other)
        store().finish(running, "sent")
        assertEquals(setOf(replacement, other), store().all().toSet())
        assertEquals(ProactiveWakeupStatus.Sent, record(running.token).status)
        assertFalse(store().cancel(running.conversationId, running.token))
    }

    @Test fun `terminal history keeps last ten without dropping pending or running`() {
        val running = appointment("running")
        val pending = appointment("pending")
        store().put(running)
        store().consume(running)
        store().put(pending)
        val completed = (1..18).map {
            appointment("chat-$it").also { value ->
                store().put(value); store().consume(value); store().finish(value, "sent")
            }
        }
        val snapshot = store().snapshot()
        assertEquals(12, snapshot.size)
        assertEquals(ProactiveWakeupStatus.Running, record(running.token).status)
        assertEquals(listOf(pending), store().all())
        assertEquals(completed.takeLast(10).map { it.token }.toSet(),
            snapshot.filter { it.status == ProactiveWakeupStatus.Sent }.map { it.appointment.token }.toSet())
    }

    @Test fun `new process recovers interrupted run but preserves pending and confirmed sends`() {
        val interrupted = appointment("interrupted")
        val pending = appointment("pending")
        val sent = appointment("sent")
        val original = store("old-process")
        original.put(interrupted); original.consume(interrupted)
        original.put(sent); original.consume(sent); original.finish(sent, "sent")
        original.put(pending)
        assertEquals(ProactiveWakeupStatus.Running,
            original.snapshot().single { it.appointment == interrupted }.status)
        val reopened = store().snapshot()
        assertEquals(ProactiveWakeupStatus.Failed, reopened.single { it.appointment == interrupted }.status)
        assertTrue(reopened.single { it.appointment == interrupted }.outcome.contains("未确认"))
        assertEquals(ProactiveWakeupStatus.Sent, reopened.single { it.appointment == sent }.status)
        assertEquals(listOf(pending), store().all())
        assertEquals(reopened, store().snapshot())
    }

    @Test fun `disabling opt in records cancellations without deleting terminal history`() {
        val sent = appointment("sent")
        val pending = appointment("pending")
        store().put(sent); store().consume(sent); store().finish(sent, "sent")
        store().put(pending)
        store().clear()
        assertTrue(store().all().isEmpty())
        assertEquals(ProactiveWakeupStatus.Sent, record(sent.token).status)
        assertEquals(ProactiveWakeupStatus.Cancelled, record(pending.token).status)
    }

    @Test fun `concurrent claims create exactly one running history record`() {
        val pending = appointment()
        store().put(pending)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { store().consume(pending) } })
            assertEquals(1, results.count { it.get() })
            assertEquals(1, store().snapshot().size)
            assertEquals(ProactiveWakeupStatus.Running, record(pending.token).status)
        } finally { pool.shutdownNow() }
    }
}
