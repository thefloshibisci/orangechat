package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class WorkspaceToolPresentationTest {
    @Test fun machineHeaderDoesNotHideShellPayloadOrEditDiff() {
        val machine = "腾讯云 · ubuntu@VM-0-4-ubuntu:22"
        val header = UIMessagePart.Text(machine).apply { metadata = buildJsonObject { put("machine", machine) } }
        val payload = UIMessagePart.Text("""{"stdout":"mail delivered","exitCode":0}""").apply {
            metadata = buildJsonObject { put("diff", "-old\n+new"); put("machine", machine) }
        }
        val output = listOf(header, payload)
        assertEquals(machine, workspaceToolMachine(output))
        assertEquals("mail delivered", workspaceToolContent(output)!!.jsonObject["stdout"]!!.jsonPrimitive.content)
        assertEquals("-old\n+new", workspaceToolDiff(output))
    }

    @Test fun legacyJsonAndErrorsContinueToRender() {
        assertEquals("text", workspaceToolContent(listOf(UIMessagePart.Text("""{"text":"text"}""")))!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("offline", workspaceToolContent(listOf(UIMessagePart.Text("""{"error":"offline"}""")))!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertNull(workspaceToolContent(listOf(UIMessagePart.Image("synthetic"), UIMessagePart.Text("machine"))))
    }

    @Test fun labeledFailureKeepsMachineButCannotOfferAnEditedFile() {
        val header = UIMessagePart.Text("腾讯云 · ubuntu@host:22").apply {
            metadata = buildJsonObject { put("machine", text); put("workspace_id", "cloud") }
        }
        for (failure in listOf("""{"error":"offline"}""", """{"ok":false}""", """{"isError":true}""")) {
            val output = listOf(header, UIMessagePart.Text(failure))
            assertEquals(header.text, workspaceToolMachine(output))
            assertTrue(workspaceToolFailed(output))
        }
        assertFalse(workspaceToolFailed(listOf(header, UIMessagePart.Text("""{"path":"/srv/chat/done.txt"}"""))))
    }
}
