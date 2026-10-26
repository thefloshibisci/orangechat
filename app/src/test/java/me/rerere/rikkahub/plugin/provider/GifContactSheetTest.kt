package me.rerere.rikkahub.plugin.provider

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import me.rerere.ai.util.GifContactSheet
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifContactSheetTest {
    private fun gif() = Base64.decode("R0lGODlhBAAEAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAEAAAICQABCBxIsCCAgAAh+QQBCgABACwAAAAABAAEAIEAAP8AAAAAAAAAAAAICQABCBxIsCCAgAA7", Base64.DEFAULT)

    // Robolectric 4.16.1 has no Movie shadow/native binding. Frame sampling is
    // asserted in PluginImageResultsTest on Android; here exercise its still fallback.
    @Test fun `GIF still fallback and upload produce bounded PNG`() {
        val sheet = GifContactSheet.render(gif())
        val bitmap = BitmapFactory.decodeByteArray(sheet.png, 0, sheet.png.size)
        try {
            assertEquals(Color.RED, bitmap.getPixel(1, 1))
            assertTrue(bitmap.width <= 728 && bitmap.height <= 728)
        } finally { bitmap.recycle() }
        val file = File.createTempFile("gif-upload", ".gif")
        try {
            file.writeBytes(gif())
            val encoded = UIMessagePart.Image(file.toURI().toString()).encodeBase64().getOrThrow()
            assertEquals("image/png", encoded.mimeType)
            assertTrue(encoded.base64.startsWith("data:image/png;base64,"))
        } finally { file.delete() }
    }

    @Test fun `oversized logical screen rejected before native decode and never falls back to raw GIF`() {
        val oversized = gif().apply { this[6] = 0xff.toByte(); this[7] = 0x7f; this[8] = 0xff.toByte(); this[9] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { GifContactSheet.render(oversized) }
        assertThrows(IllegalArgumentException::class.java) { GifContactSheet.render(ByteArray(GifContactSheet.MAX_INPUT_BYTES + 1)) }
        val file = File.createTempFile("gif-oversized", ".gif")
        try {
            file.writeBytes(oversized)
            assertTrue(UIMessagePart.Image(file.toURI().toString()).encodeBase64().isFailure)
        } finally { file.delete() }
    }
}
