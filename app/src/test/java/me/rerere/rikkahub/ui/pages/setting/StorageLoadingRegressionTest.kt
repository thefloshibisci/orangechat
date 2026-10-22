package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.CachePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageLoadingRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun diskCountPublishesIntoLazySettingsCardAfterBackgroundAndReentry() {
        val appScope = AppScope()
        val dao = Proxy.newProxyInstance(ManagedFileDAO::class.java.classLoader,
            arrayOf(ManagedFileDAO::class.java)) { _, _, _ -> error("Disk count must not query DB") } as ManagedFileDAO
        val manager = FilesManager(compose.activity, FilesRepository(dao), appScope)
        val dir = File(compose.activity.filesDir, "upload").apply { mkdirs() }
        File(dir, "one.txt").writeText("one")
        val shown = mutableStateOf(true)
        val gate = CompletableDeferred<Unit>()
        try {
            compose.setContent { CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) { MaterialTheme { if (shown.value) {
                LazyColumn { item("dataSettings") {
                    val storage by produceState(-1 to 0L) {
                        gate.await()
                        value = manager.countChatFiles()
                    }
                    CardGroup { item(headlineContent = { Text("storage") },
                        supportingContent = { Text(if (storage.first < 0) "calculating" else "${storage.first}:${storage.second}") }) }
                } }
            } } } }
            compose.onNodeWithText("calculating").assertIsDisplayed()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            gate.complete(Unit)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("1:3").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("1:3").assertIsDisplayed()
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            File(dir, "two.txt").writeText("two")
            compose.runOnIdle { shown.value = true }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("2:6").fetchSemanticsNodes().isNotEmpty()
            }
        } finally { appScope.cancel() }
    }

    @Test fun actualFileCardLoadsAndDrawsLocalImageAfterBackgroundAndReentry() {
        val source = File(compose.activity.cacheDir, "preview.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            source.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val result = AtomicReference<ImageResult?>()
        val loader = ImageLoader.Builder(compose.activity).memoryCachePolicy(CachePolicy.DISABLED).components {
            add(object : Interceptor {
                override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                    chain.proceed().also { result.set(it) }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        val shown = mutableStateOf(true)
        val file = ManagedFileEntity(1, "upload", "upload/preview.png", "preview.png", "image/png", source.length(), 0, 0)
        try {
            compose.setContent { MaterialTheme { if (shown.value) {
                FileItem(file, source, {}, false, false, true, {})
            } } }
            compose.waitUntil(5_000) { result.get() != null }
            assertTrue(result.get().toString(), result.get() is SuccessResult)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            result.set(null)
            compose.runOnIdle { shown.value = true }
            compose.waitForIdle()
            compose.waitUntil(5_000) { result.get() != null }
            assertTrue(result.get().toString(), result.get() is SuccessResult)
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                var red = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    if (bitmap.getPixel(x, y) == Color.RED) red++
                }
                assertTrue("Decoded image must actually be drawn, red pixels=$red", red > 1000)
                bitmap.recycle()
            }
        } finally { loader.shutdown(); SingletonImageLoader.reset() }
    }
}
