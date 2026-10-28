package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.util.Base64
import java.io.File
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileEncoderCacheIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `cached JPEG remains decodable and prefix variants share the same encoded file`() {
        val directory = temporary.newFolder()
        RequestImageEncoding.configure(directory)
        val source = temporary.newFile("picture.png")
        writeImage(source, Bitmap.CompressFormat.PNG)
        val original = source.readBytes()
        val image = UIMessagePart.Image(source.toURI().toString())
        val first = image.encodeBase64().getOrThrow()
        val raw = image.encodeBase64(false).getOrThrow()
        assertEquals("image/jpeg", first.mimeType)
        assertEquals("data:image/jpeg;base64,${raw.base64}", first.base64)
        val bytes = Base64.decode(raw.base64, Base64.NO_WRAP)
        assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).let { decoded ->
            assertNotNull(decoded)
            assertEquals(80, decoded.width)
            assertEquals(40, decoded.height)
            decoded.recycle()
        }
        assertArrayEquals(original, source.readBytes())
        assertEquals(first, image.encodeBase64().getOrThrow())
    }

    @Test fun `EXIF rotation is normalized before caching without modifying the original`() {
        RequestImageEncoding.configure(temporary.newFolder())
        val source = temporary.newFile("rotated.jpg")
        writeImage(source, Bitmap.CompressFormat.JPEG)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val original = source.readBytes()
        val image = UIMessagePart.Image(source.toURI().toString())
        val encoded = image.encodeBase64(false).getOrThrow()
        val bytes = Base64.decode(encoded.base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).let { decoded ->
            assertNotNull(decoded)
            assertEquals(40, decoded.width)
            assertEquals(80, decoded.height)
            decoded.recycle()
        }
        assertEquals(encoded, image.encodeBase64(false).getOrThrow())
        assertArrayEquals(original, source.readBytes())
    }

    private fun writeImage(file: File, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.CYAN)
            file.outputStream().use { assertTrue(bitmap.compress(format, 85, it)) }
        } finally { bitmap.recycle() }
    }
}
