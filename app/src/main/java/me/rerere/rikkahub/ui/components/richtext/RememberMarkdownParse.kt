package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cache hits render immediately. Even the very first cache miss is parsed off the UI thread. */
@Composable
internal fun <T : Any> rememberMarkdownParse(
    content: String,
    cache: MarkdownParseCache<T>,
    parse: (String) -> T,
): State<T?> {
    // The parser is CPU-bound and does not suspend. Cancelling a previous effect alone would
    // allow rapid token updates to start many simultaneous parses of the same growing message.
    val parseLock = remember(cache) { Mutex() }
    return produceState(initialValue = cache.get(content), key1 = content, key2 = cache) {
        // Keep the previous result visible during streaming. Cancellation prevents a slow old
        // parse from publishing over a newer source after withContext returns.
        try {
            value = withContext(Dispatchers.Default) {
                parseLock.withLock {
                    ensureActive()
                    cache.getOrParse(content, parse)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            // A malformed streaming fragment must not crash the UI or prevent the next update.
            exception.printStackTrace()
        }
    }
}
