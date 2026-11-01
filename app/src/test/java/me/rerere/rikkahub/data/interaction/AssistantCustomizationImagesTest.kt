package me.rerere.rikkahub.data.interaction

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantCustomizationImagesTest {
    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also {
            it.compress(Bitmap.CompressFormat.PNG, 100, out)
            it.recycle()
        }
        out.toByteArray()
    }

    @Test fun `real uploaded and generated images are decoded before import`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = png()
        val uploaded = File(context.filesDir, "upload/style.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val generated = File(context.filesDir, "images/generated.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val saved = mutableListOf<String>()
        val loader = AssistantCustomizationImages(context, { file, mime ->
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("image/png", mime)
            saved += file.canonicalPath
            "file:///managed/${file.name}"
        })
        assertEquals("file:///managed/style.png", loader.importImage(uploaded.absolutePath))
        assertEquals("file:///managed/generated.png", loader.importImage(generated.toURI().toString()))
        assertEquals(listOf(uploaded.canonicalPath, generated.canonicalPath), saved)
    }

    @Test fun `private paths traversal corrupt files and unsupported URIs never import`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val privateFile = File(context.filesDir, "private.png").apply { writeBytes(png()) }
        val corrupt = File(context.filesDir, "upload/corrupt.png").apply { parentFile!!.mkdirs(); writeText("not a picture") }
        val loader = AssistantCustomizationImages(context, { _, _ -> error("must not import") })
        listOf(privateFile.absolutePath, "${context.filesDir}/upload/../private.png", corrupt.absolutePath,
            "content://other/private.png", "data:image/png;base64,AAA", "invented.png").forEach { source ->
            val error = runCatching { loader.importImage(source) }.exceptionOrNull()
            assertNotNull(source, error)
            assertNotEquals("must not import", error!!.message)
        }
    }

    @Test fun `remote download imports validated bytes and removes staging file`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = png()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        var staging: File? = null
        val loader = AssistantCustomizationImages(context, { file, mime ->
            staging = file
            assertEquals("image/png", mime)
            assertArrayEquals(bytes, file.readBytes())
            "file:///managed/downloaded.png"
        }, client)
        assertEquals("file:///managed/downloaded.png", loader.importImage("https://images.example/test.png"))
        assertNotNull(staging)
        assertFalse(staging!!.exists())
    }

    @Test fun `bad HTTP response nonimage and oversized download cannot overwrite settings`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        for ((code, bytes) in listOf(404 to png(), 200 to "<html>error</html>".toByteArray(),
            200 to ByteArray(12 * 1024 * 1024 + 1))) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .body(bytes.toResponseBody()).build()
            }.build()
            val loader = AssistantCustomizationImages(context, { _, _ -> error("must not import") }, client)
            val error = runCatching { loader.importImage("https://images.example/test.png") }.exceptionOrNull()
            assertNotNull(error)
            assertNotEquals("must not import", error!!.message)
        }
    }
}
