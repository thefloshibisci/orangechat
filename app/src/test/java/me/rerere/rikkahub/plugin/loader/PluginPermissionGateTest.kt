package me.rerere.rikkahub.plugin.loader

import android.app.Application
import me.rerere.rikkahub.plugin.webview.hasPluginPermission
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PluginPermissionGateTest {
    @Test
    fun `memory bank bridge denies undeclared plugins across all actions`() {
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), OkHttpClient())
        listOf("recall", "save", "search", "delete").forEach { action ->
            val result = JSONObject(sandbox.invokeMemoryBankBridge(action, "{}"))
            assertFalse(result.getBoolean("success"))
            assertEquals("Permission 'memory_bank' is required", result.getString("error"))
        }
    }

    @Test
    fun `memory bank bridge passes permission gate when declared`() {
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), OkHttpClient())
        sandbox.grantedPermissions = setOf("  memory_bank  ")
        val result = JSONObject(sandbox.invokeMemoryBankBridge("recall", """{"query":"hi"}"""))
        assertFalse(result.getBoolean("success"))
        assertFalse(result.getString("error").contains("Permission 'memory_bank' is required"))
    }

    @Test
    fun `hasPluginPermission matches exact and trimmed names`() {
        assertFalse(hasPluginPermission(emptyList(), "overlay"))
        assertFalse(hasPluginPermission(listOf("conversation_sync", "overlay_extra"), "overlay"))
        assertTrue(hasPluginPermission(listOf("  overlay "), "overlay"))
        assertTrue(hasPluginPermission(listOf("memory_bank"), "memory_bank"))
    }
}
