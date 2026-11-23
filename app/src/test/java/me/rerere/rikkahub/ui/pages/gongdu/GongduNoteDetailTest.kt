package me.rerere.rikkahub.ui.pages.gongdu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.gongdu.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class GongduNoteDetailTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var loader: ImageLoader
    @Before fun localImageLoader() {
        // Synthetic fixture, never download private platform content during unit tests.
        val bitmap = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 720f, 900f,
            intArrayOf(0xFFD8F0EE.toInt(), 0xFF77B7C7.toInt(), 0xFF316679.toInt()), null, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, 720f, 900f, paint)
        paint.shader = null; paint.color = 0xFFEBF8EB.toInt()
        canvas.drawCircle(520f, 190f, 92f, paint)
        paint.color = 0x8053858B.toInt()
        canvas.drawOval(-180f, 470f, 820f, 1100f, paint)
        paint.color = 0x80E8F3ED.toInt()
        canvas.drawOval(-160f, 620f, 1100f, 1200f, paint)
        loader = ImageLoader.Builder(compose.activity).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY)
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }
    @After fun cleanup() { loader.shutdown(); SingletonImageLoader.reset() }

    private fun note(count: Int = 9) = GongduCodec.note(buildJsonObject { put("note", buildJsonObject {
        put("title", "把周末留给海边"); put("desc", "沿着海边慢慢走，找一家能看见日落的小店。\n#周末散步[话题]#")
        put("user", buildJsonObject { put("nickname", "小雨") })
        put("interactInfo", buildJsonObject { put("likedCount", "128"); put("collectedCount", "36") })
        put("imageList", buildJsonArray { repeat(count) { index -> add(buildJsonObject {
            put("urlDefault", "https://sns.xhscdn.com/test-$index.jpg"); put("width", 720); put("height", 900)
        }) } })
    }) }, GongduFeed("synthetic-note", "标题", "test-token"))

    @Test fun galleryCanReadBeyondSixButShareRemainsVisibleAndActsOnce() {
        var shared = 0; var original = 0
        compose.setContent { MaterialTheme { GongduNoteDetail(note(), false, null, {}, { original++ }, { shared++ }) } }
        compose.onNodeWithContentDescription("笔记配图 1").assertIsDisplayed()
        repeat(6) { compose.onNodeWithTag("gongdu-note-gallery").performTouchInput { swipeLeft() }; compose.waitForIdle() }
        compose.onNodeWithText("7 / 9").assertIsDisplayed()
        compose.onNodeWithContentDescription("笔记配图 7").assertIsDisplayed()
        compose.onNodeWithText("和机聊这篇").assertIsDisplayed().performClick()
        compose.onNodeWithText("打开原文").performClick()
        assertEquals(1, shared); assertEquals(1, original)
        snapshot("detail-light")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    fun compactDarkReaderKeepsActionsAndErrorsVisible() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            GongduNoteDetail(note(), true, "分享失败，请重试", {}, {}, {})
        } }
        compose.onNodeWithText("正在送到聊天…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭笔记").assertIsNotEnabled()
        compose.onNodeWithText("分享失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("打开原文").assertIsDisplayed()
        snapshot("detail-dark-compact")
    }

    @Test fun textOnlyNoteHasHonestEmptyStateAndWorkingShare() {
        compose.setContent { MaterialTheme { GongduNoteDetail(note(0), false, null, {}, {}, {}) } }
        compose.onNodeWithText("服务未提供可显示的配图，可打开原文查看").assertIsDisplayed()
        compose.onNodeWithText("和机聊这篇").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun waterGlassSearchRendersAtPhoneWidth() {
        compose.setContent { MaterialTheme { GongduWaterBackdrop { Column {
            RoomHeader("一起逛", "小红书 · 一起看，也一起聊", true, {})
            RoomTabs(listOf("推荐", "搜索", "消息", "设置"), 1, {}, Modifier.padding(16.dp))
            FeedGrid(emptyList(), false, { SearchHeader("", {}, false, {}) }, "", {})
        } } } }
        compose.onNodeWithText("想和机一起看什么？").assertIsDisplayed()
        snapshot("search-glass")
    }

    @Test fun darkReaderBodyRemainsReadableAfterScrolling() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            GongduNoteDetail(note(), false, null, {}, {}, {})
        } }
        compose.onNodeWithTag("gongdu-note-body").performScrollToIndex(1)
        val text = compose.onNodeWithText("把周末留给海边")
        text.assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val foreground = layouts.single().layoutInput.style.color
        val background = androidx.compose.ui.graphics.Color(0xFF302B26)
        val contrast = (foreground.luminance() + .05f) / (background.luminance() + .05f)
        assertTrue("Dark reader text must retain at least 4.5:1 contrast", contrast >= 4.5f)
        assertEquals(1f, foreground.alpha, .001f)
        compose.onNodeWithText("和机聊这篇").assertIsDisplayed()
        snapshot("detail-dark-body")
    }

    private fun snapshot(name: String) {
        val output = File("build/reports/tests/gongdu-reader-screenshots/$name.png")
        output.parentFile?.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
