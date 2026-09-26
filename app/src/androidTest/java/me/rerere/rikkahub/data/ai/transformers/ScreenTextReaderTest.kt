package me.rerere.rikkahub.data.ai.transformers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTextReaderTest {
    @Test fun bundledRecognizerReadsSyntheticTextAndReleasesBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(800, 240, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("屏幕文字 OCR 12345", 30f, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 64f
            })
        }
        val text = withTimeout(30_000) { recognizeScreenBitmap(bitmap) }
        assertTrue(text.contains("12345"))
        assertTrue(bitmap.isRecycled)
    }
}
