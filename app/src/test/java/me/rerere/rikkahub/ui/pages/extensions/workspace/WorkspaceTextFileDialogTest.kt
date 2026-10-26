package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import me.rerere.workspace.WorkspaceTextPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceTextFileDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullRemoteTextSavesExactOriginalAndEditedContent() {
        var saved: Pair<String, String>? = null
        show(onSave = { original, text -> saved = original to text })
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("workspace_file_text").performTextReplacement("new\n完整内容")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals("original" to "new\n完整内容", saved)
        compose.onNodeWithText("new\n完整内容").assertIsDisplayed()
    }

    @Test fun dirtyDismissAsksBeforeDiscardAndContinueRetainsDraft() {
        var closed = 0
        show(onDismiss = { closed++ })
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("workspace_file_text").performTextReplacement("unsaved")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        assertEquals(0, closed)
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("workspace_file_text").assertTextContains("unsaved")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun remoteConflictOrSaveFailureKeepsUnsavedTextVisible() {
        show(onSave = { _, _ -> error("远程文件已变化，请重新读取") })
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("workspace_file_text").performTextReplacement("keep my draft")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("远程文件已变化，请重新读取").assertIsDisplayed()
        compose.onNodeWithTag("workspace_file_text").assertTextContains("keep my draft")
    }

    @Test fun truncatedPreviewCannotOverwriteFullRemoteFile() {
        show(preview = WorkspaceTextPreview("partial", truncated = true))
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("保存").assertDoesNotExist()
        compose.onNodeWithText("文件较大，仅显示部分内容，无法在此保存修改。").assertIsDisplayed()
    }

    @Test fun localPreviewRemainsReadOnly() {
        show(editable = false)
        compose.onNodeWithText("original").assertIsDisplayed()
        compose.onNodeWithText("编辑").assertDoesNotExist()
    }

    @Test fun readFailureHasRetryAndCannotBecomeEditableFileContent() {
        var retries = 0
        show(preview = null, error = "network unavailable", onRetry = { retries++ })
        compose.onNodeWithText("network unavailable").assertIsDisplayed()
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("重新读取").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun show(
        preview: WorkspaceTextPreview? = WorkspaceTextPreview("original"),
        error: String? = null,
        editable: Boolean = true,
        onRetry: () -> Unit = {},
        onSave: suspend (String, String) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                WorkspaceTextFileDialog("file.txt", preview, loading = false, error, editable,
                    onRetry, onSave, onDismiss)
            }
        }
    }
}
