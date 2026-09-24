package me.rerere.rikkahub.plugin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.model.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PluginCardInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleSwitchAndDeleteAreIndependentTouchTargets() {
        var opened = 0
        var deleted = 0
        compose.setContent {
            var enabled by remember { mutableStateOf(true) }
            val plugin = PluginInfo(
                PluginManifest("test.plugin", "Test Plugin", "", "1", "Test", "P", "main.js"),
                File("test.plugin"), enabled,
                loadError = "Script failed",
            )
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    PluginCard(plugin, onClick = { opened++ }, onToggle = { enabled = it },
                        onDelete = { deleted++ })
                }
            }
        }
        compose.onNodeWithText("Test Plugin").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened) }
        val toggle = compose.onNode(isToggleable())
        toggle.performTouchInput { click() }
        toggle.assertIsOff()
        toggle.performTouchInput { click() }
        toggle.assertIsOn()
        compose.onNodeWithContentDescription("删除").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(0, deleted)
        }
        compose.onNodeWithText("删除").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, deleted)
        }
    }
}
