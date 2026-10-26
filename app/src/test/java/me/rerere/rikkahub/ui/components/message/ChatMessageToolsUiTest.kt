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
