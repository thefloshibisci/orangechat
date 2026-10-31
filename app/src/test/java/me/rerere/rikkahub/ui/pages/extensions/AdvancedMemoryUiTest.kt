package me.rerere.rikkahub.ui.pages.extensions

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.serein.SereinHookConfig
import me.rerere.rikkahub.ui.pages.assistant.detail.SereinHookControls
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
class AdvancedMemoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `original advanced memory entry selects bound assistant and resets dialogs when switching`() {
        val first = Assistant(name = "助手甲")
        val bound = Assistant(name = "助手乙")
        compose.setContent {
            MemoryTestTheme {
                AdvancedMemoryContent(listOf(first, bound), bound.id.toString(), Modifier.width(320.dp)) { assistant ->
                    SereinHookControls(assistant, SereinHookConfig(assistantId = bound.id.toString()), {}, { "synthetic" })
                }
            }
        }
        compose.onNodeWithText("当前助手：助手乙").assertIsDisplayed()
        compose.onNodeWithText("Serein 自动召回").assertIsDisplayed()
        compose.onNodeWithText("新建进阶记忆").assertDoesNotExist()
        compose.onNodeWithText("配置连接").performScrollTo().performClick()
        compose.onNodeWithText("Gateway Key 是部署时保存的 api-token 文件内容。Zeabur 部署查看 gateway 服务的 /secrets/api-token；只填密钥，不加 Bearer。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("当前助手：助手乙").performScrollTo().performClick()
        compose.onNodeWithText("助手甲").performClick()
        compose.onNodeWithText("当前助手：助手甲").assertIsDisplayed()
        compose.onNodeWithText("配置连接").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("当前助手：助手甲").performScrollTo().performClick()
        compose.onNodeWithText("助手乙").performClick()
        compose.onNodeWithText("配置连接").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("记忆使用说明").assertDoesNotExist()
        compose.onNodeWithText("使用说明").performScrollTo().performClick()
        compose.onNodeWithText("记忆使用说明").assertIsDisplayed()
        compose.onNodeWithText("旧 Supabase 连接已停用，云端原有数据保留。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("记忆使用说明").assertDoesNotExist()
        compose.onNodeWithText("当前助手：助手乙").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/test-screenshots/advanced-memory.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Config(qualifiers = "w411dp-h891dp-mdpi")
    @Test fun `phone width preview uses shared settings typography`() {
        val assistant = Assistant(name = "助手")
        compose.setContent {
            MemoryTestTheme {
                val colors = me.rerere.rikkahub.ui.theme.presets.LiquidGlassThemePreset.getColorScheme(false)
                MaterialTheme(colorScheme = colors, typography = me.rerere.rikkahub.ui.theme.Typography) {
                    androidx.compose.material3.Surface(color = colors.surfaceContainer) {
                        AdvancedMemoryContent(listOf(assistant), assistant.id.toString()) {
                            SereinHookControls(it, SereinHookConfig(assistantId = it.id.toString()), {}, { "synthetic" })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("当前助手：助手").assertIsDisplayed()
        compose.onNodeWithText("使用说明").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/test-screenshots/advanced-memory-phone.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `loading and deleted assistant never leave stale memory controls`() {
        val assistant = Assistant(name = "助手")
        var assistants by mutableStateOf(emptyList<Assistant>())
        compose.setContent {
            MemoryTestTheme { AdvancedMemoryContent(assistants, assistant.id.toString()) { Text("controls:${it.name}") } }
        }
        compose.onNodeWithText("请先创建一位助手，再配置记忆。").assertIsDisplayed()
        compose.runOnIdle { assistants = listOf(assistant) }
        compose.onNodeWithText("controls:助手").assertIsDisplayed()
        compose.runOnIdle { assistants = emptyList() }
        compose.onNodeWithText("controls:助手").assertDoesNotExist()
        compose.onNodeWithText("请先创建一位助手，再配置记忆。").assertIsDisplayed()
    }
}
