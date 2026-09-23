package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ToolImageFileInstrumentedTest {
    @Test
    fun localToolImagesEncodeAsRealJpegWithEitherFileUriFormat() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("tool image ", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (url in listOf(Uri.fromFile(file).toString(), file.toURI().toString())) {
                val image = UIMessagePart.Image(url)
                val raw = image.encodeBase64(false).getOrThrow()
                val prefixed = image.encodeBase64().getOrThrow()
                assertEquals("image/jpeg", raw.mimeType)
                assertEquals("data:image/jpeg;base64,${raw.base64}", prefixed.base64)
                val bytes = Base64.decode(raw.base64, Base64.NO_WRAP)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(decoded)
                assertEquals(24, decoded.width)
                assertEquals(16, decoded.height)
                decoded.recycle()
            }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }
}
