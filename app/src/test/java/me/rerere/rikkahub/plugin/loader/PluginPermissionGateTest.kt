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
    fun `memory bank bridge always returns removed regardless of manifest permissions`() {
        val sandbox = PluginSandbox(RuntimeEnvironment.getApplication(), OkHttpClient())
        listOf(emptySet(), setOf("memory_bank")).forEach { perms ->
            sandbox.grantedPermissions = perms
            listOf("recall", "save", "search", "delete").forEach { action ->
                val result = JSONObject(sandbox.invokeMemoryBankBridge(action, """{"query":"hi","content":"x"}"""))
                assertFalse(result.getBoolean("success"))
                assertEquals("MemoryBank has been removed", result.getString("error"))
            }
        }
    }

    @Test
    fun `hasPluginPermission matches exact and trimmed names`() {
        assertFalse(hasPluginPermission(emptyList(), "overlay"))
        assertFalse(hasPluginPermission(listOf("conversation_sync", "overlay_extra"), "overlay"))
        assertTrue(hasPluginPermission(listOf("  overlay "), "overlay"))
    }
}
