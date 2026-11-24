package me.rerere.rikkahub.plugin.loader

import android.app.Application
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginBinaryResponseTest {
    private fun fetch(bytes: ByteArray, options: String): JSONObject {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), client)
        sandbox.allowedHosts = listOf("create.suanbohe.com")
        val method = PluginSandbox::class.java.getDeclaredMethod("nativeFetch", String::class.java, String::class.java)
        method.isAccessible = true
        return JSONObject(method.invoke(sandbox, "https://create.suanbohe.com/api/ai/encode-vibe", options) as String)
    }
    @Test fun opaqueBinaryPreservedWithoutUtf8Conversion() {
        val bytes = byteArrayOf(0, -1, -128, 127, 10, 13, 42)
        val result = fetch(bytes, """{"responseEncoding":"base64","retry":false}""")
        assertTrue(result.getBoolean("success"))
        assertEquals("base64", result.getString("bodyEncoding"))
        assertArrayEquals(bytes, Base64.decode(result.getString("body"), Base64.DEFAULT))
    }
    @Test fun textResponseDefaultPreserved() {
        val result = fetch("{\"ok\":true}".toByteArray(), "{}")
        assertEquals("text", result.getString("bodyEncoding"))
        assertEquals("{\"ok\":true}", result.getString("body"))
    }
    @Test fun oversizedBinaryRejected() {
        assertFalse(fetch(ByteArray(12000001), """{"responseEncoding":"base64"}""").getBoolean("success"))
    }
    @Test fun base64RequestBodyUploadsExactBytes() {
        val sent = byteArrayOf(0, -1, -128, 127, 10, 13, 42)
        var received: ByteArray? = null
        var contentType: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body!!.writeTo(buffer)
            received = buffer.readByteArray()
            contentType = chain.request().header("Content-Type")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toByteArray().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), client)
        sandbox.allowedHosts = listOf("create.suanbohe.com")
        val method = PluginSandbox::class.java.getDeclaredMethod("nativeFetch", String::class.java, String::class.java)
        method.isAccessible = true
        val body = Base64.encodeToString(sent, Base64.NO_WRAP)
        for (verb in listOf("POST", "PUT", "DELETE")) {
            received = null
            val options = """{"method":"$verb","bodyEncoding":"base64","headers":{"Content-Type":"image/png"},"body":"$body"}"""
            assertTrue(JSONObject(method.invoke(sandbox, "https://create.suanbohe.com/upload", options) as String).getBoolean("success"))
            assertArrayEquals(sent, received)
            assertEquals("image/png", contentType)
        }
    }

    @Test fun malformedAndOversizedUploadsNeverReachNetwork() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("Unexpected network request") }.build()
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), client)
        sandbox.allowedHosts = listOf("create.suanbohe.com")
        val method = PluginSandbox::class.java.getDeclaredMethod("nativeFetch", String::class.java, String::class.java)
        method.isAccessible = true
        for (body in listOf("a", "A".repeat(16_000_001))) {
            val options = """{"method":"POST","bodyEncoding":"base64","body":"$body"}"""
            assertFalse(JSONObject(method.invoke(sandbox, "https://create.suanbohe.com/upload", options) as String).getBoolean("success"))
        }
        assertEquals(0, requests)
    }

    @Test fun nativeFetchPreservesPatchAndHeadAndRejectsUnsupportedMethods() {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("ETag", "v2").body("".toResponseBody()).build()
        }.build()
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), client)
        val method = PluginSandbox::class.java.getDeclaredMethod("nativeFetch", String::class.java, String::class.java)
        method.isAccessible = true
        fun fetch(options: JSONObject): JSONObject =
            JSONObject(method.invoke(sandbox, "https://gallery.example/photos/1", options.toString()) as String)

        val body = """{"title":"B"}"""
        val patch = JSONObject().put("method", "PATCH").put("body", body)
            .put("headers", JSONObject().put("Content-Type", "application/json"))
        assertTrue(fetch(patch).getBoolean("success"))
        val sent = requests.single()
        assertEquals("PATCH", sent.method)
        assertEquals("application/json", sent.header("Content-Type"))
        val buffer = okio.Buffer()
        requireNotNull(sent.body).writeTo(buffer)
        assertEquals(body, buffer.readUtf8())

        val head = fetch(JSONObject().put("method", "HEAD"))
        assertTrue(head.getBoolean("success"))
        assertEquals("HEAD", requests.last().method)
        assertNull(requests.last().body)
        assertEquals("", head.getString("body"))
        assertEquals("v2", head.getJSONObject("headers").getString("ETag"))

        val unsupported = fetch(JSONObject().put("method", "OPTIONS"))
        assertFalse(unsupported.getBoolean("success"))
        assertTrue(unsupported.getString("error").contains("OPTIONS"))
        val invalid = fetch(JSONObject().put("method", JSONObject.NULL))
        assertFalse(invalid.getBoolean("success"))
        assertTrue(invalid.getString("error").contains("method"))
        assertEquals(2, requests.size)
    }
}
