package me.rerere.ai.util

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEncoderImageTest {
    @Test
    fun `inline data strips prefix for raw base64 providers`() {
        val image = UIMessagePart.Image("data:image/png;base64,aGVsbG8=")
        assertEquals(EncodedImage("aGVsbG8=", "image/png"), image.encodeBase64(false).getOrThrow())
        assertEquals(EncodedImage(image.url, "image/png"), image.encodeBase64().getOrThrow())
    }

    @Test
    fun `inline data retains its mime type`() {
        for (type in listOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            val encoded = UIMessagePart.Image("data:$type;base64,aGVsbG8=").encodeBase64(false).getOrThrow()
            assertEquals(type, encoded.mimeType)
            assertEquals("aGVsbG8=", encoded.base64)
        }
    }

    @Test
    fun `malformed or empty inline data fails explicitly`() {
        for (url in listOf("data:image/png;base64,", "data:image/png;base64", "data:text/plain;base64,aA==", "data:image/png,hello")) {
            assertTrue(url, UIMessagePart.Image(url).encodeBase64(false).isFailure)
        }
    }

    @Test
    fun `single slash file uri reaches file loading`() {
        val error = UIMessagePart.Image("file:/missing-orangechat-image.png").encodeBase64().exceptionOrNull()
        assertTrue(error != null)
        assertFalse(error!!.message.orEmpty().contains("Unsupported URL format"))
    }
}
