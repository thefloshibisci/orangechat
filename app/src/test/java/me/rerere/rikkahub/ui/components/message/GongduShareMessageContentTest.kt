package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import me.rerere.rikkahub.data.listen.ListenRepository
import me.rerere.rikkahub.data.listen.ListenSong
import me.rerere.rikkahub.data.listen.listenShareText
import me.rerere.rikkahub.data.reading.ReadBook
import me.rerere.rikkahub.data.reading.ReadChapter
import me.rerere.rikkahub.data.reading.readShareText
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp-xxhdpi")
class GongduShareMessageContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var repository: ListenRepository? = null
    @After fun closeRepository() { repository?.close() }

    @Test fun mixedLegacyMessageShowsBothCardsAndEveryCommentWithoutRawJson() {
        val song = listenShareText(ListenSong("123", "合成歌曲", "合成歌手"))
        val book = ReadBook("a".repeat(64), "合成书籍", "作者", "txt",
            listOf(ReadChapter("第一章", listOf("这是原文[zip:literal]书摘。"))))
        val excerpt = readShareText(book, 0, 0, "原文[zip:literal]书摘")
        val listenRepositoryForTest = ListenRepository(RuntimeEnvironment.getApplication(),
            OkHttpClient.Builder().addInterceptor { error("Unexpected network call") }.build())
            .also { repository = it }
        compose.setContent {
            KoinApplication(application = { modules(module { single<ListenRepository> { listenRepositoryForTest } }) }) {
                MaterialTheme {
                    GongduShareMessageContent("开头附言$song\n中间附言$excerpt\n结尾附言", "chat", true) { Text(it) }
                }
            }
        }
        compose.onNodeWithText("开头附言").assertIsDisplayed()
        compose.onNodeWithText("合成歌曲").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放这首歌").assertExists()
        compose.onNodeWithText("\n中间附言").assertIsDisplayed()
        compose.onNodeWithText("《合成书籍》").assertIsDisplayed()
        compose.onNodeWithText("原文[zip:literal]书摘").assertIsDisplayed()
        compose.onNodeWithText("\n结尾附言").assertIsDisplayed()
        compose.onNodeWithText(song).assertDoesNotExist()
        compose.onNodeWithText(excerpt).assertDoesNotExist()
    }

    @Test fun malformedCardIsPassedToOrdinaryTextRendererUnchanged() {
        val text = "附言[共度·歌曲]\n{broken}\n原话"
        compose.setContent { MaterialTheme { GongduShareMessageContent(text, null, true) { Text(it) } } }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithContentDescription("播放这首歌").assertDoesNotExist()
    }
}
