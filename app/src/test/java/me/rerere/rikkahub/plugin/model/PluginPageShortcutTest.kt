package me.rerere.rikkahub.plugin.model

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginPageShortcutTest {
    private val plugin = PluginInfo(
        PluginManifest("test.plugin", "Plugin", "", "1", "Test", "", "main.js",
            customPageWebView = PluginWebViewPageConfig(entry = "index.html")),
        File("test.plugin"), isEnabled = true,
    )

    @Test fun runtimeErrorDoesNotHideManagementShortcut() {
        assertTrue(plugin.copy(loadError = "Missing configuration").hasPageShortcut())
    }

    @Test fun disabledOrIntegrityBlockedPluginIsNotAShortcut() {
        assertFalse(plugin.copy(isEnabled = false).hasPageShortcut())
        assertFalse(plugin.copy(isEnabled = false, loadError = "Integrity check failed").hasPageShortcut())
    }

    @Test fun toolOnlyPluginHasNoPageShortcut() {
        assertFalse(plugin.copy(manifest = plugin.manifest.copy(customPageWebView = null)).hasPageShortcut())
    }

    @Test fun onlySupportedBuiltInPagesHaveShortcuts() {
        assertTrue(plugin.copy(manifest = plugin.manifest.copy(customPageWebView = null,
            customPage = "memory_bank")).hasPageShortcut())
        assertFalse(plugin.copy(manifest = plugin.manifest.copy(customPageWebView = null,
            customPage = "unknown")).hasPageShortcut())
    }
}
