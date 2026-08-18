/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentContinuityTest {
    @Test
    fun `selects only bounded user and assistant text`() {
        val messages = buildList {
            add(UIMessage.system("secret system prompt"))
            repeat(4) { index ->
                add(UIMessage.user("user-$index"))
                add(UIMessage.assistant("assistant-$index"))
            }
            add(UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("secret tool output"))))
            add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///photo.jpg"))))
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("caption that belongs to a photo"),
                        UIMessagePart.Image("file:///photo-with-caption.jpg"),
                    ),
                )
            )
        }

        val turns = selectRecentContinuityTurns(messages)

        assertEquals(6, turns.size)
        assertEquals("user-1", turns.first().text)
        assertEquals("assistant-3", turns.last().text)
        assertFalse(turns.any { it.text.contains("secret") })
    }

    @Test
    fun `builds schema expected by Xinchao with a stable conversation session`() {
        val args = buildRecentContinuityArguments(
            sessionId = recentContinuitySessionId("conversation-1"),
            turns = listOf(RecentContinuityTurn("turn-1", "user", "刚刚聊到这里")),
        )

        assertEquals("orangechat:conversation-1", args["session_id"]?.jsonPrimitive?.content)
        assertEquals("orangechat-android", args["client"]?.jsonPrimitive?.content)
        val message = args["messages"]?.jsonArray?.single()?.jsonObject
        assertEquals("turn-1", message?.get("turn_id")?.jsonPrimitive?.content)
        assertEquals("user", message?.get("role")?.jsonPrimitive?.content)
        assertEquals("刚刚聊到这里", message?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `drops turns that look like credentials`() {
        val messages = listOf(
            UIMessage.user("普通聊天"),
            UIMessage.user("api_key = sk-this-must-not-leave-the-device"),
            UIMessage.assistant("Authorization: Bearer secret-token-value"),
        )

        val turns = selectRecentContinuityTurns(messages)

        assertEquals(listOf("普通聊天"), turns.map { it.text })
    }

    @Test
    fun `extracts returned context and drops transport status`() {
        val result = parseRecentContinuityResult(
            listOf(
                UIMessagePart.Text(
                    "近期跨端对话已同步：写入 1 条，重复 0 条。\n\n" +
                        "2026-08-18T12:00:00Z [codex/window] user: 手机端刚聊到这里"
                )
            )
        )

        assertEquals(
            "2026-08-18T12:00:00Z [codex/window] user: 手机端刚聊到这里",
            result,
        )
    }

    @Test
    fun `returns null when no other client context exists`() {
        val result = parseRecentContinuityResult(
            listOf(UIMessagePart.Text("近期跨端对话已同步：写入 1 条，重复 0 条。 当前没有可返回的近期内容。"))
        )

        assertNull(result)
    }

    @Test
    fun `prompt marks remote text as optional short lived context`() {
        val prompt = recentContinuityPrompt("other window context")

        assertTrue(prompt.contains("近期跨端上下文"))
        assertTrue(prompt.contains("不是长期记忆"))
        assertTrue(prompt.contains("other window context"))
    }
}
