package me.rerere.rikkahub.data.serein

import org.junit.Assert.*
import org.junit.Test

class SereinStatusTest {
    @Test fun `connection errors are actionable without reflecting untrusted response text`() {
        assertTrue(sereinConnectionLabel(HookRecall(status = "http_401"), 10).contains("Gateway Key"))
        assertTrue(sereinConnectionLabel(HookRecall(status = "http_404"), 10).contains("服务版本"))
        assertTrue(sereinConnectionLabel(null, 3000).contains("超时"))
        assertFalse(sereinFailureLabel("secret-response-token").contains("secret"))
        assertTrue(sereinConnectionLabel(HookRecall(), 10).startsWith("连接成功"))
        assertTrue(sereinRecallLabel(SereinRecallStatus("empty", "[]", 1)).contains("未命中"))
        assertTrue(sereinRecallLabel(SereinRecallStatus("ready", "[\"scene:test\"]", 1)).contains("1 条候选"))
    }
}
