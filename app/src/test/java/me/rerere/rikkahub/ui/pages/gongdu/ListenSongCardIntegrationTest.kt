package me.rerere.rikkahub.ui.pages.gongdu

import android.graphics.Bitmap
import android.graphics.Canvas
import me.rerere.rikkahub.ui.components.message.ListenSongCard
import me.rerere.rikkahub.data.listen.*
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import okhttp3.OkHttpClient
import java.io.File
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.listen.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],qualifiers="w375dp-h812dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListenSongCardIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun realFieldsFoldAndPlayIsDrivenByHostNotGuessed() {
        val lines = (1..8).map { "选词$it" }; var plays=0; var playing by mutableStateOf(false)
        compose.setContent { var expanded by remember { mutableStateOf(false) }; MaterialTheme {
            MusicGiftCard(null, remember { extractMusicPalette(null) }, lines, expanded, {expanded=!expanded}, {plays++},
                modifier=Modifier.width(335.dp).testTag("card"), songTitle="实际曲名", artist="实际歌手", message="",
                playing=playing, enabled=true, busy=false)
        } }
        compose.onNodeWithText("实际曲名").assertIsDisplayed(); compose.onNodeWithText("实际歌手").assertIsDisplayed()
        val height = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height / compose.activity.resources.displayMetrics.density
        assertTrue("folded height = $height dp", height <= 160f)
        compose.onNodeWithContentDescription("播放这首歌").performClick(); compose.runOnIdle {assertEquals(1,plays)}
        compose.onNodeWithContentDescription("暂停播放").assertDoesNotExist(); compose.runOnIdle {playing=true}
        compose.onNodeWithContentDescription("暂停播放").assertIsDisplayed()
        compose.onNodeWithTag("card-expand").performClick(); compose.onNodeWithText("查看全部 8 句").performClick()
        compose.onNodeWithText(lines.last()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("card-expand").performClick(); compose.onNodeWithText(lines.last()).assertDoesNotExist()
    }
    @Test fun chatCardReadsPayloadAndNeverPlaysAudioOnRenderOrWhenReadOnly() {
        val repository = ListenRepository(RuntimeEnvironment.getApplication(), OkHttpClient.Builder()
            .addInterceptor { error("Rendering a card must not request music") }.build())
        val isolated = koinApplication { modules(module { single<ListenRepository> { repository } }) }
        try {
            val share = ListenShare(ListenSong("23", "春日序曲", "林间电台"), "愿这首歌陪你慢慢走。", listOf("把这一刻，轻轻放进歌里", "沿着晚风走过安静街角"))
            var readOnly by mutableStateOf(true)
            compose.setContent { KoinIsolatedContext(isolated) {
                MaterialTheme { Box(Modifier.width(335.dp).testTag("real-card")) { ListenSongCard(share,"test-chat",readOnly) } }
            } }
            compose.onNodeWithText(share.song.title).assertIsDisplayed(); compose.onNodeWithTag("card-play").assertIsNotEnabled()
            compose.onNodeWithTag("card-expand").performClick(); compose.onNodeWithText(share.lines.last()).assertIsDisplayed()
            compose.onNodeWithText(share.message).assertIsDisplayed(); compose.waitForIdle()
            val png=File("build/reports/tests/gongdu-listen-screenshots/v11-real-chat.png"); png.parentFile.mkdirs()
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap)); png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            compose.runOnIdle { readOnly=false }; compose.onNodeWithTag("card-play").performClick()
            compose.onNodeWithText("请先在一起听房间连接音乐服务").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放这首歌").assertIsEnabled()
        } finally { isolated.close(); repository.close() }
    }

    @Test fun twoLineSongLeavesPaperBelowArtist() {
        compose.setContent { MaterialTheme {
            MusicGiftCard(null, remember { extractMusicPalette(null) }, emptyList(), false, {}, {},
                modifier = Modifier.width(325.dp).testTag("card"), songTitle = "Когда душе твоей больно",
                artist = "Ploho / 塞壬唱片-MSR", message = "", playing = false, enabled = true, busy = false)
        } }
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val artist = compose.onNodeWithText("Ploho / 塞壬唱片-MSR").fetchSemanticsNode().boundsInRoot
        assertTrue(card.bottom - artist.bottom >= 13.5f * compose.activity.resources.displayMetrics.density)
    }

    @Test fun readOnlyOrBusyDoesNotExposeFakePlaybackOrInventMissingLyrics() {
        var busy by mutableStateOf(false)
        compose.setContent { MaterialTheme {MusicGiftCard(null,remember { extractMusicPalette(null) }, emptyList(),false,{},{error("read-only")},
            songTitle="独立单曲",artist="歌手",message="只有留言",playing=false,enabled=busy,busy=busy)} }
        compose.onNodeWithTag("card-play").assertIsNotEnabled(); compose.onNodeWithTag("card-expand").assertDoesNotExist()
        compose.onNodeWithText("只有留言").assertIsDisplayed(); compose.runOnIdle {busy=true}
        compose.onNodeWithContentDescription("正在准备播放").assertIsNotEnabled()
    }
}
