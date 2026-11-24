/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.localtasks

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalTaskLedgerTest {
    private var now = 1_700_000_000_000L
    private val store = MemoryLocalTaskStore()
    private val ledger = LocalTaskLedger(store) { now }

    @Test
    fun `per post and daily caps are enforced atomically`() {
        val settings = testSettings(maxCallsPerPost = 2, dailyBudget = 3)

        assertTrue(ledger.tryChargeModelCall("post_1", settings))
        assertTrue(ledger.tryChargeModelCall("post_1", settings))
        assertFalse(ledger.tryChargeModelCall("post_1", settings))
        assertEquals(2, ledger.modelCallsFor("post_1"))

        assertTrue(ledger.tryChargeModelCall("post_2", settings))
        assertFalse(ledger.tryChargeModelCall("post_3", settings))
        assertFalse(ledger.hasDailyBudget(settings))
    }

    @Test
    fun `daily budget rolls over but post usage is kept`() {
        val settings = testSettings(maxCallsPerPost = 2, dailyBudget = 1)
        assertTrue(ledger.tryChargeModelCall("post_1", settings))
        assertFalse(ledger.hasDailyBudget(settings))

        now += LocalTasksApi.DAY_MILLIS

        assertTrue(ledger.hasDailyBudget(settings))
        assertEquals(1, ledger.modelCallsFor("post_1"))
        assertTrue(ledger.tryChargeModelCall("post_1", settings))
        assertFalse(ledger.tryChargeModelCall("post_1", settings))
    }

    @Test
    fun `reply and like counters are per assistant and post`() {
        val settings = testSettings(maxReplies = 2)

        assertTrue(ledger.canReply("post_1", ASSISTANT_A.id, settings))
        ledger.recordReply("post_1", ASSISTANT_A.id)
        assertEquals(1, ledger.replyCount("post_1", ASSISTANT_A.id))
        assertTrue(ledger.canReply("post_1", ASSISTANT_A.id, settings))
        ledger.recordReply("post_1", ASSISTANT_A.id)
        assertFalse(ledger.canReply("post_1", ASSISTANT_A.id, settings))
        assertTrue(ledger.canReply("post_1", ASSISTANT_B.id, settings))

        assertFalse(ledger.hasLiked("post_1", ASSISTANT_A.id))
        ledger.recordLike("post_1", ASSISTANT_A.id)
        assertTrue(ledger.hasLiked("post_1", ASSISTANT_A.id))
    }

    @Test
    fun `state is persisted across instances`() {
        ledger.createTask(
            LocalTaskRecord(taskId = "lt_persisted", postId = "post_1", createdAt = now),
        )
        ledger.recordReply("post_1", ASSISTANT_A.id)

        val reopened = LocalTaskLedger(store) { now }

        assertEquals("post_1", reopened.task("lt_persisted")?.postId)
        assertEquals(1, reopened.replyCount("post_1", ASSISTANT_A.id))
    }

    @Test
    fun `restart interrupts queued and running tasks`() {
        ledger.createTask(LocalTaskRecord(taskId = "lt_queued", postId = "post_1", createdAt = now))
        ledger.createTask(LocalTaskRecord(taskId = "lt_running", postId = "post_2", createdAt = now))
        ledger.updateTask("lt_running") { it.copy(state = LocalTaskState.RUNNING) }
        ledger.createTask(LocalTaskRecord(taskId = "lt_done", postId = "post_3", createdAt = now))
        ledger.updateTask("lt_done") { it.copy(state = LocalTaskState.COMPLETED) }

        val reopened = LocalTaskLedger(store) { now }

        assertEquals(2, reopened.markInterruptedOnStartup())
        assertEquals(LocalTaskState.INTERRUPTED, reopened.task("lt_queued")?.state)
        assertEquals(LocalTaskState.INTERRUPTED, reopened.task("lt_running")?.state)
        assertEquals("app_restart", reopened.task("lt_running")?.endReason)
        assertEquals(LocalTaskState.COMPLETED, reopened.task("lt_done")?.state)
        assertEquals(0, reopened.markInterruptedOnStartup())
    }

    @Test
    fun `active tasks are never pruned away`() {
        repeat(LocalTasksApi.MAX_TASK_RECORDS + 5) { index ->
            ledger.createTask(
                LocalTaskRecord(
                    taskId = "lt_finished_$index",
                    postId = "post_$index",
                    state = LocalTaskState.COMPLETED,
                    createdAt = now + index,
                ),
            )
        }
        ledger.createTask(
            LocalTaskRecord(taskId = "lt_active", postId = "post_active", createdAt = now + 1_000),
        )

        assertNotNull(ledger.task("lt_active"))
        assertTrue(ledger.snapshot().tasks.size <= LocalTasksApi.MAX_TASK_RECORDS + 1)
        assertNull(ledger.task("lt_finished_0"))
    }

    @Test
    fun `corrupt or unsupported persisted state remains intact and fails closed`() {
        listOf("{not json", "{}", """{"version":1}""", """{"version":999}""").forEach { raw ->
            store.write(LocalTasksApi.KEY_LEDGER, raw)
            assertThrows(IllegalStateException::class.java) { LocalTaskLedger(store) { now } }
            assertEquals(raw, store.raw(LocalTasksApi.KEY_LEDGER))
        }
    }

    @Test
    fun `missing persisted defaults at every nesting level fail closed`() {
        val state = LocalTaskLedgerState(
            posts = mapOf("post_1" to LocalTaskPostUsage()),
            tasks = mapOf("lt_saved" to LocalTaskRecord(
                taskId = "lt_saved",
                pendingReview = listOf(LocalTaskPendingActionDto(
                    id = "review_1", kind = "comment", assistantId = "assistant_1", assistantName = "Assistant",
                )),
            )),
        )
        val original = LocalTaskJson.task.parseToJsonElement(LocalTaskJson.task.encodeToString(state)).jsonObject
        fun assertRejected(document: JsonObject) {
            val raw = document.toString()
            store.write(LocalTasksApi.KEY_LEDGER, raw)
            assertThrows(IllegalStateException::class.java) { LocalTaskLedger(store) { now } }
            assertEquals(raw, store.raw(LocalTasksApi.KEY_LEDGER))
        }
        original.keys.forEach { key -> assertRejected(JsonObject(original - key)) }
        val posts = original.getValue("posts").jsonObject
        val usage = posts.getValue("post_1").jsonObject
        usage.keys.forEach { key ->
            assertRejected(JsonObject(original + ("posts" to JsonObject(posts + ("post_1" to JsonObject(usage - key))))))
        }
        val tasks = original.getValue("tasks").jsonObject
        val task = tasks.getValue("lt_saved").jsonObject
        task.keys.forEach { key ->
            assertRejected(JsonObject(original + ("tasks" to JsonObject(tasks + ("lt_saved" to JsonObject(task - key))))))
        }
        // The original version-1 writer omits nullable nulls; that remains a valid ledger.
        store.write(LocalTasksApi.KEY_LEDGER, original.toString())
        assertEquals(state, LocalTaskLedger(store) { now }.snapshot())
    }

    @Test
    fun `negative counters and inconsistent task identities fail closed`() {
        val invalid = listOf(
            LocalTaskLedgerState(dayKey = -2),
            LocalTaskLedgerState(modelCallsToday = -1),
            LocalTaskLedgerState(modelCallsToday = 1),
            LocalTaskLedgerState(posts = mapOf("post_1" to LocalTaskPostUsage(modelCalls = -1))),
            LocalTaskLedgerState(posts = mapOf("post_1" to LocalTaskPostUsage(lastTurnAt = -1))),
            LocalTaskLedgerState(posts = mapOf("post_1" to LocalTaskPostUsage(replies = mapOf("a" to -1)))),
            LocalTaskLedgerState(tasks = mapOf("wrong" to LocalTaskRecord(taskId = "lt_saved"))),
            LocalTaskLedgerState(tasks = mapOf("lt_saved" to LocalTaskRecord(taskId = "lt_saved", modelCalls = -1))),
            LocalTaskLedgerState(tasks = mapOf("lt_saved" to LocalTaskRecord(taskId = "lt_saved", finishedAt = -1))),
        )
        invalid.forEach { state ->
            val raw = LocalTaskJson.task.encodeToString(state)
            store.write(LocalTasksApi.KEY_LEDGER, raw)
            assertThrows(IllegalStateException::class.java) { LocalTaskLedger(store) { now } }
            assertEquals(raw, store.raw(LocalTasksApi.KEY_LEDGER))
        }
    }

    @Test
    fun `failed ledger write refuses reservation and all later operations`() {
        val storage = SwitchableStore()
        val guarded = LocalTaskLedger(storage) { now }
        val settings = testSettings(dailyBudget = 1)
        assertTrue(guarded.hasDailyBudget(settings))
        val before = storage.raw
        storage.failWrites = true

        assertThrows(IllegalStateException::class.java) { guarded.tryChargeModelCall("post_1", settings) }
        assertThrows(IllegalStateException::class.java) { guarded.hasDailyBudget(settings) }
        assertThrows(IllegalStateException::class.java) { guarded.createTask(LocalTaskRecord(taskId = "lt_x")) }
        assertEquals(before, storage.raw)
        storage.failWrites = false
        val reopened = LocalTaskLedger(storage) { now }
        assertEquals(0, reopened.snapshot().modelCallsToday)
    }

    @Test
    fun `confirmed reservations and idempotency keys survive recreation`() {
        val storage = SwitchableStore()
        val first = LocalTaskLedger(storage) { now }
        first.createTask(LocalTaskRecord(taskId = "lt_saved", clientKey = "same-request"))
        assertTrue(first.tryChargeModelCall("post_1", testSettings(dailyBudget = 1)))

        val reopened = LocalTaskLedger(storage) { now }
        assertEquals("lt_saved", reopened.findByClientKey("same-request")?.taskId)
        assertFalse(reopened.hasDailyBudget(testSettings(dailyBudget = 1)))
    }

    private class SwitchableStore : LocalTaskKeyValueStore {
        var raw: String? = null
        var failWrites = false
        override fun read(key: String): String? = raw
        override fun write(key: String, value: String) {
            if (failWrites) throw java.io.IOException("storage unavailable")
            raw = value
        }
    }
}
