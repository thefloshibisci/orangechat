package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.codex.*
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Uses the current provider form's typography and spacing; no screen-level theme changes. */
@Composable
fun CodexProviderConfigure(provider: ProviderSetting.Codex, onEdit: (ProviderSetting.Codex) -> Unit) {
    val repository = koinInject<CodexAccountRepository>()
    val oauth = koinInject<CodexOAuthManager>()
    val manager = koinInject<ProviderManager>()
    val accounts by repository.accounts.collectAsStateWithLifecycle()
    val storageUnavailable by repository.storageUnavailable.collectAsStateWithLifecycle()
    val status by oauth.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val latestProvider by rememberUpdatedState(provider)
    val latestEdit by rememberUpdatedState(onEdit)

    suspend fun loadModels() {
        val current = latestProvider
        val models = manager.getProviderByType(current).listModels(current)
        if (models.isEmpty()) { feedback = "官方未返回可用模型，请稍后重试"; return }
        val merged = models.map { model ->
            current.models.firstOrNull { it.modelId == model.modelId }?.let { old ->
                old.copy(inputModalities = model.inputModalities, abilities = model.abilities,
                    codexReasoningEfforts = model.codexReasoningEfforts)
            } ?: model
        }
        latestEdit(latestProvider.copy(models = current.models.filter { old -> merged.none { it.modelId == old.modelId } } + merged))
        feedback = "已获取 ${models.size} 个模型"
    }

    LaunchedEffect(status) {
        if (status is CodexOAuthStatus.Success) {
            feedback = "ChatGPT 登录成功"
            try { loadModels() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = "登录成功，模型获取失败；请点击刷新模型重试" }
            oauth.consumeResult()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ChatGPT 订阅 · OpenAI Codex", style = MaterialTheme.typography.titleMedium)
        Text("直接连接 OpenAI 官方服务。OAuth 凭据只保存在本机，不随配置或备份导出。", style = MaterialTheme.typography.bodySmall)
        Text("当前：${if (storageUnavailable) "本机凭据暂时无法读取" else if (accounts.isEmpty()) "未登录" else "已登录 ${accounts.size} 个账号"}")
        if (storageUnavailable) {
            Text("凭据仍保留在本机。请解锁设备后重试，无需立即重新登录。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scope.launch {
                try { repository.reloadCredentials() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { feedback = "凭据仍无法读取，请稍后重试" }
            } }) { Text("重试读取凭据") }
        }
        if (!storageUnavailable && status !is CodexOAuthStatus.Waiting) {
            Button(onClick = { oauth.startLogin() }, modifier = Modifier.fillMaxWidth()) { Text("使用 ChatGPT 登录（设备码）") }
            OutlinedButton(onClick = { oauth.startLogin(device = false) }, modifier = Modifier.fillMaxWidth()) { Text("使用浏览器登录（PKCE）") }
        }
        when (val current = status) {
            is CodexOAuthStatus.Waiting -> {
                if (current.userCode != null) {
                    Text("在 ChatGPT 安全设置开启设备码登录，再输入下方一次性代码：")
                    SelectionContainer { Text(current.userCode, style = MaterialTheme.typography.headlineSmall) }
                    OutlinedButton(onClick = { try { oauth.openDevicePage() } catch (_: Exception) { feedback = "无法打开浏览器" } }) { Text("打开 ChatGPT 授权页面") }
                } else Text("等待浏览器授权；完成后请返回橘瓣。")
                TextButton(onClick = oauth::cancel) { Text("取消登录") }
            }
            is CodexOAuthStatus.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        accounts.forEach { account ->
            HorizontalDivider()
            Text(account.email.ifBlank { account.name }, style = MaterialTheme.typography.titleSmall)
            val tokenState = when {
                account.tokenStatus == CodexTokenStatus.INVALID -> "失效，请重新登录"
                account.expiresAt <= System.currentTimeMillis() -> "已过期，下次请求自动刷新"
                else -> "有效，临近到期自动刷新"
            }
            Text("Token：$tokenState", style = MaterialTheme.typography.bodySmall)
            Text("到期：${codexTime(account.expiresAt / 1000)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("参与账号轮换")
                Switch(checked = account.enabled, enabled = !busy, onCheckedChange = { enabled -> scope.launch {
                    try { repository.setEnabled(account.id, enabled) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { feedback = "账号状态保存失败" }
                } })
            }
            val windows = listOfNotNull(account.usage?.primary, account.usage?.secondary)
            if (windows.isEmpty()) Text("额度尚未获取", style = MaterialTheme.typography.bodySmall)
            windows.forEach { window ->
                Text("${window.windowMinutes?.let { "${it} 分钟窗口" } ?: "额度"}：已用 ${window.usedPercent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                window.resetsAt?.let { Text("重置：${codexTime(it)}", style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try { repository.refreshAccount(account.id); feedback = "额度已更新" }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { feedback = "额度获取失败；请检查登录和网络" }
                    finally { busy = false }
                } }) { Text("刷新额度") }
                TextButton(enabled = !busy, onClick = { scope.launch {
                    oauth.cancel()
                    try { repository.delete(account.id); feedback = "已退出，已清除本机凭据" }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { feedback = "清除凭据失败，请重试" }
                } }) { Text("退出登录") }
            }
        }
        if (accounts.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { loadModels() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = "模型获取失败，请检查登录和网络" }
            finally { busy = false }
        } }) { Text("刷新模型") }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun codexTime(seconds: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
