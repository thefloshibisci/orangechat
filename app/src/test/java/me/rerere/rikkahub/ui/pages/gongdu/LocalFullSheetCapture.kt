package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.reading.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.koinInject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w390dp-h1380dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalFullSheetCapture {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()
    private val book = ReadBook("a".repeat(64), "合成书籍 · 慢慢读", "测试作者", "txt", listOf(
        ReadChapter("第一章 雨停以后", listOf("这是一段合成测试原文。窗外的雨停了，窗台上留着一小片安静。", "第二段合成原文，留给下一页。")),
        ReadChapter("第二章 风来到窗边", listOf("第三段合成原文。")),
    ))

    @Test fun captureExpandedSettings() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            private val root = temporary.newFolder()
            private val prefix = UUID.randomUUID().toString()
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        val repository = ReadRepository(context)
        var sharedCard: ReadShare? = null
        var rejectShare = false
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val appScope = AppScope()
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        val conversations = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
        // Seed the UI settings stream without exercising Android's keystore in a reader UI test.
        val appSettings = SettingsStore(context, AppScope().also { it.coroutineContext[Job]?.cancel() })
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        val isolatedKoin = koinApplication { modules(module {
            single { repository }
            single { conversations }
            single { appSettings }
        }) }
        try {
            runBlocking { repository.importParsed(book); conversations.insertConversation(conversation) }
            compose.setContent { KoinIsolatedContext(isolatedKoin) { Themed { GongduReadRoom(conversation.id.toString(), {}, {}) } } }
            compose.onNodeWithTag("read-book-${book.id}").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("返回书架").fetchSemanticsNodes().isNotEmpty() }
            openSettings()
            compose.onNodeWithText("更多排版").performClick()
            for ((mode, title) in listOf("paper" to "日间", "dark" to "夜间")) {
                compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("read-appearance"))).performClick()
                compose.waitUntil(10000) { repository.settings.value.theme == mode }
                compose.onNodeWithText("阅读设置").assertIsDisplayed()
                compose.onNodeWithText("本书统计").assertIsDisplayed()
                snapshot("full-expanded-$mode")
            }
        } finally {
            compose.activityRule.scenario.close()
            appScope.coroutineContext[Job]?.cancel()
            isolatedKoin.close()
            repository.close()
            database.close()
        }
    }
    private fun showControls() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-paragraph-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("read-controls").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("read-paragraph-0").performTouchInput { click(center) }
            compose.waitForIdle()
        }
    }

    private fun openSettings() {
        showControls()
        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
    }

    @Composable
    private fun Themed(settings: ReadSettings = ReadSettings(), fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { ReadTheme(settings) { Surface { Box(Modifier.fillMaxSize()) { content() } } } }
        }
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-read-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            // ModalBottomSheet lives in a separate Android dialog window. Include it in captures.
            val manager = org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(
                Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val windows = org.robolectric.util.ReflectionHelpers.getField<List<android.view.View>>(manager, "mViews")
            windows.filter { it !== view && it.isShown && it.width > 0 && it.height > 0 }.forEach { window ->
                val params = window.layoutParams as? android.view.WindowManager.LayoutParams
                if (params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                }
                val origin = IntArray(2); val base = IntArray(2)
                window.getLocationOnScreen(origin); view.getLocationOnScreen(base)
                canvas.save()
                canvas.translate((origin[0] - base[0]).toFloat(), (origin[1] - base[1]).toFloat())
                window.draw(canvas)
                canvas.restore()
            }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
