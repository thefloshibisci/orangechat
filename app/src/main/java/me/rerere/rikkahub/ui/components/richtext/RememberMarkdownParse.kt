package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache hits render immediately. Even the very first cache miss is parsed off the UI thread. */
@Composable
internal fun <T : Any> rememberMarkdownParse(
    content: String,
    cache: MarkdownParseCache<T>,
    parse: (String) -> T,
): State<T?> = produceState(initialValue = cache.get(content), content, cache) {
    // Keep the previous result visible during streaming. Cancellation prevents a slow old parse
    // from publishing over a newer source after withContext returns.
    value = withContext(Dispatchers.Default) { cache.getOrParse(content, parse) }
}
