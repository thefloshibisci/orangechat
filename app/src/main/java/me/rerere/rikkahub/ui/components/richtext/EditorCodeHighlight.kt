package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.LocalDarkMode

internal data class EditorHighlightRequest(val text: String, val language: String, val darkMode: Boolean)

internal data class EditorHighlightResult(val request: EditorHighlightRequest, val text: AnnotatedString)

/** Owned by one editor; callers cancel the previous update when its input changes. */
internal class EditorCodeHighlighter(
    private val highlight: suspend (EditorHighlightRequest) -> AnnotatedString,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val beforeHighlight: suspend () -> Unit = { delay(100) },
    private val maxCacheEntries: Int = 4,
    private val maxCacheCharacters: Int = 64 * 1024,
) {
    private val cached = LinkedHashMap<EditorHighlightRequest, AnnotatedString>(4, 0.75f, true)
    private var cachedCharacters = 0
    private var revision = 0L
    private val mutableResult = MutableStateFlow<EditorHighlightResult?>(null)
    val result = mutableResult.asStateFlow()

    suspend fun update(request: EditorHighlightRequest) {
        val requestedRevision = ++revision
        if (request.text.isEmpty()) {
            mutableResult.value = EditorHighlightResult(request, AnnotatedString(""))
            return
        }
        cached[request]?.let {
            mutableResult.value = EditorHighlightResult(request, it)
            return
        }

        // A short quiet interval coalesces typing before entering the shared JS highlighter.
        beforeHighlight()
        val text = try {
            withContext(dispatcher) { highlight(request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (revision == requestedRevision) {
                mutableResult.value = EditorHighlightResult(request, AnnotatedString(request.text))
            }
            return
        }
        currentCoroutineContext().ensureActive()
        if (revision != requestedRevision) return

        // Identity offsets require exact source text, including whitespace and surrogate pairs.
        val unchangedText = text.takeIf { it.text == request.text } ?: AnnotatedString(request.text)
        if (maxCacheEntries > 0 && request.text.length <= maxCacheCharacters) {
            cached[request] = unchangedText
            cachedCharacters += request.text.length
            while (cached.size > maxCacheEntries || cachedCharacters > maxCacheCharacters) {
                val oldest = cached.entries.iterator()
                cachedCharacters -= oldest.next().key.text.length
                oldest.remove()
            }
        }
        mutableResult.value = EditorHighlightResult(request, unchangedText)
    }
}

internal class CachedCodeVisualTransformation(private val highlighted: AnnotatedString?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = highlighted?.takeIf { it.text == text.text } ?: text,
        offsetMapping = OffsetMapping.Identity,
    )
}

/** Input and cursor rendering always return immediately, even while highlighting is in flight. */
@Composable
fun rememberHighlightCodeVisualTransformation(
    text: String,
    language: String,
    highlighter: Highlighter = LocalHighlighter.current,
    darkMode: Boolean = LocalDarkMode.current,
): VisualTransformation {
    val editorHighlighter = remember(highlighter) {
        EditorCodeHighlighter(highlight = { request ->
            val palette = if (request.darkMode) AtomOneDarkPalette else AtomOneLightPalette
            val tokens = highlighter.highlight(request.text, request.language)
            buildAnnotatedString {
                tokens.forEach { token -> buildHighlightText(token, palette) }
            }
        })
    }
    val request = remember(text, language, darkMode) { EditorHighlightRequest(text, language, darkMode) }
    val result by editorHighlighter.result.collectAsState()
    LaunchedEffect(editorHighlighter, request) { editorHighlighter.update(request) }
    val highlighted = result?.takeIf { it.request == request }?.text
    return remember(highlighted) { CachedCodeVisualTransformation(highlighted) }
}
