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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.serein.LocalSceneRepository
import org.junit.Assert.*
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
class LocalScenesCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `narrow management creates edits reads history and deletes while tools remain opt in`() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = LocalSceneRepository(db)
        val initial = Assistant(name = "南枳")
        val owner = initial.id.toString()
        try {
            runBlocking { repo.search(owner) }
            compose.setContent {
                var assistant by remember { mutableStateOf(initial) }
                MemoryTestTheme { Surface { Column(Modifier.width(320.dp)) {
                    LocalScenesCard(assistant, { assistant = it }, repo)
                } } }
            }
            compose.onNode(isToggleable()).assertIsOff()
            compose.onNodeWithText("管理本地记忆").performClick()
            waitFor("还没有记忆，可以先记下一件重要的事。")
            compose.onNodeWithText("记一条").performClick()
            compose.onNodeWithText("保存").assertIsNotEnabled()
            compose.onNodeWithText("标题").performTextInput("茶的口味")
            compose.onNodeWithText("记忆内容").performTextInput("喜欢清淡的茶")
            compose.onNodeWithText("保存").performClick()
            waitFor("茶的口味")
            compose.onNodeWithText("茶的口味").performClick()
            compose.onNodeWithText("记忆内容").performTextReplacement("喜欢清淡的绿茶")
            compose.onNodeWithText("保存").performClick()
            waitFor("喜欢清淡的绿茶")
            compose.onNodeWithText("茶的口味").performClick()
            compose.onNodeWithText("查看修订").performScrollTo().performClick()
            waitFor("修订 2 · 你")
            compose.onNodeWithText("喜欢清淡的茶").assertIsDisplayed()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("删除记忆").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            waitFor("还没有记忆，可以先记下一件重要的事。")
            compose.onNodeWithText("关闭").performClick()
            compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val output = File("build/test-screenshots/local-scenes-card.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue(runBlocking { repo.search(owner).isEmpty() })
        } finally { db.close() }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
