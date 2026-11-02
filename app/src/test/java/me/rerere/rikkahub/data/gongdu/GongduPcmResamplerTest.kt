package me.rerere.rikkahub.data.gongdu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

class GongduPcmResamplerTest {
    private data class Rendered(val hasAudio: Boolean, val pcm: ShortArray)

    private fun render(startMs: Long, endMs: Long, input: (GongduPcmResampler) -> Unit): Rendered {
        val sink = ByteArrayOutputStream()
        val resampler = GongduPcmResampler(startMs, endMs) { bytes, count -> sink.write(bytes, 0, count) }
        input(resampler)
        val hasAudio = resampler.finish()
        val bytes = sink.toByteArray()
        val values = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(values)
        assertEquals(bytes.size, resampler.pcmBytesWritten)
        return Rendered(hasAudio, values)
    }

    private fun wave(index: Int, rate: Int) = sin(2 * PI * 440 * index / rate) * 10000

    @Test fun `8 kHz is resampled to a full second at 16 kHz without raising voice pitch`() {
        val result = render(0, 1000) { resampler ->
            repeat(8000) { i -> resampler.append(wave(i, 8000), i * 1_000_000.0 / 8000, 8000) }
        }
        assertTrue(result.hasAudio)
        assertEquals(16000, result.pcm.size)
        assertEquals(((wave(0, 8000) + wave(1, 8000)) / 2).roundToInt().toShort(), result.pcm[1])
        val risingEdges = result.pcm.toList().zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
        assertTrue("440 Hz should remain 440 Hz: $risingEdges", risingEdges in 439..441)
    }

    @Test fun `44100 and 48000 Hz keep duration and pitch across rounded codec buffer timestamps`() {
        for (rate in listOf(44100, 48000)) {
            val result = render(0, 1000) { resampler ->
                var offset = 0
                while (offset < rate) {
                    val pts = (offset * 1_000_000.0 / rate).roundToLong()
                    val count = minOf(1024, rate - offset)
                    repeat(count) { i -> resampler.append(wave(offset + i, rate), pts + i * 1_000_000.0 / rate, rate) }
                    offset += count
                }
            }
            assertTrue(result.hasAudio)
            assertEquals(16000, result.pcm.size)
            val risingEdges = result.pcm.toList().zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
            assertTrue("rate=$rate rising=$risingEdges", risingEdges in 439..441)
        }
    }

    @Test fun `late start timestamp gap and early end become silence at their original video times`() {
        val result = render(250, 1750) { resampler ->
            repeat(4000) { i -> resampler.append(1000.0, 500000 + i * 62.5, 16000) }
            repeat(4000) { i -> resampler.append(2000.0, 1000000 + i * 62.5, 16000) }
        }
        assertEquals(24000, result.pcm.size)
        assertTrue(result.pcm.slice(0 until 4000).all { it == 0.toShort() })
        assertTrue(result.pcm.slice(4000 until 8000).all { it == 1000.toShort() })
        assertTrue(result.pcm.slice(8000 until 12000).all { it == 0.toShort() })
        assertTrue(result.pcm.slice(12000 until 16000).all { it == 2000.toShort() })
        assertTrue(result.pcm.slice(16000 until 24000).all { it == 0.toShort() })
    }

    @Test fun `a selected window excludes earlier and later audio without changing its duration`() {
        val result = render(200, 400) { resampler ->
            repeat(16000) { i ->
                val value = when { i < 3200 -> 1000.0; i < 6400 -> 2000.0; else -> 3000.0 }
                resampler.append(value, i * 62.5, 16000)
            }
        }
        assertTrue(result.hasAudio)
        assertEquals(3200, result.pcm.size)
        assertTrue(result.pcm.all { it == 2000.toShort() })
    }

    @Test fun `PCM16 and float stereo buffers honor buffer bounds mix to mono and sanitize NaN`() {
        val pcm16 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(123456); putShort(1000); putShort(3000); putShort(-1000); putShort(-3000)
            position(4)
        }
        val mixed16 = render(0, 1) { it.appendBuffer(pcm16, 0, 16000, 2, false) }
        assertEquals(2000.toShort(), mixed16.pcm[0])
        assertEquals((-2000).toShort(), mixed16.pcm[1])
        val floats = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(0.5f); putFloat(-0.5f); putFloat(1f); putFloat(1f)
            putFloat(-1f); putFloat(-1f); putFloat(Float.NaN); putFloat(0.5f)
            flip()
        }
        val mixedFloat = render(0, 1) { it.appendBuffer(floats, 0, 16000, 2, true) }
        assertArrayEquals(shortArrayOf(0, 32767, -32767, 8192), mixedFloat.pcm.take(4).toShortArray())
    }

    @Test fun `maximum window emits exactly bounded PCM chunks and rejects longer windows`() {
        var bytesWritten = 0
        val resampler = GongduPcmResampler(0, 180000) { _, count ->
            assertTrue(count in 2..8192 && count % 2 == 0)
            bytesWritten += count
        }
        resampler.append(1.0, 0.0, 16000)
        resampler.append(1.0, 179999937.5, 16000)
        assertTrue(resampler.finish())
        assertEquals(5_760_000, bytesWritten)
        assertEquals(bytesWritten, resampler.pcmBytesWritten)
        assertThrows(IllegalArgumentException::class.java) { GongduPcmResampler(0, 180001) { _, _ -> } }
    }

    @Test fun `no source audio or source outside the selected window is reported as absent`() {
        val absent = render(1000, 1500) { it.append(1000.0, 0.0, 16000) }
        assertFalse(absent.hasAudio)
        assertEquals(0, absent.pcm.size)
        val later = render(0, 500) { it.append(1000.0, 1000000.0, 16000) }
        assertFalse(later.hasAudio)
        assertEquals(0, later.pcm.size)
    }
}
