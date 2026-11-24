package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.plugin.localtasks.LocalTaskJson
import me.rerere.rikkahub.plugin.localtasks.LocalTaskLedgerState
import me.rerere.rikkahub.plugin.localtasks.LocalTaskPostUsage
import me.rerere.rikkahub.plugin.localtasks.LocalTaskRecord
import me.rerere.rikkahub.plugin.localtasks.LocalTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalTasksBackupCodecTest {
    @Test
    fun `snapshot rejects corrupt incomplete and negative ledgers`() {
        val malformed = listOf(
            "", " ", "{not json", """{"version":1}""",
            LocalTaskJson.task.encodeToString(LocalTaskLedgerState(modelCallsToday = -1)),
        )
        malformed.forEach { raw ->
            assertThrows(PluginDataBackupException::class.java) {
                LocalTasksBackupCodec.snapshot(null, raw, 100)
            }
        }
    }

    @Test
    fun `import validates original nested ledger before applying defaults`() {
        val valid = LocalTasksBackupCodec.encode(LocalTasksBackupPayload(ledger = LocalTaskLedgerState()))
        val document = LocalTaskJson.task.parseToJsonElement(valid).jsonObject
        val ledger = document.getValue("ledger").jsonObject
        ledger.keys.forEach { field ->
            val incomplete = JsonObject(document + ("ledger" to JsonObject(ledger - field))).toString()
            assertThrows(PluginDataBackupException::class.java) { LocalTasksBackupCodec.decode(incomplete) }
        }
    }

    @Test
    fun `normal version one backup round trip preserves budget and stops work`() {
        val original = LocalTaskLedgerState(
            dayKey = 1, modelCallsToday = 3,
            posts = mapOf("post_1" to LocalTaskPostUsage(modelCalls = 3)),
            tasks = mapOf("lt_1" to LocalTaskRecord(taskId = "lt_1", state = LocalTaskState.RUNNING)),
        )
        val snapshot = LocalTasksBackupCodec.snapshot(null, LocalTaskJson.task.encodeToString(original), 100)!!
        val restored = LocalTasksBackupCodec.decode(LocalTasksBackupCodec.encode(snapshot))
        assertEquals(snapshot, restored)
        assertEquals(3, restored.ledger!!.modelCallsToday)
        assertEquals(original.posts, restored.ledger.posts)
        assertEquals(LocalTaskState.INTERRUPTED, restored.ledger.tasks.getValue("lt_1").state)
        assertEquals(100L, restored.ledger.tasks.getValue("lt_1").finishedAt)
    }
}
