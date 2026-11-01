package me.rerere.tts.model

import me.rerere.tts.controller.TextChunker
import org.junit.Assert.*
import org.junit.Test

class SpeechMarkupTest {
    @Test fun `different sentences keep independent delivery and plain sentences keep defaults`() {
        val raw = "开头。[[voice emotion=happy speed=1.2 pitch=2]]开心(laughs)。[[/voice]]" +
            "[[voice emotion=sad speed=0.7 pitch=-2 volume=0.5]]慢一点<#0.3#>好。[[/voice]]结尾。"
        val segments = SpeechMarkup.parse(raw)
        assertEquals(4, segments.size)
        assertEquals(SpeechStyle(), segments[0].style)
        assertEquals(SpeechStyle("happy", 1.2f, 2), segments[1].style)
        assertEquals(SpeechStyle("sad", .7f, -2, .5f), segments[2].style)
        assertEquals("开头。开心。慢一点好。结尾。", SpeechMarkup.transcript(raw))
    }

    @Test fun `invalid model attributes cannot crash synthesis or forward nonfinite numbers`() {
        val empty = SpeechMarkup.parse("[[voice emotion=\"\" speed='' pitch=bad volume=NaN]]嗯。[[/voice]]").single()
        assertNull(empty.style.speed)
        assertNull(empty.style.pitch)
        assertNull(empty.style.volume)
        val limited = SpeechMarkup.parse("[[voice speed=100 pitch=-90 volume=100]]好。[[/voice]]").single()
        assertEquals(2f, limited.style.speed)
        assertEquals(-12, limited.style.pitch)
        assertEquals(10f, limited.style.volume)
    }

    @Test fun `streaming waits for closed voice blocks including decimal attribute and pause`() {
        val first = "[[voice emotion=happy speed=0.9]]你好<#0.3#>呀。[[/voice]]"
        for (i in 1 until first.length) assertEquals("delta $i", 0, SpeechMarkup.readyPrefixLength(first.take(i)))
        assertEquals(first.length, SpeechMarkup.readyPrefixLength(first + "[[voice speed=1."))
        assertEquals(3, SpeechMarkup.readyPrefixLength("你好。[[voi"))
    }

    @Test fun `streaming plain text retains partial sentences and decimal tokens`() {
        val ready = "你好<#0.3#>呀。"
        assertEquals(ready.length, SpeechMarkup.readyPrefixLength(ready + "没说完"))
        assertEquals(0, SpeechMarkup.readyPrefixLength("你好<#0."))
        assertEquals(0, SpeechMarkup.readyPrefixLength("3.14"))
    }

    @Test fun `only valid between-text nonconsecutive pauses survive`() {
        assertEquals("你好<#0.3#>呀", SpeechMarkup.miniMaxText("<#1#>你好<#0.3#><#2#>呀<#1#>", true))
        assertEquals("你好<#0.3#>(laughs)呀", SpeechMarkup.miniMaxText("你好<#0.3#>(laughs)<#2#>呀", true))
        assertEquals("你好呀", SpeechMarkup.miniMaxText("你好<#0#><#100#><#NaN#><#0.333#>呀", true))
        assertEquals("你好(laughs)呀", SpeechMarkup.miniMaxText("你好(LAUGHS)呀", true))
        assertEquals("你好呀", SpeechMarkup.miniMaxText("你好(laughs)呀", false))
    }

    @Test fun `markdown preparation protects tokens while transforming only words`() {
        val raw = "[[voice emotion=happy speed=0.9]]**你好**(laughs)<#0.3#>呀。[[/voice]]"
        val result = SpeechMarkup.prepare(raw) { it.replace("**", "") }
        val segment = SpeechMarkup.parse(result).single()
        assertEquals("你好(laughs)<#0.3#>呀。", segment.text)
        assertEquals(.9f, segment.style.speed)
        assertEquals("happy", segment.style.emotion)
    }

    @Test fun `chunking never splits a decimal pause token`() {
        val raw = "123456789。接着<#0.25#>说下去。"
        val chunks = TextChunker(maxChunkLength = 12).split(raw)
        assertEquals(raw, chunks.joinToString("") { it.text })
        assertTrue(chunks.any { it.text.contains("<#0.25#>") })
    }

    @Test fun `cutoff model output never speaks unfinished control headers`() {
        for (suffix in listOf("[[voice speed=0.", "[[voi", "[[/voi", "<#0.")) {
            assertEquals("你好", SpeechMarkup.transcript("你好$suffix"))
            assertEquals("你好", SpeechMarkup.miniMaxText("你好$suffix", true))
        }
    }
}
