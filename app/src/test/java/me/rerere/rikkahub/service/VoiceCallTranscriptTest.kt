package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class VoiceCallTranscriptTest {
    @Test fun streamingRevisionsKeepOneLineWithOriginalTimeAndName() {
        val log = VoiceCallTranscript()
        log.record("u1", "user", "严槿", "你好", 100)
        log.record("a1", "assistant", "南枳", "你好", 200)
        log.record("a1", "assistant", "南枳", "你好，我在。", 250)
        assertEquals(2, log.snapshot().size)
        assertEquals("你好，我在。", log.snapshot()[1].text)
        assertEquals(200L, log.snapshot()[1].timestampMillis)
    }
    @Test fun completedSnapshotRemainsSeparateFromNextCall() {
        val log = VoiceCallTranscript()
        log.record("u1", "user", "严槿", "第一通", 100)
        log.record("empty", "assistant", "南枳", " ", 110)
        val first = log.snapshot()
        log.clear()
        log.record("u2", "user", "严槿", "第二通", 200)
        assertEquals(listOf("第一通"), first.map { it.text })
        assertEquals(listOf("第二通"), log.snapshot().map { it.text })
    }
}
