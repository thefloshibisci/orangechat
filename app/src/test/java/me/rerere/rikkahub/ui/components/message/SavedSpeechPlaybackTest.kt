package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.GENERATED_SPEECH
import me.rerere.rikkahub.data.ai.tools.generateSavedSpeech
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.ElevenLabsTTSProvider
import okhttp3.MediaType.Companion.toMediaType
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
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SavedSpeechPlaybackTest {
    private class FakePlayer {
        var play = false
        var state = Player.STATE_IDLE
        var prepares = 0
        var replays = 0
        var released = false
        var items: List<MediaItem> = emptyList()
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayWhenReady", "isPlaying" -> play
                "getPlaybackState" -> state
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentMediaItemIndex" -> 0
                "getCurrentPosition" -> 0L
                "getPlayerError" -> null
                "setMediaItems" -> { @Suppress("UNCHECKED_CAST") val list = args!![0] as List<MediaItem>; items = list; null }
                "prepare" -> { prepares++; state = Player.STATE_READY; null }
                "play" -> { play = true; null }
                "pause" -> { play = false; null }
                "seekTo" -> { replays++; state = Player.STATE_READY; null }
                "release" -> { released = true; null }
                else -> null
            }
        } as Player
    }

    @Test fun `send then repeated playback pause replay and reopen makes exactly one synthesis request`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var requests = 0
        val provider = ElevenLabsTTSProvider(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("mp3_44100_128", chain.request().url.queryParameter("output_format"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1, 2, 3).toResponseBody("audio/mpeg".toMediaType())).build()
        }.build())
        val setting = TTSProviderSetting.ElevenLabs(modelId = "eleven_v4")
        val file = File(context.filesDir, "synthetic-saved-voice.mp3")
        val clips = generateSavedSpeech(setting, "[whispers]hello", SpeechStyle(), synthesize = { chunk ->
            val chunks = provider.generateSpeech(context, setting, TTSRequest(chunk.text, chunk.style)).toList()
            TTSResponse(chunks.single().data, AudioFormat.MP3)
        }, persist = { response, _ ->
            file.writeBytes(response.audioData)
            UIMessagePart.Audio(file.toURI().toString(), buildJsonObject { put(GENERATED_SPEECH, true); put("duration_ms", 19500) })
        })
        val fake = FakePlayer()
        val playback = SavedSpeechPlayer(context, clips) { fake.player }
        try {
            assertEquals(1, requests); assertEquals(0, fake.prepares); assertFalse(playback.state.value.playing)
            repeat(5) { playback.toggle(); playback.toggle() }
            assertEquals(1, fake.prepares); assertEquals(1, requests)
            fake.state = Player.STATE_ENDED; fake.play = false
            playback.toggle()
            assertEquals(1, fake.replays); assertEquals(1, requests)
        } finally { playback.close() }
        val reopened = SavedSpeechPlayer(context, clips) { FakePlayer().player }
        try { reopened.toggle(); assertEquals(1, requests) } finally { reopened.close() }
    }

    @Test fun `missing files do not request synthesis or create a player`() {
        var created = 0
        val player = SavedSpeechPlayer(RuntimeEnvironment.getApplication(), listOf(UIMessagePart.Audio("file:///not-existing-voice.mp3"))) {
            created++; FakePlayer().player
        }
        try {
            player.toggle(); player.toggle()
            assertEquals(0, created)
            assertTrue(player.state.value.error!!.contains("未重新生成"))
        } finally { player.close() }
    }

    @Test fun `duration uses complete file lengths including multiple chunks and unknown is explicit`() {
        fun clip(ms: Long) = UIMessagePart.Audio("file:///a", buildJsonObject { put("duration_ms", ms) })
        assertEquals("0:20", speechDurationLabel(totalSpeechDuration(listOf(clip(19500)))))
        assertEquals("0:20", speechDurationLabel(totalSpeechDuration(listOf(clip(8000), clip(12000)))))
        assertEquals("—:—", speechDurationLabel(totalSpeechDuration(listOf(clip(8000), clip(0)))))
    }
}
