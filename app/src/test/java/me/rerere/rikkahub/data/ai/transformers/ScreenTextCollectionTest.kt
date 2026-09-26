package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenTextCollectionTest {
    @Test fun `successful OCR never reads the accessibility tree`() = runBlocking {
        val result = collectScreenTextWithFallback({ "  识别文字  " }, { error("must not read") })
        assertEquals("success", result.status)
        assertEquals("识别文字", result.text)
        assertEquals("已读取", result.summary())
    }

    @Test fun `engine failure falls back with truthful source and bounded content`() = runBlocking {
        val result = collectScreenTextWithFallback(
            { throw ExtraInfoUnavailable(ExtraInfoIssue.OCR_UNAVAILABLE, 14) },
            { "可见文字".repeat(2000) },
        )
        assertEquals("fallback:OCR_UNAVAILABLE:14", result.status)
        assertTrue(result.summary().contains("无障碍可见文字"))
        assertTrue(result.summary().contains("错误码 14"))
        assertTrue(result.text!!.contains("可能不包含图片内文字"))
        assertTrue(result.text!!.length <= 4000)
        ExtraInfoDiagnostics.publish(false, listOf("当前屏幕文字" to result))
        assertFalse(ExtraInfoDiagnostics.latest.value.toString().contains("可见文字可见文字"))
    }

    @Test fun `empty or changed window fallback preserves the OCR failure`() = runBlocking {
        for (issue in listOf(ExtraInfoIssue.OCR_INITIALIZATION, ExtraInfoIssue.OCR_UNAVAILABLE,
            ExtraInfoIssue.OCR_IMAGE, ExtraInfoIssue.OCR_RECOGNITION)) {
            val result = collectExtraInfoResult(100) {
                collectScreenTextWithFallback({ throw ExtraInfoUnavailable(issue) }, { " " })
            }
            assertEquals("unavailable:${issue.name}", result.status)
            assertNull(result.text)
        }
    }

    @Test fun `protected screenshot and missing permission never fall back`() = runBlocking {
        for (issue in listOf(ExtraInfoIssue.SCREEN_PROTECTED, ExtraInfoIssue.ACCESSIBILITY,
            ExtraInfoIssue.SCREEN_FAILED, ExtraInfoIssue.BACKGROUND_SCREEN)) {
            val result = collectExtraInfoResult(100) {
                collectScreenTextWithFallback({ throw ExtraInfoUnavailable(issue) }, { error("must not read") })
            }
            assertEquals("unavailable:${issue.name}", result.status)
        }
    }

    @Test fun `cancellation and timeout never trigger another screen read`() = runBlocking {
        try {
            collectScreenTextWithFallback({ throw CancellationException() }, { error("must not read") })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        val timedOut = collectExtraInfoResult(10) {
            collectScreenTextWithFallback({ delay(1000); "late" }, { error("must not read") })
        }
        assertEquals("timeout", timedOut.status)
    }

    @Test fun `fallback cancellation propagates and fallback errors reveal no content`() = runBlocking {
        try {
            collectScreenTextWithFallback({ throw ExtraInfoUnavailable(ExtraInfoIssue.OCR_RECOGNITION) },
                { throw CancellationException() })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        val result = collectExtraInfoResult(100) {
            collectScreenTextWithFallback({ throw ExtraInfoUnavailable(ExtraInfoIssue.OCR_RECOGNITION) },
                { error("private screen and secret") })
        }
        assertEquals("unavailable:OCR_RECOGNITION", result.status)
        assertFalse(result.contextText().contains("secret"))
    }

    @Test fun `empty recognition remains empty and successful recognition is bounded`() = runBlocking {
        assertEquals("empty", collectScreenTextWithFallback({ " " }, { error("must not read") }).status)
        assertEquals(4000, collectScreenTextWithFallback({ "x".repeat(5000) }, { error("must not read") }).text!!.length)
    }
}
