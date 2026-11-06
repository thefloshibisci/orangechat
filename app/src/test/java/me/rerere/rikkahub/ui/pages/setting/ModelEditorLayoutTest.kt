package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.*
import me.rerere.rikkahub.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "zh-rCN-w400dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelEditorLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun footerFreesEighteenDpAndMovesButtonsDownByNineDp() {
        var revised by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            if (revised) ModelEditorLayout(Modifier.fillMaxSize(), title = { Text("编辑模型") },
                content = { Box(Modifier.fillMaxSize().testTag("form")) },
                actions = { TextButton(onClick = {}, modifier = Modifier.testTag("confirm")) { Text("确认") } })
            else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("编辑模型")
                Box(Modifier.weight(1f).fillMaxWidth().testTag("form"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {}, modifier = Modifier.testTag("confirm")) { Text("确认") }
                }
            }
        } }
        val oldFormBottom = compose.onNodeWithTag("form").fetchSemanticsNode().boundsInRoot.bottom
        val oldButton = compose.onNodeWithTag("confirm").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { revised = true }
        assertEquals(18f, compose.onNodeWithTag("form").fetchSemanticsNode().boundsInRoot.bottom - oldFormBottom, 1f)
        assertEquals(9f, compose.onNodeWithTag("confirm").fetchSemanticsNode().boundsInRoot.top - oldButton, 1f)
    }

    @Test fun actualModelFormKeepsBothAbilityButtonsAboveTheFooterAndReachableOnSmallScreens() {
        var height by mutableStateOf(810)
        var model by mutableStateOf(Model(modelId = "gpt-6-astra", displayName = "gpt-6-astra",
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE), abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)))
        var confirms = 0
        compose.setContent { MaterialTheme {
            Surface {
                ModelEditorLayout(Modifier.fillMaxWidth().height(height.dp),
                    title = { Text("编辑模型", Modifier.height(48.dp), style = MaterialTheme.typography.titleLarge) },
                    content = { ModelSettingsForm(model, { model = it }, true, ProviderSetting.OpenAI(),
                        enableAutomatic = { _, value -> value }) },
                    actions = {
                        TextButton(onClick = {}) { Text("取消") }
                        TextButton(onClick = { confirms++ }, modifier = Modifier.testTag("confirm")) { Text("确认") }
                    })
            }
        } }
        val toolLabel = compose.activity.getString(R.string.setting_provider_page_tool)
        val reasoningLabel = compose.activity.getString(R.string.setting_provider_page_reasoning)
        for (h in listOf(810, 620)) {
            compose.runOnIdle { height = h }
            if (h < 800) compose.onNodeWithTag("model-ability-controls").performScrollTo()
            val footer = compose.onNodeWithTag("model-editor-actions").fetchSemanticsNode().boundsInRoot
            capture("model-editor-$h")
            val abilities = compose.onNodeWithTag("model-ability-controls").fetchSemanticsNode().boundsInRoot
            assertTrue("Ability row at height $h must fit above footer: $abilities / $footer",
                abilities.bottom <= footer.top && abilities.height >= 40f)
            for (label in listOf(toolLabel, reasoningLabel)) {
                val node = compose.onNodeWithText(label)
                node.assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Ability label must be completely above footer: $bounds / $footer", bounds.bottom <= footer.top && bounds.height >= 16f)
            }
            compose.onNodeWithTag("confirm").performClick()
            capture("model-editor-$h")
        }
        assertEquals(2, confirms)
        compose.onNodeWithText(toolLabel).performClick()
        assertFalse(ModelAbility.TOOL in model.abilities)
    }

    private fun capture(name: String) {
        val output = File("build/reports/chat-interaction/$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
