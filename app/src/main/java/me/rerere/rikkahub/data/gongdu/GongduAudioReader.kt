package me.rerere.rikkahub.data.gongdu

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** Decode the selected original soundtrack, never microphone audio. Bounded mono 16 kHz PCM WAV. */
internal object GongduAudioReader {
    const val SAMPLE_RATE = GongduPcmResampler.SAMPLE_RATE
    const val MAX_PCM_BYTES = GongduPcmResampler.MAX_PCM_BYTES

    suspend fun extract(video: File, output: File, startMs: Long, endMs: Long): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(video.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8000..192000 && channels in 1..8) { "暂不支持此音轨格式" }
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var idle = 0
            RandomAccessFile(output, "rw").use { wav ->
                wav.setLength(0)
                wav.write(ByteArray(44))
                val resampler = GongduPcmResampler(startMs, endMs) { bytes, count -> wav.write(bytes, 0, count) }
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    check(++idle < 1000) { "音轨解码超时" }
                    if (!inputDone) {
                        val slot = decoder.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val buffer = checkNotNull(decoder.getInputBuffer(slot))
                            val bytes = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (bytes < 0 || time >= endMs * 1000) {
                                decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(slot, 0, bytes, time, 0)
                                extractor.advance()
                            }
                            idle = 0
                        }
                    }
                    when (val slot = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val actual = decoder.outputFormat
                            rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT
                            require(rate in 8000..192000 && channels in 1..8 &&
                                encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "暂不支持此音轨格式" }
                        }
                        else -> if (slot >= 0) {
                            try {
                                idle = 0
                                if (info.size > 0) {
                                    val buffer = checkNotNull(decoder.getOutputBuffer(slot)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    require(info.offset >= 0 && info.size <= buffer.capacity() - info.offset) { "音轨缓冲区无效" }
                                    buffer.clear()
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    resampler.appendBuffer(buffer, info.presentationTimeUs, rate, channels,
                                        encoding == AudioFormat.ENCODING_PCM_FLOAT)
                                }
                                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endMs * 1000
                            } finally { decoder.releaseOutputBuffer(slot, false) }
                        }
                    }
                }
                if (!resampler.finish()) return false
                wav.seek(0)
                wav.write(wavHeader(resampler.pcmBytesWritten))
            }
            return true
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    internal fun wavHeader(size: Int): ByteArray = java.nio.ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(size)
    }.array()
}
