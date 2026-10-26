package me.rerere.rikkahub.ui.pages.translator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import java.util.Locale
import kotlin.uuid.Uuid

/** Owns the request snapshot and prevents a cancelled request from publishing into a newer edit. */
internal class TranslationSession(
    private val scope: CoroutineScope,
    private val loadSettings: suspend () -> Settings,
    private val generate: (Settings, String, Locale) -> Flow<String>,
    private val save: suspend (String, String, String, Uuid) -> Unit,
    private val onError: suspend (Throwable) -> Unit,
) {
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    private val _translatedText = MutableStateFlow("")
    val translatedText = _translatedText.asStateFlow()
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage = _targetLanguage.asStateFlow()
    private val _translating = MutableStateFlow(false)
    val translating = _translating.asStateFlow()
    private var currentJob: Job? = null

    fun updateInputText(text: String) {
        if (text == _inputText.value) return
        cancelTranslation()
        _inputText.value = text
        _translatedText.value = ""
    }

    fun updateTargetLanguage(language: Locale) {
        if (language == _targetLanguage.value) return
        cancelTranslation()
        _targetLanguage.value = language
        _translatedText.value = ""
    }

    fun restoreHistory(entry: TranslationHistoryEntity) {
        cancelTranslation()
        _inputText.value = entry.sourceText
        _translatedText.value = entry.translatedText
        _targetLanguage.value = Locale.forLanguageTag(entry.targetLanguage)
    }

    fun translate() {
        val source = _inputText.value
        if (source.isBlank()) return
        val language = _targetLanguage.value
        cancelTranslation()
        _translating.value = true
        _translatedText.value = ""
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val requestSettings = loadSettings()
                var result = ""
                generate(requestSettings, source, language).collect { text ->
                    ensureActive()
                    if (currentJob == coroutineContext[Job]) {
                        result = text
                        _translatedText.value = text
                    }
                }
                ensureActive()
                if (currentJob == coroutineContext[Job] && result.isNotBlank()) {
                    try {
                        save(source, result, language.toLanguageTag(), requestSettings.translateModeId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ensureActive()
                        if (currentJob == coroutineContext[Job]) {
                            onError(IllegalStateException("翻译已完成，但历史保存失败", error))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentJob == coroutineContext[Job]) onError(error)
            } finally {
                if (currentJob == coroutineContext[Job]) {
                    currentJob = null
                    _translating.value = false
                }
            }
        }
        currentJob = job
        job.start()
    }

    fun cancelTranslation() {
        val previous = currentJob
        currentJob = null
        previous?.cancel()
        _translating.value = false
    }
}
