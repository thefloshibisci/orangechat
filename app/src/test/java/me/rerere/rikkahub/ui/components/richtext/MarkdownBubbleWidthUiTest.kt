package me.rerere.rikkahub.ui.components.richtext

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownBubbleWidthUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `short plain markdown bubble wraps content width instead of filling parent`() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    Box(Modifier.width(400.dp)) {
                        Box(Modifier.testTag("bubble")) {
                            MarkdownBlock(content = "嘀嘀咕咕")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val width = compose.onNodeWithTag("bubble").getBoundsInRoot().width
        assertTrue("Expected wrapped bubble width > 0.dp, got $width", width > 0.dp)
        assertTrue("Expected short plain bubble width < 200.dp (not 400.dp), got $width", width < 200.dp)
    }

    @Test
    fun `centered html paragraph still fills width for alignment`() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides Settings()) {
                    Box(Modifier.width(400.dp)) {
                        Box(Modifier.testTag("centered")) {
                            MarkdownBlock(content = """<p style="text-align: center">居中</p>""")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val width = compose.onNodeWithTag("centered").getBoundsInRoot().width
        assertTrue("Expected centered paragraph width >= 380.dp, got $width", width >= 380.dp)
    }
}
