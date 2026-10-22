package me.rerere.rikkahub.ui.components.ai

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
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
class CompressContextDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val confirm get() = compose.activity.getString(R.string.confirm)
    private val cancel get() = compose.activity.getString(R.string.cancel)

    @Test fun failureExitsSpinnerShowsErrorAndAllowsRetryWithoutClosing() {
        val result = CompletableDeferred<Unit>()
        var dismissed = false
        var calls = 0
        compose.setContent { CompositionLocalProvider(LocalSettings provides Settings()) { MaterialTheme {
            CompressContextDialog({ dismissed = true }) { _, _, _, _, progress ->
                calls++
                progress("正在整段压缩聊天记录（含工具内容）…")
                if (calls == 1) { result.await(); error("模型请求超时，原文未修改") }
            }
        } } }
        compose.onNodeWithText(confirm).performClick()
        compose.onNodeWithText("正在整段压缩聊天记录（含工具内容）…").assertIsDisplayed()
        compose.runOnIdle { result.complete(Unit) }
        compose.onNodeWithText("模型请求超时，原文未修改").assertIsDisplayed()
        compose.onNodeWithText(confirm).assertIsEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithText(confirm).performClick()
        compose.runOnIdle { assertEquals(2, calls); assertTrue(dismissed) }
    }

    @Test fun cancellationStopsRequestAndRestoresFormWithoutSavingOrDismissing() {
        var closed = false
        var dismissed = false
        compose.setContent { CompositionLocalProvider(LocalSettings provides Settings()) { MaterialTheme {
            CompressContextDialog({ dismissed = true }) { _, _, _, _, _ ->
                try { awaitCancellation() } finally { closed = true }
            }
        } } }
        compose.onNodeWithText(confirm).performClick()
        compose.onNodeWithText(cancel).performClick()
        compose.onNodeWithText(confirm).assertIsEnabled()
        compose.runOnIdle { assertTrue(closed); assertFalse(dismissed) }
    }
}
