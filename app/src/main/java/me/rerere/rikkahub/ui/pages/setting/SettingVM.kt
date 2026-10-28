/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.withMcpServerEdit
import me.rerere.rikkahub.data.datastore.withProviderModelEdit
import kotlin.uuid.Uuid

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    fun updateDisplaySetting(desired: me.rerere.rikkahub.data.datastore.DisplaySetting) {
        // Display controls are scalar edits. Going through updateFrom() would
        // JSON-encode and merge the entire Settings object for every switch tap.
        // Update against the store's latest snapshot so concurrent edits are
        // still preserved without the full editor merge.
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(displaySetting = desired)
            }
        }
    }

    fun updateSettings(settings: Settings) {
        updateSettings(this.settings.value, settings)
    }

    fun updateSettings(previous: Settings, settings: Settings) {
        viewModelScope.launch {
            settingsStore.updateFrom(previous, settings)
        }
    }

    fun updateMcpServer(previous: McpServerConfig, desired: McpServerConfig) {
        viewModelScope.launch {
            settingsStore.update { it.withMcpServerEdit(previous, desired) }
        }
    }

    fun updateProviderModel(providerId: Uuid, previous: Model, desired: Model) {
        viewModelScope.launch {
            settingsStore.update { it.withProviderModelEdit(providerId, previous, desired) }
        }
    }

    fun updateProactiveSetting(
        onUpdated: (me.rerere.rikkahub.data.datastore.ProactiveMessageSetting) -> Unit = {},
        update: (me.rerere.rikkahub.data.datastore.ProactiveMessageSetting) -> me.rerere.rikkahub.data.datastore.ProactiveMessageSetting,
    ) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(proactiveMessageSetting = update(current.proactiveMessageSetting))
            }
            onUpdated(settingsStore.settingsFlow.value.proactiveMessageSetting)
        }
    }

    fun updateSystemToolsSetting(
        update: (me.rerere.rikkahub.data.datastore.SystemToolsSetting) -> me.rerere.rikkahub.data.datastore.SystemToolsSetting,
    ) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(systemToolsSetting = update(current.systemToolsSetting))
            }
        }
    }

    fun addCustomTheme(theme: me.rerere.rikkahub.ui.theme.CustomTheme) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(customThemes = settings.customThemes + theme)
            }
        }
    }

    fun updateCustomTheme(theme: me.rerere.rikkahub.ui.theme.CustomTheme) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    customThemes = settings.customThemes.map {
                        if (it.id == theme.id) theme else it
                    }
                )
            }
        }
    }

    fun deleteCustomTheme(themeId: String) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val ns = settings.copy(customThemes = settings.customThemes.filter { it.id != themeId })
                if (settings.themeId == themeId) ns.copy(themeId = me.rerere.rikkahub.ui.theme.PresetThemes[0].id) else ns
            }
        }
    }
}
