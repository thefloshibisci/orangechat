package me.rerere.rikkahub.workflow

import kotlinx.serialization.json.*
import me.rerere.rikkahub.workflow.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowWorkspacePolicyTest {
    private fun action(id: String? = null) = WorkflowAction("workspace_shell", buildJsonObject {
        put("command", "python mail.py")
        id?.let { put("workspace_id", it) }
    })
    private fun definition(action: WorkflowAction) = WorkflowDefinition("postal", "邮局", trigger = TriggerSpec.Manual, actions = listOf(action))

    @Test fun newDefinitionsMayUseTheAssistantDefault() {
        val json = WorkflowJson.encode(definition(action()))
        val parsed = WorkflowJson.parse(json, setOf("workspace_shell")) as WorkflowJson.ParseResult.Ok
        assertEquals(action(), parsed.definition.actions.single())
        assertFalse(parsed.definition.actions.single().args.containsKey("workspace_id"))
    }

    @Test fun storedMissingTargetRemainsMissingAfterReadingAndEncoding() {
        val original = definition(action())
        val loaded = WorkflowJson.parseStored(WorkflowJson.encode(original))!!
        assertEquals(original.actions, loaded.actions)
        val roundTrip = WorkflowJson.parseStored(WorkflowJson.encode(loaded))!!
        assertFalse(roundTrip.actions.single().args.containsKey("workspace_id"))
    }

    @Test fun explicitRemoteAndOtherActionArgumentsArePreserved() {
        val remote = action("remote-id")
        val parsed = WorkflowJson.parse(WorkflowJson.encode(definition(remote)), setOf("workspace_shell")) as WorkflowJson.ParseResult.Ok
        assertEquals(remote, parsed.definition.actions.single())
        assertEquals(remote, WorkflowJson.parseStored(WorkflowJson.encode(definition(remote)))!!.actions.single())
        val noWorkspace = WorkflowAction("notify", buildJsonObject { put("text", "hi") })
        assertEquals(noWorkspace, WorkflowJson.parseStored(WorkflowJson.encode(definition(noWorkspace)))!!.actions.single())
    }

    @Test fun transferWorkflowStillRequiresBothEndpoints() {
        val transfer = WorkflowAction("workspace_transfer_file", buildJsonObject {
            put("source_workspace_id", "phone"); put("destination_workspace_id", "cloud")
            put("source_path", "/workspace/file"); put("destination_path", "/srv/chat/file")
        })
        val parsed = WorkflowJson.parse(WorkflowJson.encode(definition(transfer)), setOf(transfer.tool)) as WorkflowJson.ParseResult.Ok
        assertEquals(transfer, parsed.definition.actions.single())
        for (key in listOf("source_workspace_id", "destination_workspace_id")) {
            val missing = transfer.copy(args = JsonObject(transfer.args - key))
            assertEquals("workspace_target_required", (WorkflowJson.parse(WorkflowJson.encode(definition(missing)), setOf(transfer.tool)) as WorkflowJson.ParseResult.Err).error)
        }
    }
}
