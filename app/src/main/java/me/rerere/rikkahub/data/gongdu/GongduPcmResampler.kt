package me.rerere.rikkahub.data.gongdu

import kotlin.math.max
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Incremental mono PCM16 resampling on the video's presentation timeline.
 * Adjacent source samples are linearly interpolated. Timestamp gaps are silence,
 * including a late soundtrack start or an early soundtrack end, rather than a
 * change in playback speed. The sink must consume its bounded chunks synchronously.
 */
internal class GongduPcmResampler(
    startMs: Long,
    endMs: Long,
    private val sink: (ByteArray, Int) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 180
    }

    init {
        require(startMs >= 0 && endMs > startMs && endMs <= 180_000) { "音轨范围应在 3 分钟内" }
    }

    private val startUs = startMs * 1000.0
    private val endUs = endMs * 1000.0
    private val frameLimit = ((endMs - startMs) * SAMPLE_RATE / 1000).toInt()
    private val pending = ByteArray(8192)
    private var pendingSize = 0
    private var writtenFrames = 0
    private var previousTime: Double? = null
    private var previousValue = 0.0
    private var previousPeriod = 0.0
    private var hasSourceFrames = false
    private var finished = false

    val pcmBytesWritten: Int get() = writtenFrames * 2

    fun appendBuffer(buffer: ByteBuffer, presentationTimeUs: Long, sourceRate: Int, channels: Int, floatEncoding: Boolean) {
        require(sourceRate in 8000..192000 && channels in 1..8) { "音轨采样率或声道数无效" }
        val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * if (floatEncoding) 4 else 2
        require(pcm.remaining() % frameBytes == 0) { "音轨 PCM 帧不完整" }
        var frame = 0
        while (pcm.remaining() >= frameBytes && writtenFrames < frameLimit) {
            var mono = 0.0
            repeat(channels) {
                val value = if (floatEncoding) pcm.float.toDouble() * 32767 else pcm.short.toDouble()
                mono += if (value.isFinite()) value else 0.0
            }
            append(mono / channels, presentationTimeUs + frame++ * 1_000_000.0 / sourceRate, sourceRate)
        }
    }

    fun append(value: Double, timeUs: Double, sourceRate: Int) {
        check(!finished) { "音轨处理已结束" }
        require(sourceRate in 8000..192000 && timeUs.isFinite()) { "音轨采样率或时间无效" }
        if (writtenFrames == frameLimit) return
        val period = 1_000_000.0 / sourceRate
        val sample = if (value.isFinite()) value.coerceIn(-32768.0, 32767.0) else 0.0
        val previous = previousTime
        if (previous == null && timeUs >= endUs) return
        // Decoders can repeat priming/overlap samples; never replay older timestamps.
        if (previous != null && timeUs <= previous) return
        if (timeUs < endUs && timeUs + period > startUs) hasSourceFrames = true

        if (previous == null) {
            while (writtenFrames < frameLimit && outputTime() < timeUs) emit(0.0)
        } else {
            val distance = timeUs - previous
            // A small allowance covers integer-us PTS rounding, even at 192 kHz.
            val gap = distance > max(previousPeriod, period) * 1.75 + 1.0
            while (writtenFrames < frameLimit && outputTime() < timeUs) {
                val output = outputTime()
                val next = when {
                    gap && output >= previous + previousPeriod -> 0.0
                    gap -> previousValue
                    else -> previousValue + (sample - previousValue) *
                        ((output - previous) / distance).coerceIn(0.0, 1.0)
                }
                emit(next)
            }
        }
        previousTime = timeUs
        previousValue = sample
        previousPeriod = period
    }

    /** Complete exactly the requested time window, or report that no source audio overlaps it. */
    fun finish(): Boolean {
        check(!finished) { "音轨处理已结束" }
        finished = true
        if (!hasSourceFrames) return false
        val previous = checkNotNull(previousTime)
        while (writtenFrames < frameLimit) {
            emit(if (outputTime() < previous + previousPeriod) previousValue else 0.0)
        }
        flush()
        return true
    }

    private fun outputTime(): Double = startUs + writtenFrames * 1_000_000.0 / SAMPLE_RATE

    private fun emit(value: Double) {
        check(writtenFrames < frameLimit && pcmBytesWritten + 2 <= MAX_PCM_BYTES) { "音轨超过处理上限" }
        val pcm = value.roundToInt().coerceIn(-32768, 32767)
        pending[pendingSize++] = (pcm and 255).toByte()
        pending[pendingSize++] = ((pcm shr 8) and 255).toByte()
        writtenFrames++
        if (pendingSize == pending.size) flush()
    }

    private fun flush() {
        if (pendingSize > 0) {
            sink(pending, pendingSize)
            pendingSize = 0
        }
    }
}
