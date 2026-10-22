package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.backup.tabs.ChatHistoryActions
import org.junit.Assert.assertEquals
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
class ReactionPresetLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun presetsAreVisibleChipsAndEntryOnlyAppearsInDialog() {
        val presets = mutableStateListOf("⊙ω⊙", "⊙_⊙", "Õ_Õ")
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        ReactionPresetSettings(presets, { presets.add(it) }, { presets.remove(it) })
                    }
                }
            }
        }
        presets.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("已添加").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        capture("kaomoji-tags.png")
        compose.onNodeWithContentDescription("添加预设").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("(test)")
        compose.onNodeWithText("添加", useUnmergedTree = true).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("(test)").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除 (test)").performClick()
        compose.onNodeWithText("(test)").assertDoesNotExist()
        assertEquals(listOf("⊙ω⊙", "⊙_⊙", "Õ_Õ"), presets.toList())
    }

    @Test fun backupActionsRenderCompactlyBesideReferenceAndDispatchSeparately() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatHistoryActions(true, { actions.add("export") },
                            { actions.add("incremental") }, { actions.add("overwrite") })
                        Text("备份项目（高度参照）")
                        MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("聊天记录", "文件").forEachIndexed { index, label ->
                                SegmentedButton(
                                    checked = true,
                                    onCheckedChange = {},
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(label) }
                            }
                        }
                    }
                }
            }
        }
        listOf("仅导出聊天记录", "增量恢复", "覆盖恢复").forEach {
            compose.onNodeWithText(it).assertHeightIsEqualTo(40.dp).performClick()
        }
        assertEquals(listOf("export", "incremental", "overwrite"), actions)
        capture("backup-actions-height.png")
    }

    private fun capture(name: String) {
        val output = File("build/test-screenshots/$name")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
