package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GongduVideoSamplingTest {
    @Test fun shortVideoUsesTwoSecondDetailWhileLongOverviewStaysBoundedAndCoversBothEnds() {
        val short = gongduVideoPlan(10_001)
        assertEquals(listOf(0L, 2000L, 4000L, 6000L, 8000L, 10_000L), short.times)
        val long = gongduVideoPlan(180_000)
        assertEquals(24, long.times.size)
        assertEquals(0L, long.times.first())
        assertEquals(179_999L, long.times.last())
        assertEquals(long.times.sorted().distinct(), long.times)
    }
    @Test fun explicitWindowSamplesOnlyRequestedRangeAtHigherDensity() {
        val plan = gongduVideoPlan(60_000, GongduVideoRequest(10.0, 13.001, 0.5))
        assertFalse(plan.overview)
        assertEquals((10_000L..13_000L step 500).toList(), plan.times)
        assertEquals(13_001L, plan.endMs)
        val final = gongduVideoPlan(60_000, GongduVideoRequest(58.0))
        assertEquals(60_000L, final.endMs)
    }
    @Test fun invalidUnboundedAndReversedWindowsCannotPretendToHaveBeenRead() {
        listOf(GongduVideoRequest(-1.0), GongduVideoRequest(60.0), GongduVideoRequest(10.0, 5.0),
            GongduVideoRequest(0.0, 45.0), GongduVideoRequest(Double.NaN), GongduVideoRequest(intervalSeconds = 0.0),
            GongduVideoRequest(endSeconds = Double.POSITIVE_INFINITY)).forEach {
            assertTrue(runCatching { gongduVideoPlan(60_000, it) }.isFailure)
        }
    }
    @Test fun modelArgumentsMustBeActualFiniteNumbers() {
        assertEquals(GongduVideoRequest(2.0, 4.0, 0.5), gongduVideoRequest(buildJsonObject {
            put("start_seconds", 2); put("end_seconds", 4); put("frame_interval_seconds", 0.5)
        }))
        assertTrue(runCatching { gongduVideoRequest(buildJsonObject { put("start_seconds", "2") }) }.isFailure)
        assertTrue(runCatching { gongduVideoRequest(buildJsonObject { put("end_seconds", JsonNull) }) }.isFailure)
    }
    @Test fun extractedSoundHeaderDeclaresExactMono16kPcmLength() {
        val header = GongduAudioReader.wavHeader(32_000)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals(32_036, data.getInt(4))
        assertEquals(1, data.getShort(22).toInt())
        assertEquals(16_000, data.getInt(24))
        assertEquals(16, data.getShort(34).toInt())
        assertEquals(32_000, data.getInt(40))
    }
}
