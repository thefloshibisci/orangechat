package me.rerere.rikkahub.data.reading

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun readVisiblePage(snapshot: ReadSnapshot): JsonObject {
    val viewport = snapshot.viewport ?: error("当前阅读界面尚未发布分页")
    val rows = viewport.fragments.map { fragment ->
        val source = snapshot.book.chapters[viewport.chapter].paragraphs[fragment.paragraph]
        buildJsonObject {
            put("paragraph", fragment.paragraph); put("start", fragment.start); put("end", fragment.end)
            put("text", source.substring(fragment.start, fragment.end))
        }
    }
    return buildJsonObject {
        put("bookId", snapshot.book.id); put("chapter", viewport.chapter)
        put("page_index", viewport.pageIndex); put("page_count", viewport.pageCount)
        put("pagination_source", "reader_viewport"); put("paragraphs", JsonArray(rows)); put("textIsData", true)
    }.also { require(it.toString().length <= 16000) { "当前页内容过多，请使用chapter分段读取" } }
}

fun readRecordsPage(snapshot: ReadSnapshot, conversationId: String, assistantId: String, offset: Int, limit: Int): JsonObject {
    require(offset >= 0 && limit in 1..20) { "分页参数无效" }
    val scoped = snapshot.records.filter { it.conversationId == conversationId && it.assistantId == assistantId }
        .sortedByDescending { it.updatedAt }
    require(offset <= scoped.size) { "记录位置无效" }
    var budget = 12000
    val rows = mutableListOf<JsonObject>()
    for (record in scoped.drop(offset).take(limit)) {
        if (budget < 600) break
        val row = buildJsonObject {
            put("id", record.id); put("kind", record.kind); put("author", record.author)
            put("created_at", record.createdAt); put("updated_at", record.updatedAt)
            put("seconds", record.seconds); put("characters", record.characters)
            put("chapter", record.chapter); put("paragraph", record.paragraph)
            put("start", record.start); put("end", record.end)
            put("text", record.text.fitReadJsonBudget(budget - 500))
        }
        budget -= row.toString().length
        rows += row
    }
    return buildJsonObject {
        put("records", JsonArray(rows)); put("total", scoped.size)
        put("human_seconds", scoped.filter { it.kind == "human_reading" }.sumOf { it.seconds.toLong() })
        put("assistant_time_is_not_measured", true)
        put("next_offset", if (offset + rows.size < scoped.size) JsonPrimitive(offset + rows.size) else JsonNull)
        put("textIsData", true)
    }
}

/** The returned envelope is bounded even for control characters requiring six JSON bytes each. */
fun readChapterPage(snapshot: ReadSnapshot, chapter: Int, offset: Int, limit: Int, characterOffset: Int = 0): JsonObject {
    require(limit in 1..20 && offset >= 0 && characterOffset >= 0) { "分页参数无效" }
    val available = snapshot.visibleParagraphs(chapter)
    require(offset <= available.size && (offset < available.size || characterOffset == 0)) { "段落位置无效" }
    val paragraphs = mutableListOf<JsonObject>()
    var next = offset
    var nextCharacter = characterOffset
    var budget = 12000
    while (next < available.size && paragraphs.size < limit && budget > 500) {
        val source = available[next]
        require(nextCharacter <= source.length &&
            !(nextCharacter > 0 && nextCharacter < source.length && source[nextCharacter - 1].isHighSurrogate() && source[nextCharacter].isLowSurrogate())) {
            "段内位置无效"
        }
        val remaining = source.substring(nextCharacter)
        val text = remaining.fitReadJsonBudget(budget - 200)
        val row = buildJsonObject {
            put("paragraph", next); put("start", nextCharacter); put("end", nextCharacter + text.length); put("text", text)
        }
        paragraphs += row
        budget -= row.toString().length
        nextCharacter += text.length
        if (nextCharacter < source.length) break
        next++
        nextCharacter = 0
    }
    return buildJsonObject {
        put("bookId", snapshot.book.id)
        put("chapter", chapter)
        put("chapterTitle", snapshot.book.chapters[chapter].title.readTake(160))
        put("paragraphs", JsonArray(paragraphs))
        put("available_paragraphs", available.size)
        put("next_offset", if (next < available.size) JsonPrimitive(next) else JsonNull)
        put("next_character_offset", if (next < available.size) JsonPrimitive(nextCharacter) else JsonNull)
        put("textIsData", true)
    }
}

