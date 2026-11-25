/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveRefactorTest {

    @Test
    fun `sanitizeProactiveHistory strips reasoning from sent proactive messages unless preserved`() {
        val userMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("晚上好")),
        )
        val proactiveMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("内心思考"),
                UIMessagePart.Tool("call-1", "memory_search", "{}"),
                UIMessagePart.Text("在忙吗？"),
                UIMessagePart.Text("注意休息"),
            ),
        )
        val blankProactiveMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("仅思考不说话"), UIMessagePart.Text("   ")),
        )
        val regularAssistantMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("普通思考"),
                UIMessagePart.Text("普通回复"),
            ),
        )

        val ids = setOf(proactiveMsg.id.toString(), blankProactiveMsg.id.toString())
        val sanitized = sanitizeProactiveHistory(
            messages = listOf(userMsg, proactiveMsg, blankProactiveMsg, regularAssistantMsg),
            proactiveMessageIds = ids,
            preserveReasoning = false,
        )

        assertEquals(3, sanitized.size)
        assertEquals(userMsg, sanitized[0])
        assertEquals(listOf(UIMessagePart.Text("在忙吗？\n注意休息")), sanitized[1].parts)
        assertEquals(regularAssistantMsg, sanitized[2])

        val preserved = sanitizeProactiveHistory(
            messages = listOf(userMsg, proactiveMsg, blankProactiveMsg),
            proactiveMessageIds = ids,
            preserveReasoning = true,
        )
        assertEquals(listOf(userMsg, proactiveMsg, blankProactiveMsg), preserved)
    }

    @Test
    fun `stripHistoricalToolParts removes tools from assistant messages and drops empty shells`() {
        val userMsg = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello")))
        val toolOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c1", "ping", "{}")),
        )
        val mixed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool("c2", "ping", "{}"),
                UIMessagePart.Text("done"),
            ),
        )

        val stripped = stripHistoricalToolParts(listOf(userMsg, toolOnly, mixed))
        assertEquals(2, stripped.size)
        assertEquals(userMsg, stripped[0])
        assertEquals(listOf(UIMessagePart.Text("done")), stripped[1].parts)
    }

    @Test
    fun `filterInvalidToolMessages keeps executed or resumable tools and drops dangling unexecuted ones`() {
        val json = Json { ignoreUnknownKeys = true }
        val executed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("ok")),
                ),
            ),
        )
        val dangling = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c2",
                    toolName = "search",
                    input = "{}",
                    approvalState = ToolApprovalState.Auto,
                ),
            ),
        )
        val resumable = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "c3",
                    toolName = "search",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        val filtered = filterInvalidToolMessages(listOf(executed, dangling, resumable), json)
        assertEquals(listOf(executed.id, resumable.id), filtered.map { it.id })
    }

    @Test
    fun `isDeepSeekCompatible detects deepseek hosts and model names for OpenAI providers only`() {
        val genericOpenAi = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
        val deepSeekHost = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1")
        val google = ProviderSetting.Google()

        assertTrue(isDeepSeekCompatible(deepSeekHost, Model(modelId = "custom-chat")))
        assertTrue(isDeepSeekCompatible(genericOpenAi, Model(modelId = "DeepSeek-R1")))
        assertTrue(isDeepSeekCompatible(genericOpenAi, Model(modelId = "my-ds-v3")))
        assertTrue(isDeepSeekCompatible(genericOpenAi, Model(modelId = "ds_chat")))
        assertFalse(isDeepSeekCompatible(genericOpenAi, Model(modelId = "ads-helper")))
        assertFalse(isDeepSeekCompatible(genericOpenAi, Model(modelId = "gpt-4o")))
        assertFalse(isDeepSeekCompatible(google, Model(modelId = "deepseek-reasoner")))
    }

    @Test
    fun `mergeAdjacentSameRoleMessages merges consecutive user and assistant turns in order`() {
        val u1 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("u1")))
        val u2 = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("u2")))
        val a1 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("a1")))
        val a2 = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("a2")))

        val merged = mergeAdjacentSameRoleMessages(listOf(u1, u2, a1, a2))
        assertEquals(2, merged.size)
        assertEquals(MessageRole.USER, merged[0].role)
        assertEquals(listOf(UIMessagePart.Text("u1"), UIMessagePart.Text("u2")), merged[0].parts)
        assertEquals(MessageRole.ASSISTANT, merged[1].role)
        assertEquals(listOf(UIMessagePart.Text("a1"), UIMessagePart.Text("a2")), merged[1].parts)
    }

    @Test
    fun `buildProactiveSystemPrompt renders optional sections and limits accurately`() {
        val state = ProactiveSessionState(
            followUpCount = 1,
            lastProactiveText = "记得喝水",
        )
        val fullPrompt = buildProactiveSystemPrompt(
            effectiveSystemPrompt = "你是贴心伙伴",
            memories = listOf("喜欢抹茶"),
            context = "晚上九点半",
            state = state,
            maxFollowUps = 3,
            allowAppUsage = true,
            proactiveInjections = listOf("背景条目A"),
            latestUserText = "待会儿去洗澡",
            latestAssistantText = "好呀去吧",
            alwaysRespond = true,
        )

        assertTrue(fullPrompt.contains("你是贴心伙伴"))
        assertTrue(fullPrompt.contains("## 记忆\n- 喜欢抹茶"))
        assertTrue(fullPrompt.contains("背景条目A"))
        assertTrue(fullPrompt.contains("已经实际发送 1 条主动消息"))
        assertTrue(fullPrompt.contains("最多还可以实际发送 2 条消息"))
        assertTrue(fullPrompt.contains("“记得喝水”"))
        assertTrue(fullPrompt.contains("## 查看近况"))
        assertTrue(fullPrompt.contains("不能选择 PASS、WAIT 或 STOP"))

        val minimalPrompt = buildProactiveSystemPrompt(
            effectiveSystemPrompt = "",
            memories = emptyList(),
            context = "下午三点",
            state = ProactiveSessionState(),
            maxFollowUps = 2,
            allowAppUsage = false,
            proactiveInjections = emptyList(),
            latestUserText = "",
            latestAssistantText = "",
            alwaysRespond = false,
        )

        assertFalse(minimalPrompt.contains("## 记忆"))
        assertFalse(minimalPrompt.contains("## 背景设定"))
        assertFalse(minimalPrompt.contains("## 查看近况"))
        assertFalse(minimalPrompt.contains("不能选择 PASS、WAIT 或 STOP"))
        assertTrue(minimalPrompt.contains("“（非文本消息）”"))
        assertTrue(minimalPrompt.contains("“（没有可用的文本回复）”"))
    }
}
