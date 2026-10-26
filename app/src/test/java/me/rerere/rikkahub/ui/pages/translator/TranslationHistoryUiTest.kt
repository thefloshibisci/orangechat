package me.rerere.rikkahub.ui.pages.translator

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.paging.LoadState
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val entry = TranslationHistoryEntity(
        id = "history-id", sourceText = "Original source", translatedText = "恢复的译文",
        targetLanguage = "zh-Hans-CN", modelId = "deleted-model-id", createAt = 1792886400000,
    )
    private val loaded = LoadState.NotLoading(endOfPaginationReached = true)

    @Test fun `selecting a preview restores exact record and deleting never fires restoration`() {
        var restored: TranslationHistoryEntity? = null
        var deleted: TranslationHistoryEntity? = null
        show(onRestore = { restored = it }, onDelete = { deleted = it })
        compose.onNodeWithText(entry.sourceText).performClick()
        compose.runOnIdle {
            assertEquals(entry, restored)
            assertNull(deleted)
            restored = null
        }
        compose.onNodeWithContentDescription("删除此条翻译记录").performClick()
        compose.runOnIdle {
            assertEquals(entry, deleted)
            assertNull(restored)
        }
    }

    @Test fun `clear history cancel leaves records and confirmation is required before delete`() {
        var clears = 0
        show(onClear = { clears++ })
        compose.onNodeWithText("清空历史").performClick()
        compose.onNodeWithText("清空翻译历史？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText(entry.sourceText).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("清空历史").performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithText("清空翻译历史？").assertDoesNotExist()
    }

    @Test fun `empty loaded history explains saving and disables clearing`() {
        show(entries = emptyList())
        compose.onNodeWithText("还没有翻译历史。完成翻译后会自动保存。").assertIsDisplayed()
        compose.onNodeWithText("清空历史").assertIsNotEnabled()
        compose.onNodeWithText("正在读取翻译历史…").assertDoesNotExist()
    }

    @Test fun `initial load is visibly pending and never claims no history`() {
        show(entries = emptyList(), refreshState = LoadState.Loading)
        compose.onNodeWithText("正在读取翻译历史…").assertIsDisplayed()
        compose.onNodeWithText("还没有翻译历史。完成翻译后会自动保存。").assertDoesNotExist()
    }

    @Test fun `failed initial load offers retry without misrepresenting it as empty`() {
        var retries = 0
        show(entries = emptyList(), refreshState = LoadState.Error(IllegalStateException("read failed")),
            onRetry = { retries++ })
        compose.onNodeWithText("读取翻译历史失败，请重试。").assertIsDisplayed()
        compose.onNodeWithText("还没有翻译历史。完成翻译后会自动保存。").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun `failed next page retains loaded preview and offers retry`() {
        var retries = 0
        show(appendState = LoadState.Error(IllegalStateException("page failed")), onRetry = { retries++ })
        compose.onNodeWithText(entry.sourceText).assertIsDisplayed()
        compose.onNodeWithText("读取翻译历史失败，请重试。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun show(
        entries: List<TranslationHistoryEntity> = listOf(entry),
        refreshState: LoadState = loaded,
        appendState: LoadState = loaded,
        onRetry: () -> Unit = {},
        onRestore: (TranslationHistoryEntity) -> Unit = {},
        onDelete: (TranslationHistoryEntity) -> Unit = {},
        onClear: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                TranslationHistoryContent(
                    itemCount = entries.size, itemAt = { entries[it] },
                    refreshState = refreshState, appendState = appendState,
                    onRetry = onRetry, onRestore = onRestore, onDelete = onDelete, onClear = onClear,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
