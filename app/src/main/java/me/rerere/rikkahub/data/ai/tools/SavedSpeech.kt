package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.controller.TextChunker
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.SpeechMarkup
import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val GENERATED_SPEECH = "orangechat_generated_speech"
private const val DURATION_MS = "duration_ms"
private const val TRANSCRIPT = "transcript"
fun UIMessagePart.Audio.isGeneratedSpeech() = metadata?.get(GENERATED_SPEECH) == JsonPrimitive(true)
fun UIMessagePart.Audio.speechDurationMs(): Long =
    (metadata?.get(DURATION_MS) as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
fun UIMessagePart.Audio.speechTranscript(): String = (metadata?.get(TRANSCRIPT) as? JsonPrimitive)?.content.orEmpty()

/** Existing Audio attachments participate in backup, deletion and relocation. No private cache paths in JSON text. */
internal class SavedSpeechGenerator(context: Context, private val files: FilesManager) {
    private val synthesizer = TtsSynthesizer(TTSManager(context.applicationContext))

    suspend fun generate(provider: TTSProviderSetting, text: String, style: SpeechStyle): List<UIMessagePart.Audio> =
        generateSavedSpeech(provider, text, style,
            synthesize = { synthesizer.synthesize(provider, it) },
            persist = { response, transcript -> withContext(Dispatchers.IO) {
                val (bytes, format) = speechFileBytes(response)
                val extension = format.name.lowercase()
                val mime = when (format) {
                    AudioFormat.MP3 -> "audio/mpeg"
                    AudioFormat.WAV -> "audio/wav"
                    AudioFormat.OGG, AudioFormat.OPUS -> "audio/ogg"
                    else -> "audio/$extension"
                }
                val saved = files.saveManagedFromBytes(FileFolders.UPLOAD, bytes, "voice.$extension", mime)
                val file = files.getFile(saved)
                val retriever = MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (_: Exception) { 0L } finally { retriever.release() }
                UIMessagePart.Audio(file.toUri().toString(), buildJsonObject {
                    put(GENERATED_SPEECH, true); put(DURATION_MS, duration.coerceAtLeast(0)); put(TRANSCRIPT, transcript)
                })
            } },
        )
}

/** Synthesize once on send, serially. A saved partial result is retained if a later chunk fails. */
internal suspend fun generateSavedSpeech(
    provider: TTSProviderSetting,
    text: String,
    style: SpeechStyle,
    synthesize: suspend (TtsChunk) -> TTSResponse,
    persist: suspend (TTSResponse, String) -> UIMessagePart.Audio,
): List<UIMessagePart.Audio> {
    val prepared = SpeechMarkup.prepare(text) { it.stripMarkdown() }
    // ElevenLabs receives the whole utterance so a tag keeps its scope across sentence boundaries.
    val chunks = if (provider is TTSProviderSetting.ElevenLabs) listOf(TtsChunk(index = 0, text = SpeechMarkup.plainText(prepared)))
        else TextChunker(maxChunkLength = 160).splitSpeech(prepared)
    val result = mutableListOf<UIMessagePart.Audio>()
    try { for (chunk in chunks) {
        val merged = chunk.style.copy(
            emotion = chunk.style.emotion ?: style.emotion, speed = chunk.style.speed ?: style.speed,
            pitch = chunk.style.pitch ?: style.pitch, volume = chunk.style.volume ?: style.volume,
            stability = style.stability, similarityBoost = style.similarityBoost,
        )
        val response = synthesize(chunk.copy(style = merged))
        check(response.audioData.isNotEmpty()) { "语音服务返回了空音频" }
        result += persist(response, SpeechMarkup.withoutElevenLabsTags(SpeechMarkup.transcript(chunk.text)))
    } } catch (e: CancellationException) { throw e }
    catch (e: Exception) { throw SavedSpeechFailure(result.toList(), e) }
    return result
}

internal class SavedSpeechFailure(val saved: List<UIMessagePart.Audio>, cause: Exception) :
    Exception(cause.message, cause)

/** Raw providers return mono PCM16. Persist a WAV header so future playback needs no provider metadata. */
internal fun speechFileBytes(response: TTSResponse): Pair<ByteArray, AudioFormat> {
    if (response.format != AudioFormat.PCM) return response.audioData to response.format
    val rate = response.sampleRate ?: 24000
    require(rate > 0 && response.audioData.size % 2 == 0) { "Invalid PCM audio" }
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + response.audioData.size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(response.audioData.size)
    }.array()
    return (header + response.audioData) to AudioFormat.WAV
}
