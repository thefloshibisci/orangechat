package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.ui.pages.extensions.MemoryTestTheme

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalOriginalsCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `narrow phone controls show opt in and useful empty browser`() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = AppScope()
        val settingsScope = AppScope().also { it.cancel() }
        try {
            val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
            val repository = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db, files, MessageFtsManager(db), SettingsStore(context, settingsScope))
            // App startup opens Room before this page. Initialize it outside the Compose test clock.
            runBlocking { repository.searchRawOriginals(Assistant().id) }
            compose.setContent {
                var assistant by remember { mutableStateOf(Assistant(name = "南枳")) }
                MemoryTestTheme {
                    Surface {
                        Column(Modifier.width(320.dp)) {
                            LocalOriginalsCard(assistant, { assistant = it }, repository)
                        }
                    }
                }
            }
            compose.onNodeWithText("保存聊天原话").assertIsDisplayed()
            compose.onNodeWithText("导入已有聊天").assertIsNotEnabled()
            compose.onNode(isToggleable()).performClick()
            val output = File("build/test-screenshots/local-originals-card.png")
            output.parentFile?.mkdirs()
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("查看原话").performClick()
            try {
                compose.waitUntil(10_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText("还没有原话，请开启存档后聊天，或导入已有聊天。")).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: Throwable) {
                Thread.getAllStackTraces().forEach { (thread, trace) ->
                    if (thread.name.contains("Dispatcher") || thread.name.contains("arch_disk") || trace.any { it.className.contains("room") }) {
                        println("Thread ${thread.name}: ${trace.joinToString("\n")}")
                    }
                }
                val roots = compose.onAllNodes(isRoot())
                roots.fetchSemanticsNodes().indices.forEach { println(roots[it].printToString()) }
                throw failure
            }
            compose.onNodeWithText("搜索原话").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
        } finally {
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            db.close()
        }
    }
}
