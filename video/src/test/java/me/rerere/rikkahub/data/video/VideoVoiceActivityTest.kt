package me.rerere.rikkahub.data.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 视频通话"说完自动发送"的判定：只在真的说过话、并且安静够久之后才成立。 */
class VideoVoiceActivityTest {
    private fun VideoVoiceActivity.speech(now: Long) = onAmplitude(0.4f, now)
    private fun VideoVoiceActivity.silence(now: Long) = onAmplitude(0.01f, now)

    @Test
    fun `全程安静的一段不会被当成一句话发出去`() {
        val activity = VideoVoiceActivity()
        var now = 0L
        while (now < 10_000L) {
            activity.silence(now)
            now += 150L
        }
        assertFalse(activity.shouldSend(elapsedMs = 10_000L, nowMillis = now))
    }

    @Test
    fun `说完停顿够久就自动发送`() {
        val activity = VideoVoiceActivity()
        activity.speech(0L)
        activity.speech(300L)
        activity.silence(600L)
        assertFalse("安静还不够久", activity.shouldSend(elapsedMs = 900L, nowMillis = 1_000L))
        assertTrue(activity.shouldSend(elapsedMs = 2_600L, nowMillis = 2_600L))
    }

    @Test
    fun `刚开始那一下不会被判成说完`() {
        val activity = VideoVoiceActivity()
        activity.silence(0L)
        activity.speech(50L)
        activity.silence(100L)
        assertFalse(activity.shouldSend(elapsedMs = 200L, nowMillis = 200L))
    }

    @Test
    fun `说话过程里的短暂停顿不会提前切段`() {
        val activity = VideoVoiceActivity()
        activity.speech(0L)
        activity.silence(200L)
        assertFalse("一秒半的换气不能发送", activity.shouldSend(elapsedMs = 1_700L, nowMillis = 1_700L))
        activity.speech(1_750L)
        activity.silence(1_900L)
        assertFalse("接着说会重新计时", activity.shouldSend(elapsedMs = 3_500L, nowMillis = 3_500L))
        assertTrue(activity.shouldSend(elapsedMs = 3_900L, nowMillis = 3_900L))
    }

    @Test
    fun `转写稳定且有内容时，即使没有振幅也会发送`() {
        val activity = VideoVoiceActivity()
        activity.onTranscript("我今天在路上", 0L)
        assertFalse(activity.shouldSend(elapsedMs = 1_000L, nowMillis = 500L))
        assertTrue(activity.shouldSend(elapsedMs = 2_000L, nowMillis = 2_000L))
    }

    @Test
    fun `stable transcript does not send while speech is still active`() {
        val activity = VideoVoiceActivity()
        activity.onTranscript("我还在说话", 0L)
        activity.speech(0L)
        activity.speech(1_200L)
        assertFalse(activity.shouldSend(elapsedMs = 1_200L, nowMillis = 1_200L))
        activity.silence(1_350L)
        assertFalse(activity.shouldSend(elapsedMs = 1_500L, nowMillis = 1_500L))
        assertTrue(activity.shouldSend(elapsedMs = 3_350L, nowMillis = 3_350L))
    }

    @Test
    fun `missing audio samples are not evidence that speech ended`() {
        val activity = VideoVoiceActivity()
        activity.onTranscript("还没说完", 0L)
        activity.speech(0L)
        assertFalse(activity.shouldSend(elapsedMs = 5_000L, nowMillis = 5_000L))
        activity.silence(5_000L)
        assertFalse(activity.shouldSend(elapsedMs = 6_900L, nowMillis = 6_900L))
        assertTrue(activity.shouldSend(elapsedMs = 7_000L, nowMillis = 7_000L))
    }

    @Test
    fun `reset 之后不继承上一段的语音与转写`() {
        val activity = VideoVoiceActivity()
        activity.speech(0L)
        activity.onTranscript("上一句", 0L)
        activity.reset()
        activity.silence(5_000L)
        assertFalse(activity.shouldSend(elapsedMs = 9_000L, nowMillis = 9_000L))
    }
}
