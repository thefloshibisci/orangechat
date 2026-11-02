package me.rerere.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class AudioFileTranscriptionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private lateinit var audio: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        audio = temporary.newFile("sample.wav").apply {
            writeBytes("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(64))
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `SiliconFlow uploads existing file to exact configured endpoint without guessing parameters`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"  你好，世界。  "}"""))
        val provider = ASRProviderSetting.SiliconFlow(
            apiKey = "test-key", model = "user-chosen-model", language = "zh",
            baseUrl = server.url("/custom/audio/transcriptions").toString(),
        )
        assertEquals("你好，世界。", transcribeAudioFile(client, provider, audio))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/custom/audio/transcriptions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("user-chosen-model"))
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("RIFF"))
        // Current SiliconFlow file API documents file/model only; do not infer optional controls.
        assertFalse(body.contains("name=\"language\""))
        assertFalse(body.contains("name=\"response_format\""))
        assertTrue(audio.exists())
    }

    @Test
    fun `MiMo embeds WAV data url and configured language with api-key authentication`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"对白"}}]}"""))
        val provider = ASRProviderSetting.MiMo(
            apiKey = "mimo-key", baseUrl = server.url("/proxy/v1").toString(),
            model = "mimo-v2.5-asr", language = "zh",
        )
        assertEquals("对白", transcribeAudioFile(client, provider, audio))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/proxy/v1/chat/completions", request.path)
        assertEquals("mimo-key", request.getHeader("api-key"))
        assertEquals(null, request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("mimo-v2.5-asr", body["model"]!!.jsonPrimitive.content)
        val part = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
        val data = part["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertEquals("data:audio/wav;base64,${Base64.getEncoder().encodeToString(audio.readBytes())}", data)
        assertEquals("zh", body["asr_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenAI file route preserves custom proxy prefix and removes realtime query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"transcribed"}"""))
        val provider = ASRProviderSetting.OpenAIRealtime(
            apiKey = "test-key", model = "gpt-4o-mini-transcribe", language = "en", prompt = "Names: OrangeChat.",
            websocketUrl = server.url("/proxy/openai/v1/realtime?intent=transcription&model=unused").toString().replaceFirst("http:", "ws:"),
        )
        assertEquals("transcribed", transcribeAudioFile(client, provider, audio))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/proxy/openai/v1/audio/transcriptions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("gpt-4o-mini-transcribe"))
        assertTrue(body.contains("Names: OrangeChat."))
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("name=\"response_format\""))
    }

    @Test
    fun `unknown realtime path fails before any request`() = runBlocking {
        val provider = ASRProviderSetting.OpenAIRealtime(apiKey = "test-key", websocketUrl = server.url("/custom-ws").toString())
        val error = runCatching { transcribeAudioFile(client, provider, audio) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `Volcengine is explicitly unsupported without recording or uploading`() = runBlocking {
        val error = runCatching { transcribeAudioFile(client, ASRProviderSetting.Volcengine(apiKey = "key"), audio) }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals(0, server.requestCount)
        assertTrue(audio.exists())
    }

    @Test
    fun `HTTP errors do not leak response or credential and redirects do not forward uploads`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret-user-key audio-content"))
        val provider = ASRProviderSetting.SiliconFlow(apiKey = "secret-user-key", baseUrl = server.url("/asr").toString())
        val error = runCatching { transcribeAudioFile(client, provider, audio) }.exceptionOrNull()!!
        assertTrue(error is IOException)
        assertTrue(error.message!!.contains("401"))
        assertFalse(error.message!!.contains("secret-user-key"))
        assertFalse(error.message!!.contains("audio-content"))
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirect-target")))
        val redirectError = runCatching { transcribeAudioFile(client, provider, audio) }.exceptionOrNull()!!
        assertTrue(redirectError.message!!.contains("307"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `malformed or error payload is rejected while empty recognized speech is valid`() {
        listOf("not json", "{}", "[]", """{"text":null}""", """{"text":true}""", """{"code":400,"message":"sensitive"}""").forEach { payload ->
            assertTrue(runCatching { parseAudioFileTranscript(payload, false) }.exceptionOrNull() is IOException)
        }
        assertEquals("", parseAudioFileTranscript("""{"text":""}""", false))
        assertTrue(runCatching { parseAudioFileTranscript("""{"choices":[{"message":{"content":[]}}]}""", true) }.isFailure)
    }

    @Test
    fun `MiMo rejects oversize input before encoding or uploading`() = runBlocking {
        java.io.RandomAccessFile(audio, "rw").use { it.setLength(7_000_001) }
        val provider = ASRProviderSetting.MiMo(apiKey = "test-key", baseUrl = server.url("/v1").toString())
        assertTrue(runCatching { transcribeAudioFile(client, provider, audio) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancellation cancels underlying HTTP call rather than returning a transcript`() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody("""{"text":"too late"}"""))
        val provider = ASRProviderSetting.SiliconFlow(apiKey = "test-key", baseUrl = server.url("/asr").toString())
        var returned = false
        val job = launch(Dispatchers.Default) { transcribeAudioFile(client, provider, audio); returned = true }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        withTimeout(2_000) {
            job.cancelAndJoin()
            while (client.dispatcher.runningCallsCount() != 0) delay(10)
        }
        assertFalse(returned)
        assertTrue(job.isCancelled)
    }
}
