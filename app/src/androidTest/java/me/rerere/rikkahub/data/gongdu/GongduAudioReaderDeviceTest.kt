package me.rerere.rikkahub.data.gongdu

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Real Android codec/muxer integration; JVM/Robolectric checks cannot verify this path. */
@RunWith(AndroidJUnit4::class)
class GongduAudioReaderDeviceTest {
    private lateinit var directory: File
    private lateinit var encodedAudio: File

    @Before
    fun createFixture() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        directory = File(cache, "gongdu-codec-test-${UUID.randomUUID()}")
        check(directory.mkdir()) { "Could not create codec fixture directory" }
        encodedAudio = File(directory, "stereo-48k-440hz.m4a")
        encodeSineWave(encodedAudio)
    }

    @After
    fun removeFixtures() {
        if (::directory.isInitialized) {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test(timeout = 30_000)
    fun decodesStereo48kAacToMono16kWavWithoutChangingDurationOrPitch() = runBlocking(Dispatchers.IO) {
        val wav = File(directory, "full.wav")
        assertTrue("An AAC audio track must be found", GongduAudioReader.extract(encodedAudio, wav, 0, 1_000))
        val samples = readPcmWav(wav)
        // AAC-LC may include one encoder priming frame; do not require sample-exact container padding.
        assertEquals("Full soundtrack duration in milliseconds", 1_000.0, durationMs(samples), 65.0)
        assertSinePitch(samples)
    }

    @Test(timeout = 30_000)
    fun crops250To750MillisecondsUsingTheDecodedPresentationTimeline() = runBlocking(Dispatchers.IO) {
        val wav = File(directory, "window.wav")
        assertTrue("An AAC audio track must be found", GongduAudioReader.extract(encodedAudio, wav, 250, 750))
        val samples = readPcmWav(wav)
        assertEquals("Selected soundtrack window duration in milliseconds", 500.0, durationMs(samples), 35.0)
        assertSinePitch(samples)
    }

    private fun readPcmWav(file: File): ShortArray {
        val bytes = file.readBytes()
        assertTrue("A WAV header and PCM samples must be present", bytes.size > 44)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("PCM format", 1, data.getShort(20).toInt())
        assertEquals("Mono channels", 1, data.getShort(22).toInt())
        assertEquals("Output sample rate", 16_000, data.getInt(24))
        assertEquals("PCM16 byte rate", 32_000, data.getInt(28))
        assertEquals("PCM16 block alignment", 2, data.getShort(32).toInt())
        assertEquals("PCM16 bits per sample", 16, data.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals("RIFF length", bytes.size - 8, data.getInt(4))
        assertEquals("PCM data length", bytes.size - 44, data.getInt(40))
        assertEquals("Whole PCM16 samples", 0, (bytes.size - 44) % 2)
        data.position(44)
        return ShortArray((bytes.size - 44) / 2) { data.short }
    }

    private fun durationMs(samples: ShortArray): Double = samples.size * 1_000.0 / 16_000

    private fun assertSinePitch(samples: ShortArray) {
        // Ignore codec priming/end padding when measuring the steady-state zero crossings.
        val start = samples.size / 10
        val end = samples.size * 9 / 10
        val peak = (start until end).maxOf { abs(samples[it].toInt()) }
        assertTrue("Decoded tone must have a measurable amplitude", peak > 3_000)
        val crossings = (start + 1 until end).filter { samples[it - 1] <= 0 && samples[it] > 0 }
        assertTrue("Enough periods must remain after cropping", crossings.size > 20)
        val frequency = (crossings.size - 1) * 16_000.0 / (crossings.last() - crossings.first())
        assertEquals("Resampling must retain the 440 Hz pitch", 440.0, frequency, 8.0)
    }

    private fun encodeSineWave(output: File) {
        val rate = 48_000
        val channels = 2
        val totalFrames = rate // Exactly one second of PCM source.
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var codecStarted = false
        var muxerStarted = false
        var muxerTrack = -1
        var sentFrames = 0
        var inputEos = false
        var outputEos = false
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 20_000
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codecStarted = true
            while (!outputEos) {
                check(SystemClock.elapsedRealtime() < deadline) { "AAC fixture encoding timed out" }
                if (!inputEos) {
                    val slot = encoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(slot)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.clear()
                        val ptsUs = sentFrames * 1_000_000L / rate
                        if (sentFrames == totalFrames) {
                            encoder.queueInputBuffer(slot, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            val frames = min(1_024, min(totalFrames - sentFrames, buffer.remaining() / (channels * 2)))
                            check(frames > 0) { "AAC encoder returned an unusable input buffer" }
                            repeat(frames) { frame ->
                                val value = (sin(2 * PI * 440 * (sentFrames + frame) / rate) * 20_000).toInt().toShort()
                                repeat(channels) { buffer.putShort(value) }
                            }
                            encoder.queueInputBuffer(slot, 0, frames * channels * 2, ptsUs, 0)
                            sentFrames += frames
                        }
                    }
                }
                when (val slot = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC encoder changed format after muxing began" }
                        muxerTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (slot >= 0) {
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                check(muxerStarted) { "AAC output arrived before its format" }
                                val buffer = checkNotNull(encoder.getOutputBuffer(slot))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, buffer, info)
                            }
                            outputEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            encoder.releaseOutputBuffer(slot, false)
                        }
                    }
                }
            }
            check(muxerStarted && output.length() > 0) { "AAC fixture did not produce a media track" }
        } finally {
            if (codecStarted) runCatching { encoder.stop() }
            encoder.release()
            try {
                if (muxerStarted) muxer.stop()
            } finally {
                muxer.release()
            }
        }
        check(output.length() > 0) { "AAC fixture file is empty" }
    }
}
