package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The smaller footer frees 18dp for the form and lowers its buttons by 9dp. */
@Composable
internal fun ModelEditorLayout(modifier: Modifier = Modifier, title: @Composable () -> Unit,
    content: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        title()
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth().testTag("model-editor-content")) { content() }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 7.dp).testTag("model-editor-actions"),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
    }
}
