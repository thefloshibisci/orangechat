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

    @Test fun `streaming reads long clauses before the sentence is complete`() {
        // 整句还没写完：够长的分句先出声，首字延后得更少。
        val text = "我今天走了很长很长的一段路，才终于到这里"
        val clause = text.indexOf("，") + 1
        assertEquals(clause, SpeechMarkup.streamPrefixLength(text))
        // 分句太短时继续等，避免一个字一个字地合成。
        assertEquals(0, SpeechMarkup.streamPrefixLength("好啊，那就这样吧"))
        // 已经有整句时，整句照旧优先，后面的长分句也能提前读。
        val mixed = "你好。我今天走了很长很长的一段路，才到这里"
        val mixedCut = SpeechMarkup.streamPrefixLength(mixed)
        assertTrue(mixedCut > "你好。".length)
        assertTrue(mixed.take(mixedCut).endsWith("，"))
    }

    @Test fun `streaming never splits inside markup`() {
        // 未闭合的 voice 块：任何分句边界都不许切（切了就会把半个标签读出来）。
        val unclosed = "[[voice emotion=happy speed=1.0]]这句话里面有很长很长的一个逗号，后面还有字"
        assertEquals(0, SpeechMarkup.streamPrefixLength(unclosed))
        // 停顿标记完整时照常按分句切，但绝不能切在 <#...#> 中间。
        val pause = "你好<#0.3#>我好，然后还有很多很多的话"
        val pauseCut = SpeechMarkup.streamPrefixLength(pause)
        assertTrue(pauseCut >= pause.indexOf("，") + 1)
        assertTrue(pause.take(pauseCut).contains("<#0.3#>"))
    }
}
