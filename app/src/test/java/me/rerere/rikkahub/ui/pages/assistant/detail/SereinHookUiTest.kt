package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.ui.pages.extensions.MemoryTestTheme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.serein.SereinHookConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SereinHookUiTest {
    @Test fun `local recall and upload failures are visible with service guidance`() {
        val assistant = Assistant(name = "南枳")
        compose.setContent {
            MemoryTestTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                SereinHookControls(assistant,
                    SereinHookConfig(enabled = true, assistantId = assistant.id.toString(),
                        baseUrl = "https://memory.example", gatewayKey = "synthetic-key", uploadEnabled = true),
                    {}, { "synthetic" }, counts = me.rerere.rikkahub.data.serein.SereinUploadCounts(pending = 1, retrying = 1),
                    recallStatus = me.rerere.rikkahub.data.serein.SereinRecallStatus("timeout", "[]", 1),
                    uploadError = "http_404")
            } }
        }
        compose.onNodeWithText("最近召回：等待超时，可选 5 秒后再试").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("运行状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("上传原因：接口不可用，请检查 gateway 地址和服务版本").performScrollTo().assertIsDisplayed()
    }
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `connection stays opt in and user can choose five seconds on narrow screen`() {
        val assistant = Assistant(name = "南枳")
        var saved: SereinHookConfig? = null
        compose.setContent {
            var config by remember { mutableStateOf(SereinHookConfig()) }
            MemoryTestTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                SereinHookControls(assistant, config, { saved = it; config = it }, { "synthetic connection result" })
            } }
        }
        compose.onNodeWithTag("serein-recall").assertIsOff()
        compose.onNodeWithTag("serein-recall").performClick()
        compose.onNodeWithText("保存并开启").assertIsNotEnabled()
        compose.onNodeWithText("HTTPS 实例根地址").performScrollTo().performTextInput("https://memory.example")
        compose.onNodeWithText("Gateway Key").performScrollTo().performTextInput("synthetic-secret")
        compose.onNodeWithText("5s").performScrollTo().performClick()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText("保存并开启").performClick()
        compose.waitUntil(5000) { saved != null }
        assertEquals(5000L, saved!!.waitMs)
        assertEquals(assistant.id.toString(), saved!!.assistantId)
        compose.onNodeWithTag("serein-recall").assertIsOn()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/test-screenshots/serein-hook-card.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("serein-recall").performClick()
        compose.waitUntil(5000) { saved?.enabled == false }
        assertFalse(saved!!.enabled)
    }

    @Test fun `upload can be enabled and paused independently while recall stays off`() {
        val assistant = Assistant(name = "南枳")
        var saved: SereinHookConfig? = null
        compose.setContent {
            var config by remember { mutableStateOf(SereinHookConfig(assistantId = assistant.id.toString(),
                baseUrl = "https://memory.example", gatewayKey = "synthetic-key")) }
            MemoryTestTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                SereinHookControls(assistant, config, { saved = it; config = it }, { "synthetic" })
            } }
        }
        compose.onNodeWithTag("serein-recall").assertIsOff()
        compose.onNodeWithTag("serein-upload").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("开启上传").performClick()
        compose.waitUntil(5000) { saved?.uploadEnabled == true }
        assertFalse(saved!!.enabled)
        assertTrue(saved!!.uploadStartedAt.isNotBlank())
        compose.onNodeWithTag("serein-upload").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5000) { saved?.uploadEnabled == false }
        compose.onNodeWithText("配置连接").performScrollTo().performClick()
        compose.onNodeWithText("保存连接").assertIsNotEnabled()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText("保存连接").performClick()
        compose.waitUntil(5000) { !compose.onAllNodes(isDialog()).fetchSemanticsNodes().any() }
        assertFalse(saved!!.enabled)
    }
}
