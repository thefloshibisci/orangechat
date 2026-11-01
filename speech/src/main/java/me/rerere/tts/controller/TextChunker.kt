/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.controller

import me.rerere.tts.model.SpeechStyle
import me.rerere.tts.model.SpeechMarkup

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 */
class TextChunker(
    private val maxChunkLength: Int = 150
) {
    fun splitSpeech(text: String): List<TtsChunk> = SpeechMarkup.parse(text).flatMap { segment ->
        split(segment.text).map { it.copy(style = segment.style) }
    }.mapIndexed { index, chunk -> chunk.copy(index = index) }

    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split("\n\n")

        val chunks = paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                splitSentences(paragraph)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }

    private fun splitSentences(text: String): List<String> = buildList {
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text.startsWith("<#", index)) {
                val end = text.indexOf("#>", index)
                if (end >= 0) { index = end + 2; continue }
            }
            if (text[index] in "。！？，、：;.!?:,\n") {
                add(text.substring(start, index + 1))
                start = index + 1
            }
            index++
        }
        if (start < text.length) add(text.substring(start))
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String,
    val style: SpeechStyle = SpeechStyle(),
)

