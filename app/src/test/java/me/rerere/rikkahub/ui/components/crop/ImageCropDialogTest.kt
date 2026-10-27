package me.rerere.rikkahub.ui.components.crop

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCropDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `native crop dialog exposes ratios rotation reset and produces rotated image`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "crop-dialog-input.png")
        Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 100, 160))
            for (y in 0 until 30) for (x in 0 until 30) setPixel(x, y, Color.RED)
        }
            .let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        var result: Bitmap? = null
        compose.setContent {
            MaterialTheme { ImageCropDialog(file.toUri(), onDismiss = {}, onConfirm = { result = it }) }
        }
        val confirm = context.getString(android.R.string.ok)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithContentDescription(confirm).assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("自由").assertIsDisplayed()
        compose.onNodeWithText("原图").assertIsDisplayed()
        compose.onNodeWithText("1:1").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("重置裁剪").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("顺时针旋转 90°").performClick()
        // Compose captureToImage waits for PixelCopy's forced redraw on a separate dialog window.
        // Robolectric's native graphics can instead render that actual window directly into a bitmap.
        lateinit var snapshot: Bitmap
        compose.runOnIdle {
            val decor = checkNotNull(ShadowDialog.getLatestDialog().window).decorView
            assertTrue("Crop dialog must have a laid out window", decor.width > 0 && decor.height > 0)
            snapshot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(snapshot))
        }
        val screenshot = File("build/test-screenshots/native-crop-dialog.png").apply { parentFile.mkdirs() }
        screenshot.outputStream().use { assertTrue(snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Screenshot PNG must be nonempty", screenshot.length() > 100)
        compose.onNodeWithContentDescription(confirm).performClick()
        compose.waitUntil(10_000) { result != null }
        assertEquals(240, result!!.width)
        assertEquals(120, result!!.height)
        assertEquals("Top-left source pixels rotate to the top-right", Color.RED, result!!.getPixel(239, 0))
        assertEquals("Decoded crop keeps the original image colors", Color.rgb(40, 100, 160), result!!.getPixel(120, 60))
        val pixels = IntArray(snapshot.width * snapshot.height)
        snapshot.getPixels(pixels, 0, snapshot.width, 0, 0, snapshot.width, snapshot.height)
        assertTrue("Screenshot must contain the loaded blue image", pixels.count {
            Color.alpha(it) > 240 && Color.red(it) in 34..46 &&
                Color.green(it) in 94..106 && Color.blue(it) in 154..166
        } > 50)
    }
}
