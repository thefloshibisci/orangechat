package me.rerere.rikkahub.ui.hooks

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Disk-backed PCM/WAV recording; no transcription API or growing in-memory audio buffer. */
internal class DirectAudioRecorder(context: Context) : CustomAsrState {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ASRState(isAvailable = true))
    override val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var disposed = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
        .setOnAudioFocusChangeListener { if (it < 0) stop() }
        .build()

    @SuppressLint("MissingPermission") // ChatInput requests RECORD_AUDIO before start.
    override fun start(onTranscriptChange: (String) -> Unit) {
        if (disposed || job?.isActive == true) return
        stopRequested = false
        mutableState.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        job = scope.launch {
            var recorder: AudioRecord? = null
            var output: File? = null
            var completed = false
            try {
                check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "无法获得麦克风音频焦点，请结束其他通话后重试"
                }
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "设备不支持此录音格式" }
                val activeRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 4096))
                recorder = activeRecorder
                check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                val directory = File(context.filesDir, "voice_messages").apply { mkdirs() }
                output = File.createTempFile("voice_", ".wav", directory)
                var bytes = 0
                RandomAccessFile(output, "rw").use { file ->
                    file.write(wavHeader(0))
                    activeRecorder.startRecording()
                    check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
                    mutableState.value = ASRState(status = ASRStatus.Listening, isAvailable = true)
                    val buffer = ByteArray(4096)
                    while (!stopRequested && bytes < MAX_BYTES) {
                        ensureActive()
                        val read = activeRecorder.read(buffer, 0, minOf(buffer.size, MAX_BYTES - bytes),
                            AudioRecord.READ_NON_BLOCKING)
                        check(read >= 0) { "录音读取失败 ($read)" }
                        if (read == 0) { delay(10); continue }
                        file.write(buffer, 0, read)
                        bytes += read
                        var peak = 0
                        for (i in 0 until read - 1 step 2) {
                            val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort()
                            peak = maxOf(peak, abs(sample.toInt()))
                        }
                        mutableState.value = mutableState.value.copy(
                            durationMs = bytes * 1000L / (SAMPLE_RATE * 2),
                            amplitudes = (mutableState.value.amplitudes + peak / 32768f).takeLast(50),
                        )
                    }
                    mutableState.value = mutableState.value.copy(status = ASRStatus.Stopping)
                    check(bytes > 0) { "录音为空，请重新录制" }
                    file.seek(0)
                    file.write(wavHeader(bytes))
                }
                ensureActive()
                completed = true
                mutableState.value = mutableState.value.copy(status = ASRStatus.Idle,
                    audioFilePath = output.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = ASRState(status = ASRStatus.Error, isAvailable = true,
                    errorMessage = e.message ?: "录音失败")
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
                if (!completed) output?.delete()
            }
        }
    }

    override fun stop() { stopRequested = true }
    override fun cleanup() {
        disposed = true
        stopRequested = true
        scope.cancel()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MAX_BYTES = SAMPLE_RATE * 2 * 300 // Five minutes, below 10 MB before base64.
        internal fun wavHeader(dataBytes: Int): ByteArray = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
                putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            }.array()
    }
}
