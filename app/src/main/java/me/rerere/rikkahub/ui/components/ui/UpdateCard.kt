/*
 * 橘瓣 OrangeChat. Derived from RikkaHub, GNU AGPL v3.
 */
package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.Version
import me.rerere.rikkahub.utils.onSuccess

@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    state.onSuccess { info ->
        if (Version(info.version) > Version(BuildConfig.VERSION_NAME)) {
            Card(onClick = { vm.updateChecker.presentUpdate(info) }) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("发现新版本 ${info.version}", style = MaterialTheme.typography.titleMedium)
                    Text("查看更新说明并下载", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
