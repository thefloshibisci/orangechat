/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 * Extra-info controls adapted from yaselli/orangechat 47c12fe8.
 */

package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import me.rerere.rikkahub.data.ai.transformers.ExtraInfoDiagnostics
import me.rerere.rikkahub.data.service.hasNotificationListenerAccess
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.buildCardGroupItems
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingExtraInjectionPage(vm: SettingVM = koinViewModel()) {
    ExtraInjectionSettingsContent(vm.settings, vm::updateSystemToolsSetting)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtraInjectionSettingsContent(
    settingsFlow: StateFlow<Settings>,
    updateSystemToolsSetting: ((SystemToolsSetting) -> SystemToolsSetting) -> Unit,
    navigationIcon: @Composable () -> Unit = { BackButton() },
) {
    val settings by settingsFlow.collectAsStateWithLifecycle()
    val option = settings.systemToolsSetting
    val loaded = !settings.init
    val enabled = loaded && option.extraInfoInjectionEnabled
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRevision++ }
    val accessibilityReady = remember(permissionRevision) { RikkaAccessibilityService.instance != null }
    val notificationReady = remember(permissionRevision) { hasNotificationListenerAccess(context) }
    val notificationConnected = remember(permissionRevision) { NotificationListenerHandle.isBound() }
    val diagnostics by ExtraInfoDiagnostics.latest.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("额外注入") },
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("请求背景") }, items = buildCardGroupItems {
                    injectionToggle("启用额外信息", option.extraInfoInjectionEnabled, loaded) { value ->
                        updateSystemToolsSetting { it.copy(extraInfoInjectionEnabled = value) }
                    }
                    injectionToggle(
                        "用于主动消息", option.extraInfoInProactiveEnabled, enabled,
                        "后台也可读取下方已授权项目；不含屏幕 OCR。应用信息仍需主动查岗授权。",
                    ) { value -> updateSystemToolsSetting { it.copy(extraInfoInProactiveEnabled = value) } }
                    item(headlineContent = {
                        Text(if (loaded) "仅本轮使用，不保存到聊天或长期记忆" else "正在读取设置…")
                    })
                })
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("基础信息") }, items = buildCardGroupItems {
                    injectionToggle("当前时间", option.timeContextInjectionEnabled, enabled) { value ->
                        updateSystemToolsSetting { it.copy(timeContextInjectionEnabled = value) }
                    }
                    injectionToggle("电池信息", option.batteryContextInjectionEnabled, enabled) { value ->
                        updateSystemToolsSetting { it.copy(batteryContextInjectionEnabled = value) }
                    }
                    injectionToggle("天气", option.weatherContextInjectionEnabled, enabled,
                        "需位置权限；近似坐标会发送至 wttr.in 天气服务。") { value ->
                        updateSystemToolsSetting { it.copy(weatherContextInjectionEnabled = value) }
                    }
                    injectionToggle("当前位置", option.locationContextInjectionEnabled, enabled,
                        "需位置权限；坐标会发送给当前聊天模型。") { value ->
                        updateSystemToolsSetting { it.copy(locationContextInjectionEnabled = value) }
                    }
                    injectionToggle("详细地址", option.preciseLocationContextInjectionEnabled,
                        enabled && option.locationContextInjectionEnabled,
                        "需高德 API Key；坐标会发送至高德。") { value ->
                        updateSystemToolsSetting { it.copy(preciseLocationContextInjectionEnabled = value) }
                    }
                    if (option.preciseLocationContextInjectionEnabled) {
                        item(headlineContent = {
                            OutlinedTextField(
                                value = option.amapApiKey,
                                onValueChange = { key -> updateSystemToolsSetting { it.copy(amapApiKey = key.trim()) } },
                                label = { Text("高德 Web 服务 Key") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                enabled = enabled,
                            )
                        })
                    }
                })
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("应用与通知") }, items = buildCardGroupItems {
                    item(
                        headlineContent = { Text(if (accessibilityReady) "当前版本无障碍服务已连接" else "当前版本无障碍服务未连接") },
                        trailingContent = { TextButton(onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("授权设置") } },
                    )
                    item(
                        headlineContent = { Text(when {
                            !notificationReady -> "当前版本未获通知访问权限"
                            !notificationConnected -> "通知权限已开，等待监听连接"
                            else -> "通知监听已连接"
                        }) },
                        trailingContent = { TextButton(onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("授权设置") } },
                    )
                    injectionToggle("当前应用", option.currentScreenAppContextInjectionEnabled, enabled,
                        "需无障碍服务。") { value ->
                        updateSystemToolsSetting { it.copy(currentScreenAppContextInjectionEnabled = value) }
                    }
                    injectionToggle("今日应用使用", option.recentAppUsageContextInjectionEnabled, enabled,
                        "需使用情况访问权限；最多 3 个应用。") { value ->
                        updateSystemToolsSetting { it.copy(recentAppUsageContextInjectionEnabled = value) }
                    }
                    injectionToggle("当前屏幕文字", option.screenTextContextInjectionEnabled, enabled,
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "当前系统不支持屏幕 OCR，需要 Android 11 或更高版本。"
                        else "需无障碍服务；本地 OCR，最多 4000 字。识别文字会发送给聊天模型，不在后台截屏。") { value ->
                        updateSystemToolsSetting { it.copy(screenTextContextInjectionEnabled = value) }
                    }
                    injectionToggle("最近通知", option.notificationsContextInjectionEnabled, enabled,
                        "需通知访问权限；24 小时内最多 5 条，内容会发送给聊天模型。") { value ->
                        updateSystemToolsSetting { it.copy(notificationsContextInjectionEnabled = value) }
                    }
                })
            }
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("记忆与时限") }, items = buildCardGroupItems {
                    injectionToggle("相关记忆", option.memoryContextInjectionEnabled, enabled,
                        "仅当前助手与全局内置记忆，不调用外部 MCP。") { value ->
                        updateSystemToolsSetting { it.copy(memoryContextInjectionEnabled = value) }
                    }
                    item(headlineContent = { Text("记忆条数：${option.memoryContextInjectionLimit}") },
                        supportingContent = {
                            InjectionSlider(option.memoryContextInjectionLimit, 1..20,
                                enabled && option.memoryContextInjectionEnabled) { value ->
                                updateSystemToolsSetting { it.copy(memoryContextInjectionLimit = value) }
                            }
                        })
                    item(headlineContent = { Text("单项等待上限：${option.extraInfoInjectionTimeoutSeconds} 秒") },
                        supportingContent = {
                            InjectionSlider(option.extraInfoInjectionTimeoutSeconds, 1..30, enabled) { value ->
                                updateSystemToolsSetting { it.copy(extraInfoInjectionTimeoutSeconds = value) }
                            }
                        })
                })
            }
            if (diagnostics.items.isNotEmpty()) {
                item {
                    Text(
                        "最近一次${if (diagnostics.proactive) "后台" else "聊天"}读取 · " +
                            java.text.DateFormat.getTimeInstance().format(java.util.Date(diagnostics.timestamp)),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                diagnostics.items.forEach { diagnostic ->
                    item { Text("${diagnostic.name}：${diagnostic.summary}", modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
    }
}

private fun CardGroupScope.injectionToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    privacy: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    item(
        headlineContent = { Text(title) },
        supportingContent = privacy?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun InjectionSlider(value: Int, range: IntRange, enabled: Boolean, onCommit: (Int) -> Unit) {
    var pending by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    Slider(
        value = pending,
        onValueChange = { pending = it },
        onValueChangeFinished = { onCommit(pending.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        enabled = enabled,
    )
}
