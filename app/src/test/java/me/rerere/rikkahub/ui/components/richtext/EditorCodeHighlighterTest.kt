package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCodeHighlighterTest {
    @Test
    fun `typing cancels quiet interval and only latest edit is highlighted`() = runBlocking {
        val quietInterval = CompletableDeferred<Unit>()
        val highlighted = mutableListOf<String>()
        val editor = EditorCodeHighlighter(
            highlight = { highlighted += it.text; styled(it.text) },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = { quietInterval.await() },
        )

        val first = launch(Dispatchers.Unconfined) { editor.update(request("a")) }
        first.cancelAndJoin()
        val second = launch(Dispatchers.Unconfined) { editor.update(request("ab")) }
        second.cancelAndJoin()
        val latest = launch(Dispatchers.Unconfined) { editor.update(request("abc")) }

        assertTrue(highlighted.isEmpty())
        assertNull(editor.result.value)
        val immediate = AnnotatedString("abc")
        assertSame(immediate, CachedCodeVisualTransformation(null).filter(immediate).text)

        quietInterval.complete(Unit)
        latest.join()
        assertEquals(listOf("abc"), highlighted)
        assertEquals("abc", editor.result.value?.text?.text)
    }

    @Test
    fun `a slow older result never replaces a newer text language or theme`() = runBlocking {
        val olderResult = CompletableDeferred<Unit>()
        val editor = EditorCodeHighlighter(
            highlight = {
                if (it.language == "json") olderResult.await()
                styled(it.text)
            },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
        )
        val older = launch(Dispatchers.Unconfined) { editor.update(request("old")) }
        val latest = EditorHighlightRequest("latest", "javascript", true)
        editor.update(latest)
        olderResult.complete(Unit)
        older.join()

        assertEquals(latest, editor.result.value?.request)
        assertEquals(styled("latest"), editor.result.value?.text)
    }

    @Test
    fun `cancelled highlight is not published or cached as success`() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var started = 0
        val editor = EditorCodeHighlighter(
            highlight = { started++; waiting.await(); styled(it.text) },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
        )
        val cancelled = launch(Dispatchers.Unconfined) { editor.update(request("value")) }
        cancelled.cancelAndJoin()
        assertNull(editor.result.value)
        waiting.complete(Unit)
        editor.update(request("value"))

        assertEquals(2, started)
        assertEquals("value", editor.result.value?.text?.text)
    }

    @Test
    fun `cache is keyed by source language and palette`() = runBlocking {
        var runs = 0
        val editor = EditorCodeHighlighter(
            highlight = { runs++; styled(it.text) },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
        )
        val jsonLight = request("same")
        editor.update(jsonLight)
        val original = editor.result.value?.text
        editor.update(jsonLight.copy(language = "javascript"))
        editor.update(jsonLight.copy(darkMode = true))
        editor.update(jsonLight)

        assertEquals(3, runs)
        assertSame(original, editor.result.value?.text)
    }

    @Test
    fun `cache evicts least recently used text within character budget`() = runBlocking {
        val runs = mutableListOf<String>()
        val editor = EditorCodeHighlighter(
            highlight = { runs += it.text; styled(it.text) },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
            maxCacheEntries = 2,
            maxCacheCharacters = 8,
        )
        for (text in listOf("aaaa", "bbbb", "aaaa", "cccc", "aaaa", "bbbb", "oversized", "oversized")) {
            editor.update(request(text))
        }

        assertEquals(listOf("aaaa", "bbbb", "cccc", "bbbb", "oversized", "oversized"), runs)
    }

    @Test
    fun `highlight errors keep exact input and allow later retry`() = runBlocking {
        var fail = true
        var runs = 0
        val editor = EditorCodeHighlighter(
            highlight = {
                runs++
                if (fail) error("unsupported syntax")
                styled(it.text)
            },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
        )
        val request = request("{broken: \"😀\"}\n")
        editor.update(request)
        assertEquals(AnnotatedString(request.text), editor.result.value?.text)
        fail = false
        editor.update(request)

        assertEquals(2, runs)
        assertEquals(styled(request.text), editor.result.value?.text)
    }

    @Test
    fun `invalid highlighter text is rejected and identity mapping always preserves cursor offsets`() = runBlocking {
        val text = "{\"emoji\":\"😀\"}\r\n"
        val editor = EditorCodeHighlighter(
            highlight = { styled("different length") },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = {},
        )
        editor.update(request(text))
        assertEquals(AnnotatedString(text), editor.result.value?.text)
        val input = AnnotatedString(text)
        val transformation = CachedCodeVisualTransformation(styled(text))
        val result = transformation.filter(input)
        assertEquals(styled(text), result.text)
        for (offset in 0..text.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
        val newInput = AnnotatedString(text + "more")
        assertSame(newInput, transformation.filter(newInput).text)
    }

    @Test
    fun `empty input clears highlight without waiting or calling worker`() = runBlocking {
        var invoked = false
        val editor = EditorCodeHighlighter(
            highlight = { invoked = true; styled(it.text) },
            dispatcher = Dispatchers.Unconfined,
            beforeHighlight = { invoked = true },
        )
        editor.update(request(""))

        assertFalse(invoked)
        assertEquals(AnnotatedString(""), editor.result.value?.text)
    }

    private fun request(text: String) = EditorHighlightRequest(text, "json", false)

    private fun styled(text: String) = buildAnnotatedString {
        withStyle(SpanStyle(color = Color.Red)) { append(text) }
    }
}
