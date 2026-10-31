package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.datastore.ChatDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBubbleSegmentsTest {
    @Test
    fun everydaySplitsBothRolesWithoutChangingExistingAssistantFlags() {
        assertEquals(
            listOf("第一句。", "第二句。"),
            "第一句。\n\n第二句。".chatBubbleSegments(ChatDisplayMode.EVERYDAY, false),
        )
    }

    @Test
    fun standardKeepsItsExistingSplitBehavior() {
        val text = "第一句。\n第二句。"
        assertEquals(listOf(text), text.chatBubbleSegments(ChatDisplayMode.STANDARD, false))
        assertEquals(
            listOf("第一句。", "第二句。"),
            text.chatBubbleSegments(ChatDisplayMode.STANDARD, true),
        )
    }

    @Test
    fun codeAndTablesRemainWholeWhileSurroundingLinesSplit() {
        val code = "```kotlin\nval a = 1\nprintln(a)\n```"
        val table = "| A | B |\n|---|---|\n| 1 | 2 |"
        assertEquals(
            listOf("开头", code, table, "结尾"),
            "开头\n$code\n$table\n结尾".chatBubbleSegments(ChatDisplayMode.EVERYDAY, false),
        )
    }
    @Test fun namedQuoteAndReplyStayTogetherWithMultilineSource() {
        val original = "> **我**\n> 不是，是我要去洗澡\n> 第二行\n\n我不是故意的\n真的"
        assertEquals(BubbleQuote("我", "不是，是我要去洗澡\n第二行", "我不是故意的\n真的"), original.bubbleQuote())
        assertEquals(listOf(original), original.chatBubbleSegments(ChatDisplayMode.EVERYDAY, false))
        assertEquals("**南枳**\n我不是故意的\n真的", quotedBubbleSelection("南枳", original))
    }

    @Test fun legacyQuoteWorksAndOrdinaryProseDoesNotBecomeQuote() {
        assertEquals(BubbleQuote(null, "旧原话", "新的回复"), "> 旧原话\n\n新的回复".bubbleQuote())
        assertEquals(null, "正文里 > 大于号".bubbleQuote())
        assertEquals(null, "> **我**\n\n没有原话".bubbleQuote())
    }
}
