package me.rerere.rikkahub.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun movingBackgroundRepaintsActualFragmentWithoutRecomposingText() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(android.graphics.Color.RED)
            drawRect(100f, 0f, 200f, 100f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        }
        val geometry = LiveBubbleBlurGeometry().apply { originInWindow = Offset.Zero; sizePx = Size(200f, 100f) }
        val context = LiveBubbleBlurContext(imageBitmap = source.asImageBitmap(), geometry = geometry,
            baseColor = Color.White, imageAlpha = 1f)
        var compositions = 0
        compose.setContent {
            SideEffect { compositions++ }
            Box(Modifier.size(40.dp).testTag("fragment").drawBehind {
                drawLiveBackgroundFragment(context, layerOriginInWindow = Offset.Zero)
            })
        }
        val before = centerPixel()
        val initialCompositions = compositions
        compose.runOnIdle { geometry.originInWindow = Offset(-100f, 0f) }
        compose.waitForIdle()
        val after = centerPixel()
        assertEquals(android.graphics.Color.RED, before)
        assertEquals(android.graphics.Color.BLUE, after)
        assertEquals(initialCompositions, compositions)
        source.recycle()
    }

    private fun centerPixel(): Int {
        val bounds = compose.onNodeWithTag("fragment").fetchSemanticsNode().boundsInWindow
        var pixel = 0
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            val origin = IntArray(2).also(decor::getLocationInWindow)
            pixel = bitmap.getPixel((bounds.center.x - origin[0]).toInt(), (bounds.center.y - origin[1]).toInt())
            bitmap.recycle()
        }
        return pixel
    }
}
