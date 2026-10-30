package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalDisplaySettings

/** Memory pages use CardGroup, which reads the display settings the app normally provides. */
@Composable
internal fun MemoryTestTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) { MaterialTheme(content = content) }
}
