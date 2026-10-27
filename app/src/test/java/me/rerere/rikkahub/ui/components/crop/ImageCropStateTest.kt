package me.rerere.rikkahub.ui.components.crop

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import me.rerere.rikkahub.ui.components.ai.cropEncoding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCropStateTest {
    private fun state(width: Int = 400, height: Int = 200, ratio: Float? = null): ImageCropState {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ImageCropState(bitmap, bitmap.asImageBitmap(), ratio).also {
            it.layout(Rect(20f, 20f, 620f, 620f), 40f)
        }
    }

    @Test fun `pan and zoom never uncover empty space inside crop frame`() {
        val state = state()
        state.selectAspect(CropAspect.Ratio(1, 1))
        state.transform(Offset(300f, 300f), Offset(10000f, -10000f), 3f)
        assertTrue(state.imageRect.left <= state.frame.left + .001f)
        assertTrue(state.imageRect.top <= state.frame.top + .001f)
        assertTrue(state.imageRect.right >= state.frame.right - .001f)
        assertTrue(state.imageRect.bottom >= state.frame.bottom - .001f)
        state.transform(state.frame.center, Offset.Zero, .0001f)
        assertTrue(state.imageRect.width >= state.frame.width - .001f)
        assertTrue(state.imageRect.height >= state.frame.height - .001f)
    }

    @Test fun `free crop resizes edges while fixed ratio preserves aspect and reset`() {
        val state = state()
        val initial = state.frame
        state.resize(CropHandle.Left, initial, Offset(50f, 0f))
        assertEquals(initial.left + 50f, state.frame.left, .001f)
        state.selectAspect(CropAspect.Ratio(4, 3))
        state.resize(CropHandle.BottomRight, state.frame, Offset(-100f, -80f))
        assertEquals(4f / 3f, state.frame.width / state.frame.height, .001f)
        state.rotate()
        assertEquals(1, state.quarterTurns)
        state.reset()
        assertEquals(0, state.quarterTurns)
        assertEquals(CropAspect.Free, state.aspect)
        assertFalse(state.modified)
        assertEquals(initial, state.frame)
    }

    @Test fun `rotation produces correct dimensions and source pixels`() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        bitmap.setPixel(0, 0, Color.BLUE)
        val state = ImageCropState(bitmap, bitmap.asImageBitmap(), null)
        state.layout(Rect(0f, 0f, 400f, 400f), 1f)
        state.rotate()
        val result = state.crop(4096)
        assertEquals(2, result.width)
        assertEquals(4, result.height)
        assertEquals(Color.BLUE, result.getPixel(1, 0))
        val limited = state.crop(2)
        assertTrue(maxOf(limited.width, limited.height) <= 2)
    }

    @Test fun `all quarter turns map selected region back to original source`() {
        val rect = Rect(10f, 20f, 50f, 70f)
        assertEquals(rect, rotatedRectToSource(rect, 0, 100f, 200f))
        assertEquals(Rect(20f, 150f, 70f, 190f), rotatedRectToSource(rect, 1, 100f, 200f))
        assertEquals(Rect(50f, 130f, 90f, 180f), rotatedRectToSource(rect, 2, 100f, 200f))
        assertEquals(Rect(30f, 10f, 80f, 50f), rotatedRectToSource(rect, 3, 100f, 200f))
    }

    @Test fun `transparent crop uses png and opaque crop uses matching jpeg extension`() {
        assertEquals(".png", cropEncoding(true).extension)
        assertEquals(Bitmap.CompressFormat.PNG, cropEncoding(true).format)
        assertEquals(".jpg", cropEncoding(false).extension)
        assertEquals(Bitmap.CompressFormat.JPEG, cropEncoding(false).format)
    }
}
