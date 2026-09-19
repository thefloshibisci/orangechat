package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import org.junit.Assert.*
import org.junit.Test

class ExtraInfoCollectionTest {
    @Test fun `permission failure is explicit and safe for context and diagnostics`() = runBlocking {
        val result = collectExtraInfoItem(100) { throw ExtraInfoUnavailable(ExtraInfoIssue.NOTIFICATION_PERMISSION) }
        assertEquals("unavailable:NOTIFICATION_PERMISSION", result.status)
        assertEquals(ExtraInfoIssue.NOTIFICATION_PERMISSION.label, result.summary())
        assertTrue(result.contextText().contains(ExtraInfoIssue.NOTIFICATION_PERMISSION.label))
    }

    @Test fun `unknown failures never expose exception text`() = runBlocking {
        val result = collectExtraInfoItem(100) { error("secret-screen-and-key") }
        assertFalse(result.contextText().contains("secret"))
        assertTrue(result.contextText().contains("读取失败"))
        assertTrue(ExtraInfoCollectionResult("timeout").contextText().contains("超时"))
        assertTrue(ExtraInfoCollectionResult("empty").contextText().contains("为空"))
    }

    @Test fun `diagnostics retain status but not captured content`() {
        ExtraInfoDiagnostics.publish(false, listOf("screen" to ExtraInfoCollectionResult("success", "private-text")))
        assertEquals("已读取", ExtraInfoDiagnostics.latest.value.items.single().summary)
        assertFalse(ExtraInfoDiagnostics.latest.value.toString().contains("private-text"))
    }

    @Test fun `sources isolate failures empty values and timeout`() = runBlocking {
        supervisorScope {
            val tasks = listOf(
                async { collectExtraInfoItem(100) { "value" } },
                async { collectExtraInfoItem(100) { error("sensitive text") } },
                async { collectExtraInfoItem(100) { " " } },
                async { collectExtraInfoItem(10) { delay(1_000); "late" } },
            ).map { it.await() }
            assertEquals(listOf("success", "failed:IllegalStateException", "empty", "timeout"), tasks.map { it.status })
            assertEquals(listOf("value", null, null, null), tasks.map { it.text })
            assertFalse(tasks.toString().contains("sensitive text"))
        }
    }

    @Test fun `parent cancellation propagates`() = runBlocking {
        val task = async { collectExtraInfoItem(10_000) { delay(10_000); "late" } }
        task.cancel()
        try { task.await(); fail("cancel required") } catch (_: CancellationException) { }
        assertTrue(task.isCancelled)
    }

    @Test fun `background sources require separate consent and never capture screen`() {
        val option = SystemToolsSetting(
            currentScreenAppContextInjectionEnabled = true,
            recentAppUsageContextInjectionEnabled = true,
            screenTextContextInjectionEnabled = true,
            batteryContextInjectionEnabled = true,
        )
        val original = Settings(systemToolsSetting = option)
        val disabled = original.forProactiveExtraInfo().systemToolsSetting
        assertFalse(disabled.extraInfoInjectionEnabled)
        assertFalse(disabled.screenTextContextInjectionEnabled)
        val enabled = original.copy(systemToolsSetting = option.copy(extraInfoInProactiveEnabled = true))
        val filtered = enabled.forProactiveExtraInfo().systemToolsSetting
        assertTrue(filtered.extraInfoInjectionEnabled)
        assertTrue(filtered.batteryContextInjectionEnabled)
        assertFalse(filtered.currentScreenAppContextInjectionEnabled)
        assertFalse(filtered.recentAppUsageContextInjectionEnabled)
        val allowed = enabled.copy(proactiveMessageSetting = ProactiveMessageSetting(allowProactiveAppUsage = true))
            .forProactiveExtraInfo().systemToolsSetting
        assertTrue(allowed.currentScreenAppContextInjectionEnabled)
        assertTrue(allowed.recentAppUsageContextInjectionEnabled)
        assertFalse(allowed.screenTextContextInjectionEnabled)
        assertTrue(original.systemToolsSetting.screenTextContextInjectionEnabled)
    }
}
