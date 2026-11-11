package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.reading.ReadRecord
import me.rerere.rikkahub.data.reading.ReadSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadExtrasUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyShelfOffersBackupRecoveryWithoutInventingReadingActivity() {
        var restored = false
        compose.setContent {
            MaterialTheme { ReadTheme(ReadSettings()) { Surface {
                ReadExtrasContent(null, emptyList(), "", false, false, null, false, Modifier.fillMaxSize(),
                    {}, {}, {}, { restored = true }, {}, {})
            } } }
        }
        compose.onNodeWithText("打开一本书，就能留下札记").assertIsDisplayed()
        compose.onNodeWithText("从备份恢复").performScrollTo().performClick()
        assertTrue(restored)
        compose.onNodeWithTag("read-journal-input").assertDoesNotExist()
        compose.onNodeWithText("导出批注 EPUB").assertDoesNotExist()
    }

    @Test fun compactNightJournalKeepsDraftOnErrorAndSeparatesAssistantAccessFromHumanTime() {
        val draft = mutableStateOf("雨停以后，想把这一句留给你。")
        var saves = 0
        val records = listOf(
            ReadRecord("human", "book", "chat", "assistant", "human_reading", seconds = 75),
            ReadRecord("assistant", "book", "chat", "assistant", "assistant_reading", start = 4, end = 28, characters = 24, author = "assistant"),
            ReadRecord("journal", "book", "chat", "assistant", "journal", text = "助手的合成札记。", author = "assistant"),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.2f)) {
                MaterialTheme { ReadTheme(ReadSettings(theme = "bluecard-night")) { Surface {
                    ReadExtrasContent("窗边的书", records, draft.value, false, true, "保存失败，草稿已保留", true,
                        Modifier.fillMaxSize(), { draft.value = it }, { saves++ }, {}, {}, {}, {})
                } } }
            }
        }
        compose.onNodeWithText("我的阅读 1 分 15 秒").assertIsDisplayed()
        compose.onNodeWithText("助手读取 1 个片段 · 札记 1 则").assertIsDisplayed()
        snapshot("read-extras-night-compact")
        compose.onNodeWithText("留下札记").performScrollTo().performClick()
        assertEquals(1, saves)
        compose.onNodeWithTag("read-journal-input").assertTextContains(draft.value)
        compose.onNodeWithText("助手的合成札记。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("read-extras").performScrollToNode(hasText("导出批注 EPUB"))
        compose.onNodeWithText("导出批注 EPUB").assertIsDisplayed().assertIsEnabled()
        snapshot("read-extras-night-export")
    }

    @Test fun busyPanelCannotSaveOrLaunchAnotherExport() {
        compose.setContent { MaterialTheme { ReadTheme(ReadSettings()) { Surface {
            ReadExtrasContent("测试书", emptyList(), "这是一则草稿", true, true, null, false, Modifier.fillMaxSize(),
                {}, {}, {}, {}, {}, {})
        } } } }
        compose.onNodeWithText("留下札记").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("保存阅读备份").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("导出 Markdown").performScrollTo().assertIsNotEnabled()
    }

    @Test fun documentNamesKeepUnicodeAndRemoveFilesystemSeparators() {
        assertEquals("法式_蓝笺_札记_", readExportFilename("法式/蓝笺:札记?"))
        assertEquals("共度阅读", readExportFilename("   "))
        assertEquals(80, readExportFilename("书".repeat(100)).length)
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-read-coread-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
