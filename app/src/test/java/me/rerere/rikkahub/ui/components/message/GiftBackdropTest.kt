package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.RenderEffect
import android.graphics.RenderNode
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GiftBackdropTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingBlursActivityAndClosingRestoresIt() {
        var open by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            Button(onClick = { open = true }) { Text("打开红包") }
            if (open) InteractionDetailDialog({ open = false }, true) { close ->
                Column { Text("清晰的详情卡"); Button(onClick = close) { Text("关闭详情") } }
            }
        } }
        val node = ReflectionHelpers.getField<RenderNode>(compose.activity.window.decorView, "mRenderNode")
        compose.onNodeWithText("打开红包").performClick()
        compose.onNodeWithText("清晰的详情卡").assertIsDisplayed()
        // RenderNode returns true only when the effect changes: prove the activity had a blur.
        compose.runOnIdle {
            assertTrue("Activity must have an effect while the detail is open", node.setRenderEffect(null))
            node.setRenderEffect(RenderEffect.createBlurEffect(16f, 16f, android.graphics.Shader.TileMode.CLAMP))
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("关闭详情").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        compose.onNodeWithText("清晰的详情卡").assertDoesNotExist()
        compose.runOnIdle { assertFalse("Closing must clear the activity effect", node.setRenderEffect(null)) }
    }
}
