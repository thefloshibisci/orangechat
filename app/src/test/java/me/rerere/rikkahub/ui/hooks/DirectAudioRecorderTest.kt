package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectAudioRecorderTest {
    @Test fun createsPlayableMono16BitWavHeader() {
        val header = DirectAudioRecorder.wavHeader(32000)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(32036, buffer.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16000, buffer.getInt(24))
        assertEquals(32000, buffer.getInt(28))
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(32000, buffer.getInt(40))
    }
}
