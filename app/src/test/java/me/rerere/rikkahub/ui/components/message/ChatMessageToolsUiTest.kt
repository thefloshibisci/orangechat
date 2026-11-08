package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChatMessageToolsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedWorkspaceWritesDoNotShowFileDownloadChips() {
        val header = UIMessagePart.Text("腾讯云 · ubuntu@host:22").apply {
            metadata = kotlinx.serialization.json.buildJsonObject {
                put("machine", kotlinx.serialization.json.JsonPrimitive(text))
                put("workspace_id", kotlinx.serialization.json.JsonPrimitive("cloud"))
                put("workspace_root", kotlinx.serialization.json.JsonPrimitive("/srv/chat"))
            }
        }
        val calls = listOf("workspace_write_file", "workspace_edit_file", "workspace_transfer_file").map { name ->
            UIMessagePart.Tool(name, name,
                """{"path":"/srv/chat/failed.txt","destination_path":"/srv/chat/failed.txt"}""",
                listOf(header, UIMessagePart.Text("""{"ok":false,"isError":true,"error":"offline"}""")))
        }
        compose.setContent { MaterialTheme { EditedFilesList(calls, assistant = null) } }
        compose.onNodeWithText("failed.txt").assertDoesNotExist()
    }

    @Test fun remoteWorkspaceFailureIsVisibleInToolCard() {
        val error = "腾讯云 · ubuntu@host:22\nSSH offline; no local fallback"
        renderHistories(listOf(UIMessagePart.Tool("remote-failure", "workspace_shell", """{"workspace_id":"cloud","command":"python mail.py"}""",
            listOf(UIMessagePart.Text(kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.JsonPrimitive(error))
            }.toString())))))
        compose.onNodeWithText(error).assertIsDisplayed()
    }

    @Test fun labeledWorkspaceFailureDisplaysMachineHeader() {
        val machine = "腾讯云 · ubuntu@host:22"
        val header = UIMessagePart.Text(machine).apply {
            metadata = kotlinx.serialization.json.buildJsonObject {
                put("machine", kotlinx.serialization.json.JsonPrimitive(machine))
            }
        }
        renderHistories(listOf(UIMessagePart.Tool("labeled-failure", "workspace_shell", "{}",
            listOf(header, UIMessagePart.Text("""{"ok":false,"error":"SSH offline"}""")))))
        compose.onNodeWithText(machine).assertIsDisplayed()
        compose.onNodeWithText("SSH offline").assertIsDisplayed()
    }

    // Exercise both recomposition and replacing the displayed assistant's history.
    private fun renderHistories(histories: List<UIMessagePart.Tool>) {
        val current = mutableStateOf(histories.first())
        val assistant = mutableStateOf(0)
        compose.setContent {
            KoinApplication(application = { modules(module { single { AppEventBus() } }) }) {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    MaterialTheme {
                        key(assistant.value) {
                            ChainOfThought(steps = listOf(current.value)) { ChatMessageToolStep(it) }
                        }
                    }
                }
            }
        }
        histories.forEachIndexed { index, tool ->
            compose.runOnIdle { current.value = tool }
            compose.waitForIdle()
            compose.runOnIdle { assistant.value = index + 1 }
            compose.waitForIdle()
        }
    }

    @Test fun scalarAndWrongShapeFileFieldsDoNotCrashOnHistorySwitch() {
        val invalid = listOf("null", "false", "42", "\"text\"", "[]", "{}")
        val histories = invalid.flatMapIndexed { index, value ->
            listOf("write_files", "zip_files").flatMap { name ->
                listOf(
                    UIMessagePart.Tool("$name-$index-root", name, value, listOf(UIMessagePart.Text(value))),
                    UIMessagePart.Tool(
                        "$name-$index-fields", name,
                        """{"files":$value,"edits":$value}""",
                        listOf(UIMessagePart.Text("""{"total_files":$value,"files":$value,"files_content":$value}""")),
                    ),
                )
            }
        }
        renderHistories(histories)
    }

    @Test fun scalarSearchResultsAndMixedEntriesDoNotCrashOnHistorySwitch() {
        val results = listOf("null", "true", "42", "\"text\"", "[]", "{}",
            """{"items":false}""", """{"items":"bad"}""", """{"items":[null,42,{},[]]}""")
        renderHistories(results.mapIndexed { index, result ->
            UIMessagePart.Tool("search-$index", "search_web", "{}", listOf(UIMessagePart.Text(result)))
        })
    }

    @Test fun malformedAnsweredQuestionStillRendersQuestionAndRawAnswer() {
        renderHistories(listOf(UIMessagePart.Tool(
            "question", "ask_user",
            """{"questions":[42,{"id":"q","question":"Choose a color","options":false}]}""",
            approvalState = ToolApprovalState.Answered("\"blue\""),
        )))
        // The question appears in both the card title and expanded body.
        compose.onAllNodesWithText("Choose a color").assertCountEquals(2)
        compose.onAllNodesWithText("Choose a color")[1].assertIsDisplayed()
        compose.onNodeWithText("\"blue\"").assertIsDisplayed()
    }

    @Test fun validFileHistoryKeepsItsFileCount() {
        renderHistories(listOf(UIMessagePart.Tool(
            "valid", "write_files", """{"files":[{"name":"a.txt","content":"hello"}]}""",
            listOf(UIMessagePart.Text("""{"files":["a.txt"],"files_content":{"a.txt":"hello"}}""")),
        )))
        compose.onNodeWithText("1 files").assertIsDisplayed()
    }
}
