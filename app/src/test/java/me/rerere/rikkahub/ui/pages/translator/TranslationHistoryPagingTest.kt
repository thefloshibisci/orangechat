package me.rerere.rikkahub.ui.pages.translator

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationHistoryPagingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: AppDatabase
    private val emptyText = "还没有翻译历史。完成翻译后会自动保存。"
    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun emptyDatabasePresentsEmptyHistoryWithoutAccessingIndexZero() {
        show()
        awaitText(emptyText)
        compose.onNodeWithText("清空历史").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除此条翻译记录").assertDoesNotExist()
    }

    @Test fun deletingLastDatabaseRowPublishesAnEmptyPagingGeneration() {
        seed(1); show()
        awaitText("source-0")
        compose.onNodeWithContentDescription("删除此条翻译记录").performClick()
        awaitText(emptyText)
        compose.onNodeWithText("清空历史").assertIsNotEnabled()
        assertEquals(0, rowCount())
    }

    @Test fun deletingFirstRowKeepsFollowingRecordIdentityAndRestoreData() {
        seed(3)
        var restored: TranslationHistoryEntity? = null
        show { restored = it }
        awaitText("source-2")
        compose.onAllNodesWithContentDescription("删除此条翻译记录")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("source-2").fetchSemanticsNodes().isEmpty() }
        awaitText("source-1")
        compose.onNodeWithText("source-1").performClick()
        compose.runOnIdle { assertEquals(row(1), restored) }
        assertEquals(2, rowCount())
    }

    @Test fun clearingScrolledDatabaseHistoryDoesNotReuseOldKeyIndexes() {
        seed(25); show()
        awaitText("source-24")
        compose.onNodeWithTag("translation_history_list").performScrollToIndex(24)
        compose.onNodeWithText("source-0").assertIsDisplayed()
        compose.onNodeWithText("清空历史").performClick()
        compose.onNodeWithText("确认清空").performClick()
        awaitText(emptyText)
        compose.onNodeWithText("清空历史").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除此条翻译记录").assertDoesNotExist()
        assertEquals(0, rowCount())
    }

    private fun show(onRestore: (TranslationHistoryEntity) -> Unit = {}) {
        val history = Pager(PagingConfig(pageSize = 40, initialLoadSize = 40, enablePlaceholders = false)) {
            db.translationHistoryDao().pagingAll()
        }.flow
        compose.setContent {
            val entries = history.collectAsLazyPagingItems()
            val scope = rememberCoroutineScope()
            MaterialTheme {
                TranslationHistoryPagingContent(entries, onRestore,
                    onDelete = { row -> scope.launch { db.translationHistoryDao().deleteById(row.id) } },
                    onClear = { scope.launch { db.translationHistoryDao().deleteAll() } },
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun seed(count: Int) = runBlocking { repeat(count) { db.translationHistoryDao().insert(row(it)) } }
    private fun rowCount() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM translation_history").use {
        it.moveToFirst(); it.getInt(0)
    }
    private fun row(index: Int) = TranslationHistoryEntity("id-$index", "source-$index", "result-$index", "en", "model", index.toLong())
}
