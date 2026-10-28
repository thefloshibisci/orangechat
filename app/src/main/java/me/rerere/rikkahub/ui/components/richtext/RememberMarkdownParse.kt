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

/**
 * Cache hits render immediately. A short message missing from the cache is parsed inline on its
 * first composition, so the bubble appears at full size instead of growing from an empty box.
 * Long messages and every later streaming update are still parsed off the UI thread.
 */
@Composable
internal fun <T : Any> rememberMarkdownParse(
    content: String,
    cache: MarkdownParseCache<T>,
    inlineMaxChars: Int = INLINE_PARSE_MAX_CHARS,
    parse: (String) -> T,
): State<T?> {
    // Only the first composition's value matters: produceState keeps its state across key changes.
    val initial = remember(cache) {
        cache.get(content) ?: content.takeIf { it.length <= inlineMaxChars }?.let {
            runCatching { cache.getOrParse(it, parse) }.getOrNull()
        }
    }
    // The parser is CPU-bound and does not suspend. Cancelling a previous effect alone would
    // allow rapid token updates to start many simultaneous parses of the same growing message.
    val parseLock = remember(cache) { Mutex() }
    return produceState(initialValue = initial, key1 = content, key2 = cache) {
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

/** Parsing a few thousand characters takes well under a frame. */
internal const val INLINE_PARSE_MAX_CHARS = 4000
