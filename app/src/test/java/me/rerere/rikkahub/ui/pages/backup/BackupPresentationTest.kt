package me.rerere.rikkahub.ui.pages.backup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.sync.*
import me.rerere.rikkahub.ui.pages.backup.tabs.ChatHistoryActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w412dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `actions have export above equal restore segments and preserve callbacks and disabled state`() {
        val calls = mutableListOf<String>()
        var enabled by mutableStateOf(true)
        compose.setContent {
            RecoveryTheme(dark = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.width(412.dp).padding(24.dp)) {
                        ChatHistoryActions(enabled, { calls.add("export") }, { calls.add("incremental") }, { calls.add("overwrite") })
                    }
                }
            }
        }
        val export = compose.onNodeWithText("仅导出聊天记录")
        val incremental = compose.onNodeWithText("增量恢复")
        val overwrite = compose.onNodeWithText("覆盖恢复")
        val first = export.fetchSemanticsNode().boundsInRoot
        val left = incremental.fetchSemanticsNode().boundsInRoot
        val right = overwrite.fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= left.top)
        assertEquals(left.top, right.top, 1f)
        assertEquals(left.width, right.width, 1f)
        export.performClick(); incremental.performClick(); overwrite.performClick()
        assertEquals(listOf("export", "incremental", "overwrite"), calls)
        save("chat-backup-actions")
        compose.runOnIdle { enabled = false }
        export.assertIsNotEnabled(); incremental.assertIsNotEnabled(); overwrite.assertIsNotEnabled()
    }

    @Test fun `light progress uses real counts and displays four stages with reachable actions`() {
        var cancelled = false
        var copied = false
        compose.setContent {
            RecoveryTheme(dark = false) {
                RecoveryContent(StartupRecoveryState.Restoring(ChatRestoreProgress(
                    "已处理会话 32/76（含搜索索引）", ChatRestoreStep.WRITING, 32, 76)), {}, { cancelled = true }) { copied = true }
            }
        }
        compose.onNodeWithText("已处理会话 32/76（含搜索索引）").assertIsDisplayed()
        compose.onAllNodesWithText("已完成").assertCountEquals(2)
        compose.onNodeWithText("42%").assertDoesNotExist()
        compose.onNodeWithText("取消恢复并进入应用").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("复制启动诊断").performScrollTo().performClick()
        assertTrue(cancelled && copied)
        compose.onNodeWithText("恢复聊天记录").performScrollTo()
        save("restore-progress-light")
    }

    @Test fun `dark waiting screen supports large text and scrolling to continue and diagnostics`() {
        var continued = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                RecoveryTheme(dark = true) {
                    RecoveryContent(StartupRecoveryState.AwaitingConfirmation, { continued = true }, {}) { }
                }
            }
        }
        compose.onNodeWithText("继续恢复聊天记录").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("复制启动诊断").performScrollTo().assertIsDisplayed()
        assertTrue(continued)
        compose.onNodeWithText("恢复聊天记录").performScrollTo()
        save("restore-waiting-dark-large-text")
    }

    @Test fun `cancelling disables repeat action and does not claim partially committed messages`() {
        compose.setContent { RecoveryTheme { RecoveryContent(StartupRecoveryState.Cancelling, {}, {}) { } } }
        compose.onNodeWithText("正在取消，请稍候…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("正在等待当前写入回滚").assertExists()
        compose.onNodeWithText("已完成").assertDoesNotExist()
    }

    private fun save(name: String) {
        val dir = File("../.codex/ui-review-20261020/screenshots").apply { mkdirs() }
        // Draw directly in the host test: PixelCopy waits for a device render callback
        // that Robolectric does not dispatch. Production rendering is unchanged.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
