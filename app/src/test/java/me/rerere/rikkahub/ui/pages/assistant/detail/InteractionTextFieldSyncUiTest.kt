package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractionTextFieldSyncUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `AI changes refresh idle field without writing the value back`() {
        var external by mutableStateOf("拍了拍")
        val saves = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { Surface { InteractionTextField(external, "动作", 20, onChange = { saves += it }) } }
        }
        compose.runOnIdle { external = "揉了揉" }
        compose.waitForIdle()
        assertEquals("揉了揉", text())
        assertEquals(emptyList<String>(), saves)
    }

    @Test fun `focused editor keeps selection until blur then displays external update without saving it`() {
        var external by mutableStateOf("拍了拍")
        val saves = mutableListOf<String>()
        lateinit var clearFocus: () -> Unit
        compose.setContent {
            val focus = LocalFocusManager.current
            clearFocus = { focus.clearFocus() }
            MaterialTheme { Surface { InteractionTextField(external, "动作", 20, onChange = { saves += it }) } }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick().performTextReplacement("用户的草稿")
        field.performTextInputSelection(TextRange(1, 3))
        compose.waitForIdle()
        assertEquals(listOf("用户的草稿"), saves)
        compose.runOnIdle { external = "AI改的动作" }
        compose.waitForIdle()
        field.assertIsFocused()
        assertEquals("用户的草稿", text())
        assertEquals(TextRange(1, 3), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { clearFocus() }
        compose.waitForIdle()
        assertEquals("AI改的动作", text())
        assertEquals(listOf("用户的草稿"), saves)
    }

    @Test fun `blur and delayed save echoes do not erase the latest user edit`() {
        var external by mutableStateOf("拍了拍")
        val saves = mutableListOf<String>()
        lateinit var clearFocus: () -> Unit
        compose.setContent {
            val focus = LocalFocusManager.current
            clearFocus = { focus.clearFocus() }
            MaterialTheme { Surface { InteractionTextField(external, "动作", 20, onChange = { saves += it }) } }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick().performTextReplacement("草稿一")
        compose.waitForIdle()
        field.performTextReplacement("草稿二")
        compose.waitForIdle()
        compose.runOnIdle { clearFocus() }
        compose.waitForIdle()
        assertEquals("草稿二", text())
        compose.runOnIdle { external = "草稿一" }
        compose.waitForIdle()
        assertEquals("草稿二", text())
        compose.runOnIdle { external = "草稿二" }
        compose.waitForIdle()
        assertEquals("草稿二", text())
        assertEquals(listOf("草稿一", "草稿二"), saves)
    }

    private fun text(): String = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text
}
