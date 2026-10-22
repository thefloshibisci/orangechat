package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import org.junit.Assert.*
import org.junit.Test

class GenerationToolHistoryRecoveryTest {
    private fun call(id: String, approval: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(id, "external-action", "{}", approvalState = approval)

    private fun message(vararg calls: UIMessagePart.Tool) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("keep trace")) + calls)

    private fun output(call: UIMessagePart.Tool) = (call.output.single() as UIMessagePart.Text).text

    @Test
    fun `interrupted auto batch preserves known result marks started unknown and cancels unstarted leftovers`() {
        val receipt = call("finished").copy(output = listOf(UIMessagePart.Text("real receipt")))
            .withExecutionCheckpoint("completed")
        val original = message(receipt, call("uncertain").withExecutionCheckpoint("started"), call("unstarted"))
        val normalized = listOf(original).recoverToolHistoryForRequest(Json).single()
        val tools = normalized.getTools()
        assertEquals(original.id, normalized.id)
        assertEquals(original.parts.first(), normalized.parts.first())
        assertEquals(receipt, tools[0])
        assertEquals("unknown", tools[1].executionCheckpointState)
        assertEquals("cancelled", tools[2].executionCheckpointState)
        assertEquals("unstarted", Json.parseToJsonElement(output(tools[2])).jsonObject
            .getValue("tool_call_id").jsonPrimitive.content)
        assertTrue(tools.all { it.isExecuted })
        // Both old filters retain this now-complete message, instead of deleting its real receipt.
        assertFalse(tools.any { !it.isExecuted } && tools.none { it.approvalState.canResumeToolExecution() })
    }

    @Test
    fun `active mixed approval batch retains automatic pending and explicit decisions`() {
        for (decision in listOf(
            ToolApprovalState.Approved,
            ToolApprovalState.Denied("keep refusal"),
            ToolApprovalState.Answered("keep answer"),
        )) {
            val completed = call("finished").copy(output = listOf(UIMessagePart.Text("receipt")))
            val original = message(
                completed,
                call("uncertain").withExecutionCheckpoint("started"),
                call("automatic"),
                call("pending", ToolApprovalState.Pending),
                call("decision", decision),
            )
            val tools = listOf(original).recoverToolHistoryForRequest(Json).single().getTools()
            assertEquals(completed, tools[0])
            assertEquals("unknown", tools[1].executionCheckpointState)
            assertEquals(original.getTools().drop(2), tools.drop(2))
        }
    }

    @Test
    fun `historical batches finish human answers and refusals without executing approved calls`() {
        val original = message(
            call("uncertain").withExecutionCheckpoint("started"),
            call("approved", ToolApprovalState.Approved),
            call("denied", ToolApprovalState.Denied("keep reason")),
            call("answered", ToolApprovalState.Answered("keep answer")),
        )
        val user = UIMessage.user("new turn")
        val normalized = listOf(original, user).recoverToolHistoryForRequest(Json)
        val tools = normalized.first().getTools()
        assertEquals(user, normalized.last())
        assertEquals(listOf("unknown", "cancelled", "denied", "answered"), tools.map { it.executionCheckpointState })
        assertEquals(original.getTools().map { it.approvalState }, tools.map { it.approvalState })
        assertTrue(output(tools[2]).contains("keep reason"))
        assertEquals("keep answer", output(tools[3]))
    }

    @Test
    fun `background new request treats even last explicitly approved batch as historical`() {
        val original = message(
            call("uncertain").withExecutionCheckpoint("started"),
            call("approved", ToolApprovalState.Approved),
            call("auto"),
        )
        val normalized = listOf(original).recoverToolHistoryForRequest(Json, allowLastMessageResume = false)
        assertEquals(listOf("unknown", "cancelled", "cancelled"), normalized.single().getTools().map { it.executionCheckpointState })
        assertTrue(normalized.single().getTools().all { it.isExecuted })
        assertFalse(original.getTools().any { it.isExecuted })
    }

    @Test
    fun `entirely unstarted legacy batch remains untouched for existing invalid message policy`() {
        for (approval in listOf(ToolApprovalState.Auto, ToolApprovalState.Pending, ToolApprovalState.Approved)) {
            val original = message(call("unstarted", approval))
            assertSame(original, listOf(original).recoverToolHistoryForRequest(Json).single())
            assertSame(original, listOf(original).recoverToolHistoryForRequest(Json, false).single())
        }
    }

    @Test
    fun `legacy result without marker is still evidence and cannot be discarded with its unfinished sibling`() {
        val result = call("finished").copy(output = listOf(UIMessagePart.Text("old saved receipt")))
        val original = message(result, call("unstarted"))
        val normalized = listOf(original, UIMessage.user("next")).recoverToolHistoryForRequest(Json).first()
        assertEquals(result, normalized.getTools().first())
        assertEquals("cancelled", normalized.getTools().last().executionCheckpointState)
    }

    @Test
    fun `only an interrupted approved marker does not authorize untouched sibling on restart`() {
        val original = message(
            call("uncertain", ToolApprovalState.Approved).withExecutionCheckpoint("started"),
            call("unstarted"),
        )
        val normalized = listOf(original).recoverToolHistoryForRequest(Json).single()
        assertEquals(listOf("unknown", "cancelled"), normalized.getTools().map { it.executionCheckpointState })
        assertEquals(listOf(normalized), listOf(normalized).recoverToolHistoryForRequest(Json))
    }
}
