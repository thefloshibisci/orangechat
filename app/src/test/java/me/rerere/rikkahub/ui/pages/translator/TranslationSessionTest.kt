package me.rerere.rikkahub.ui.pages.translator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionTest {
    private data class Saved(val source: String, val result: String, val language: String, val model: Uuid)

    private class Fixture(scope: CoroutineScope) {
        var settings = Settings.dummy()
        var generate: (Settings, String, Locale) -> Flow<String> = { _, _, _ -> flowOf("译文") }
        var load: suspend () -> Settings = { settings }
        var saveError: Exception? = null
        val saved = mutableListOf<Saved>()
        val errors = mutableListOf<Throwable>()
        val requests = mutableListOf<Triple<Settings, String, Locale>>()
        val session = TranslationSession(
            scope = scope,
            loadSettings = { load() },
            generate = { config, source, language ->
                requests += Triple(config, source, language)
                generate(config, source, language)
            },
            save = { source, result, language, model ->
                saveError?.let { throw it }
                saved += Saved(source, result, language, model)
            },
            onError = { errors += it },
        )
    }

    @Test fun `cumulative chunks save one final result with exact source language and request model`() = runTest {
        val f = Fixture(this)
        f.generate = { _, _, _ -> flowOf("译", "译文", "译文完成") }
        f.session.updateInputText(" source\n text ")
        f.session.updateTargetLanguage(Locale.TRADITIONAL_CHINESE)
        f.session.translate()
        assertTrue(f.session.translating.value)
        runCurrent()
        assertEquals(listOf(Saved(" source\n text ", "译文完成", "zh-TW", f.settings.translateModeId)), f.saved)
        assertEquals("译文完成", f.session.translatedText.value)
        assertFalse(f.session.translating.value)
    }

    @Test fun `blank input starts no request and empty successful response creates no history`() = runTest {
        val f = Fixture(this)
        f.session.updateInputText(" \n ")
        f.session.translate()
        runCurrent()
        assertTrue(f.requests.isEmpty())
        f.generate = { _, _, _ -> flowOf("", " \n") }
        f.session.updateInputText("hello")
        f.session.translate()
        runCurrent()
        assertEquals(1, f.requests.size)
        assertTrue(f.saved.isEmpty())
        assertFalse(f.session.translating.value)
    }

    @Test fun `provider failure retains partial result but never saves a successful history`() = runTest {
        val f = Fixture(this)
        val failure = IllegalStateException("provider interrupted")
        f.generate = { _, _, _ -> flow { emit("partial"); throw failure } }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        assertEquals("partial", f.session.translatedText.value)
        assertEquals(listOf(failure), f.errors)
        assertTrue(f.saved.isEmpty())
        assertFalse(f.session.translating.value)
    }

    @Test fun `user cancel is silent and never stores partial output`() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.generate = { _, _, _ -> flow {
            try { emit("partial"); awaitCancellation() } finally { cancelled = true }
        } }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        f.session.cancelTranslation()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(f.session.translating.value)
        assertEquals("partial", f.session.translatedText.value)
        assertTrue(f.saved.isEmpty())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `old cancellation cleanup cannot stop or replace a newer request`() = runTest {
        val f = Fixture(this)
        val releaseOldCleanup = CompletableDeferred<Unit>()
        var calls = 0
        f.generate = { _, _, _ ->
            val call = ++calls
            flow {
                try { emit(if (call == 1) "old" else "new"); awaitCancellation() }
                finally { if (call == 1) withContext(NonCancellable) { releaseOldCleanup.await() } }
            }
        }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        f.session.translate()
        runCurrent()
        releaseOldCleanup.complete(Unit)
        runCurrent()
        assertEquals("new", f.session.translatedText.value)
        assertTrue(f.session.translating.value)
        assertTrue(f.saved.isEmpty())
        assertTrue(f.errors.isEmpty())
        f.session.cancelTranslation()
        runCurrent()
    }

    @Test fun `restore cancels request and immediately supplies restored language without changing model`() = runTest {
        val f = Fixture(this)
        var cancelled = false
        f.generate = { _, _, _ -> flow {
            try { emit("partial"); awaitCancellation() } finally { cancelled = true }
        } }
        f.session.updateInputText("active")
        f.session.translate()
        runCurrent()
        val entry = history()
        f.session.restoreHistory(entry)
        assertEquals(entry.sourceText, f.session.inputText.value)
        assertEquals(entry.translatedText, f.session.translatedText.value)
        assertEquals(Locale.JAPANESE, f.session.targetLanguage.value)
        assertFalse(f.session.translating.value)
        assertEquals(1, f.requests.size)
        f.generate = { _, _, _ -> flowOf("new result") }
        f.session.translate()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(Locale.JAPANESE, f.requests.last().third)
        assertEquals(f.settings.translateModeId, f.requests.last().first.translateModeId)
        assertNotEquals(entry.modelId, f.settings.translateModeId.toString())
        assertEquals(listOf(Saved(entry.sourceText, "new result", "ja", f.settings.translateModeId)), f.saved)
    }

    @Test fun `restored result survives delayed cancelled request cleanup`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        f.generate = { _, _, _ -> flow {
            try { emit("old"); awaitCancellation() }
            finally { withContext(NonCancellable) { release.await() } }
        } }
        f.session.updateInputText("active")
        f.session.translate()
        runCurrent()
        f.session.restoreHistory(history())
        release.complete(Unit)
        runCurrent()
        assertEquals("歴史の訳文", f.session.translatedText.value)
        assertTrue(f.saved.isEmpty())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `editing source cancels translation and clears mismatched result`() = runTest {
        val f = Fixture(this)
        f.generate = { _, _, _ -> flow { emit("old"); awaitCancellation() } }
        f.session.updateInputText("before")
        f.session.translate()
        runCurrent()
        f.session.updateInputText("after")
        runCurrent()
        assertEquals("after", f.session.inputText.value)
        assertEquals("", f.session.translatedText.value)
        assertFalse(f.session.translating.value)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `changing target language cancels old result and applies immediately to next request`() = runTest {
        val f = Fixture(this)
        f.generate = { _, _, _ -> flow { emit("old"); awaitCancellation() } }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        f.session.updateTargetLanguage(Locale.KOREAN)
        runCurrent()
        assertEquals("", f.session.translatedText.value)
        assertTrue(f.saved.isEmpty())
        f.generate = { _, _, _ -> flowOf("한국어") }
        f.session.translate()
        runCurrent()
        assertEquals("ko", f.saved.single().language)
    }

    @Test fun `changing selected model during generation does not relabel saved request`() = runTest {
        val f = Fixture(this)
        val finish = CompletableDeferred<Unit>()
        val originalModel = f.settings.translateModeId
        f.generate = { _, _, _ -> flow { emit("partial"); finish.await(); emit("complete") } }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        f.settings = f.settings.copy(translateModeId = Uuid.random())
        finish.complete(Unit)
        runCurrent()
        assertEquals(originalModel, f.saved.single().model)
        assertNotEquals(f.settings.translateModeId, f.saved.single().model)
    }

    @Test fun `history write failure reports storage failure while preserving completed translation`() = runTest {
        val f = Fixture(this)
        f.saveError = IllegalStateException("database unavailable")
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        assertEquals("译文", f.session.translatedText.value)
        assertFalse(f.session.translating.value)
        assertTrue(f.saved.isEmpty())
        assertEquals("翻译已完成，但历史保存失败", f.errors.single().message)
        assertSame(f.saveError, f.errors.single().cause)
    }

    @Test fun `synchronous completion owns job before first chunk and does not remain loading`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = Fixture(this)
            f.session.updateInputText("source")
            f.session.translate()
            assertEquals("译文", f.session.translatedText.value)
            assertEquals(1, f.saved.size)
            assertFalse(f.session.translating.value)
        }

    @Test fun `cancel during settings load never starts provider or persists result`() = runTest {
        val f = Fixture(this)
        f.load = { awaitCancellation() }
        f.session.updateInputText("source")
        f.session.translate()
        runCurrent()
        f.session.restoreHistory(history())
        runCurrent()
        assertEquals("歴史の訳文", f.session.translatedText.value)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.saved.isEmpty())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `request settings use visible optimistic model while persisted value lags`() = runTest {
        val old = Settings(init = false)
        val visible = old.copy(translateModeId = Uuid.random())
        var diskReads = 0
        val request = translationRequestSettings(visible) { diskReads++; old }
        assertEquals(visible.translateModeId, request.translateModeId)
        assertEquals(0, diskReads)
    }

    @Test fun `initial dummy settings await persisted configuration before requesting`() = runTest {
        val loaded = Settings(init = false)
        var diskReads = 0
        val request = translationRequestSettings(Settings.dummy()) { diskReads++; loaded }
        assertEquals(loaded, request)
        assertFalse(request.init)
        assertEquals(1, diskReads)
    }

    @Test fun `late storage failure from cancelled request cannot report over restored history`() = runTest {
        val release = CompletableDeferred<Unit>()
        val errors = mutableListOf<Throwable>()
        val session = TranslationSession(
            scope = this, loadSettings = { Settings.dummy() }, generate = { _, _, _ -> flowOf("done") },
            save = { _, _, _, _ ->
                withContext(NonCancellable) { release.await(); throw IllegalStateException("late failure") }
            },
            onError = { errors += it },
        )
        session.updateInputText("source")
        session.translate()
        runCurrent()
        session.restoreHistory(history())
        release.complete(Unit)
        runCurrent()
        assertEquals("歴史の訳文", session.translatedText.value)
        assertFalse(session.translating.value)
        assertTrue(errors.isEmpty())
    }

    private fun history() = TranslationHistoryEntity(
        id = "history", sourceText = "original", translatedText = "歴史の訳文", targetLanguage = "ja",
        modelId = "deleted-model", createAt = 1,
    )
}
