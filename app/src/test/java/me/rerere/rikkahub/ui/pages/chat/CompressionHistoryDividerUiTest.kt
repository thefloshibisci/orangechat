package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompressionHistoryDividerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun numberedDividersRenderBetweenDialogueAtPhoneWidth() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.width(360.dp)) {
                        Text("之前聊过的内容")
                        CompressionHistoryDivider("以上为第 1 次压缩前对话")
                        Text("之后继续的对话")
                        CompressionHistoryDivider("以上为第 2 次压缩前对话")
                        Text("最近的对话")
                    }
                }
            }
        }
        compose.onNodeWithText("以上为第 1 次压缩前对话").assertIsDisplayed()
        compose.onNodeWithText("以上为第 2 次压缩前对话").assertIsDisplayed()
        val output = File("build/test-screenshots/compression-history-divider.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
