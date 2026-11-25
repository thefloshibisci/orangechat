/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PluginBridgeHandlersTest {

    @Test
    fun `image transfer buffer assembles chunks resets and consumes cleanly`() {
        val buffer = PluginBridgeImageTransferBuffer(maxTransfers = 2, maxChars = 10)

        buffer.appendChunk(transferId = "tx-1", chunk = "abc", reset = true)
        buffer.appendChunk(transferId = "tx-1", chunk = "def", reset = false)
        assertEquals("abcdef", buffer.consume("tx-1"))

        assertThrows(IllegalStateException::class.java) {
            buffer.consume("tx-1")
        }

        buffer.appendChunk(transferId = "tx-2", chunk = "old", reset = true)
        buffer.appendChunk(transferId = "tx-2", chunk = "new", reset = true)
        assertEquals("new", buffer.consume("tx-2"))
    }

    @Test
    fun `image transfer buffer enforces concurrency size and lifecycle guards`() {
        val buffer = PluginBridgeImageTransferBuffer(maxTransfers = 2, maxChars = 6)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendChunk(transferId = " ", chunk = "a", reset = true)
        }
        assertThrows(IllegalStateException::class.java) {
            buffer.appendChunk(transferId = "missing", chunk = "a", reset = false)
        }

        buffer.appendChunk(transferId = "tx-1", chunk = "123", reset = true)
        buffer.appendChunk(transferId = "tx-2", chunk = "456", reset = true)
        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendChunk(transferId = "tx-3", chunk = "7", reset = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendChunk(transferId = "tx-1", chunk = "4567", reset = false)
        }

        buffer.clear()
        buffer.appendChunk(transferId = "tx-3", chunk = "ok", reset = true)
        assertEquals("ok", buffer.consume("tx-3"))
    }

    @Test
    fun `parseBridgeConversationId accepts canonical uuids and rejects invalid input`() {
        val id = Uuid.random()
        assertEquals(id, parseBridgeConversationId(id.toString()))

        for (bad in listOf(null, "", "   ", "not-a-uuid")) {
            assertThrows(IllegalArgumentException::class.java) {
                parseBridgeConversationId(bad)
            }
        }
    }

    @Test
    fun `buildReadingAssistantSystemPrompt includes structured context and handles malformed json`() {
        val plain = buildReadingAssistantSystemPrompt("{}")
        assertTrue(plain.contains("阅读助手"))
        assertFalse(plain.contains("当前阅读上下文"))

        val malformed = buildReadingAssistantSystemPrompt("{invalid json")
        assertTrue(malformed.contains("阅读助手"))
        assertFalse(malformed.contains("当前阅读上下文"))

        val rich = buildReadingAssistantSystemPrompt(
            """
            {
              "book": "小王子",
              "chapter": "21",
              "page": "88",
              "annotations": "狐狸说驯养",
              "content": "正文片段",
              "quote": "只用心才能看得清",
              "note": "很喜欢这一句"
            }
            """.trimIndent(),
        )
        assertTrue(rich.contains("书名：小王子"))
        assertTrue(rich.contains("章节：第21章"))
        assertTrue(rich.contains("页码：第88页"))
        assertTrue(rich.contains("已有批注：狐狸说驯养"))
        assertTrue(rich.contains("页面内容：正文片段"))
        assertTrue(rich.contains("引用原文：只用心才能看得清"))
        assertTrue(rich.contains("用户批注：很喜欢这一句"))
    }
}