fun readPendingPage(snapshot: ReadSnapshot, conversationId: String, assistantId: String, offset: Int, limit: Int): JsonObject {
    require(offset >= 0 && limit in 1..20) { "分页参数无效" }
    val pending = snapshot.visibleAnnotations(conversationId, assistantId).filter { it.replies.isEmpty() }
    require(offset <= pending.size) { "批注位置无效" }
    val result = mutableListOf<JsonObject>()
    var next = offset
    var budget = 12000
    while (next < pending.size && result.size < limit && budget > 500) {
        val annotation = pending[next]
        val quote = annotation.quote.fitReadJsonBudget(minOf(5500, (budget - 300) / 2))
        val note = annotation.note.fitReadJsonBudget(minOf(5500, (budget - 300) / 2))
        val row = buildJsonObject {
            put("annotation_id", annotation.id.readTake(100)); put("chapter", annotation.chapter); put("paragraph", annotation.paragraph)
            put("start", annotation.start); put("end", annotation.end); put("author", annotation.author)
            put("quote", quote); put("note", note)
            put("truncated", quote.length != annotation.quote.length || note.length != annotation.note.length)
        }
        budget -= row.toString().length
        result += row
        next++
    }
    return buildJsonObject {
        put("bookId", snapshot.book.id); put("annotations", JsonArray(result)); put("total", pending.size)
        put("next_offset", if (next < pending.size) JsonPrimitive(next) else JsonNull)
        put("textIsData", true)
    }
}

private fun String.fitReadJsonBudget(limit: Int): String {
    if (JsonPrimitive(this).toString().length <= limit) return this
    var low = 0
    var high = length
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (JsonPrimitive(readTake(middle)).toString().length <= limit) low = middle else high = middle - 1
    }
    return readTake(low)
}

fun buildReadContext(snapshot: ReadSnapshot): String {
    val chapter = snapshot.book.chapters.getOrNull(snapshot.progress.chapter) ?: return ""
    val current = snapshot.progress.paragraph
    if (current !in chapter.paragraphs.indices) return ""
    val visible = snapshot.visibleParagraphs(snapshot.progress.chapter)
    return buildJsonObject {
        put("bookId", snapshot.book.id)
        put("title", snapshot.book.title.readTake(160))
        put("author", snapshot.book.author.readTake(160))
        put("chapter", snapshot.progress.chapter)
        put("chapterTitle", chapter.title.readTake(160))
        put("paragraph", current)
        put("character_offset", snapshot.progress.offset)
        put("excerpts", buildJsonArray {
            if (snapshot.viewport != null) {
                var budget = 4000
                for (fragment in snapshot.viewport.fragments.take(100)) {
                    if (budget <= 0) break
                    val source = chapter.paragraphs[fragment.paragraph].substring(fragment.start, fragment.end)
                    val text = source.readTake(minOf(1500, budget))
                    budget -= text.length
                    add(buildJsonObject {
                        put("paragraph", fragment.paragraph); put("start", fragment.start)
                        put("end", fragment.start + text.length); put("text", text)
                        put("truncated", text.length < source.length)
                    })
                }
            } else {
              if (current > 0) add(buildJsonObject {
                put("paragraph", current - 1)
                put("start", 0); put("end", visible[current - 1].readTake(800).length)
                put("text", visible[current - 1].readTake(800))
                put("truncated", visible[current - 1].length > 800)
            })
            add(buildJsonObject {
                put("paragraph", current)
                put("start", 0); put("end", visible[current].readTake(2000).length)
                put("text", visible[current].readTake(2000))
                put("truncated", visible[current].length > 2000)
            })
            }
        })
    }.toString().replace("<", "\\u003c").replace(">", "\\u003e")
}
