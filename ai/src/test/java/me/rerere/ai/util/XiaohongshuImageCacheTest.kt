package me.rerere.ai.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XiaohongshuImageCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://sns-webpic-qc.xhscdn.com/signed/image!nd_dft_wlteh_webp_3"

    private fun client(reply: (okhttp3.Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
    }.build()

    @Test fun `signed image is downloaded once and cached across restart after link expires`() {
        var calls = 0
        val directory = temporary.newFolder()
        val http = client { request ->
            calls++
            assertEquals("https://www.xiaohongshu.com/", request.header("Referer"))
            assertTrue(request.header("User-Agent")!!.contains("Mozilla"))
            assertNull(request.header("Authorization"))
            200 to "image bytes"
        }
        val cache = XiaohongshuImageCache(directory, http)
        assertEquals("image bytes", cache.useImage(url) { it.readText() })
        assertEquals("image bytes", cache.useImage(url) { it.readText() })
        val restarted = XiaohongshuImageCache(directory, client { calls++; 403 to "expired" })
        assertEquals("image bytes", restarted.useImage(url) { it.readText() })
        assertEquals(1, calls)
    }

    @Test fun `expired images are not cached and repeated history uses bounded retry cooldown`() {
        var calls = 0
        val directory = temporary.newFolder()
        val cache = XiaohongshuImageCache(directory, client { calls++; 403 to "expired" })
        repeat(2) { assertTrue(runCatching { cache.useImage(url) { it.readText() } }.isFailure) }
        assertEquals(1, calls)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(runCatching { cache.useImage(url + "?new-signature=1") { it.readText() } }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun `limits and invalid decoded payloads leave no committed or partial files`() {
        val directory = temporary.newFolder()
        val oversized = XiaohongshuImageCache(directory, client { 200 to "12345" }, maxImageBytes = 4)
        assertTrue(runCatching { oversized.useImage(url) { error("must not consume") } }.isFailure)
        val invalid = XiaohongshuImageCache(directory, client { 200 to "html instead of image" })
        assertTrue(runCatching { invalid.useImage(url) { error("decode failed") } }.isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `cache respects entry and byte limits`() {
        val directory = temporary.newFolder()
        val cache = XiaohongshuImageCache(directory, client { 200 to "1234" }, maxEntries = 2, maxCacheBytes = 6)
        repeat(4) { index -> assertEquals("1234", cache.useImage("$url?item=$index") { it.readText() }) }
        assertTrue(directory.listFiles()!!.size <= 2)
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 6)
    }

    @Test fun `CDN matching rejects lookalikes and validation rejects credentials and custom ports`() {
        assertTrue(isXiaohongshuImageUrl(url))
        assertFalse(isXiaohongshuImageUrl("https://xhscdn.com.attacker.example/image"))
        val cache = XiaohongshuImageCache(temporary.newFolder(), client { error("must not fetch") })
        for (invalid in listOf("https://xhscdn.com.attacker.example/image",
            "https://secret@xhscdn.com/image", "https://xhscdn.com:8443/image")) {
            assertTrue(runCatching { cache.useImage(invalid) { it.readText() } }.isFailure)
        }
    }

    @Test fun `redirects stay inside the approved CDN and HTTP input is upgraded`() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertTrue(chain.request().url.isHttps)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("synthetic")
                .header("Location", "http://127.0.0.1/private").body("".toResponseBody()).build()
        }.build()
        val cache = XiaohongshuImageCache(temporary.newFolder(), http)
        assertTrue(runCatching { cache.useImage(url.replace("https:", "http:")) { it.readText() } }.isFailure)
        assertEquals(1, calls)
    }
}
