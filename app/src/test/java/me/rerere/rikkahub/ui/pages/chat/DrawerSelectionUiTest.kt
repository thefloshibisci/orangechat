package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.Typography
import me.rerere.rikkahub.ui.theme.presets.LiquidGlassThemePreset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectionMovesOnClickWithImageBackdrop() {
        val chats = listOf("考公学习交流", "宝宝有话要说").map {
            Conversation(assistantId = Uuid.random(), title = it, messageNodes = emptyList())
        }
        val painter = object : Painter() {
            override val intrinsicSize = Size(300f, 300f)
            override fun DrawScope.onDraw() {
                drawRect(Brush.linearGradient(listOf(Color(0xFF9ACFE4), Color(0xFFE6CDE8), Color(0xFFCCE8DB))))
            }
        }
        compose.setContent {
            var selected by remember { mutableStateOf(chats.first().id) }
            val backdrop = remember { DrawerBackdrop(painter) }
            MaterialTheme(colorScheme = LiquidGlassThemePreset.getColorScheme(false), typography = Typography) {
                CompositionLocalProvider(LocalDrawerBackdrop provides backdrop) {
                    Column(Modifier.width(300.dp).background(Color(0xFFF5F7FC)).drawerBackdropRoot(backdrop)
                        .padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateHeaderItem(label = "昨天", drawerItemAlpha = 1f,
                            materialMode = DisplayMaterialMode.GLASS)
                        chats.forEach { chat ->
                            ConversationItem(conversation = chat, selected = chat.id == selected, loading = false,
                                materialMode = DisplayMaterialMode.GLASS, onClick = { selected = it.id })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(chats[0].title).assertIsSelected()
        compose.onNodeWithText(chats[0].title).assertHeightIsEqualTo(36.dp)
        compose.onNodeWithText(chats[1].title).assertHeightIsEqualTo(36.dp)
        compose.onNodeWithText(chats[1].title).assertIsNotSelected().performClick()
        compose.onNodeWithText(chats[1].title).assertIsSelected()
        compose.onNodeWithText(chats[0].title).assertIsNotSelected()
        compose.onNodeWithText(chats[0].title).assertHeightIsEqualTo(36.dp)
        compose.onNodeWithText(chats[1].title).assertHeightIsEqualTo(36.dp)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/test-screenshots/drawer-selection.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
