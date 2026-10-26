/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.repository.TranslationHistoryRepository
import java.util.Locale

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val historyRepository: TranslationHistoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val errorFlow = MutableSharedFlow<Throwable>()
    private val session = TranslationSession(
        scope = viewModelScope,
        loadSettings = {
            translationRequestSettings(settingsStore.settingsFlow.value) { settingsStore.settingsFlowRaw.first() }
        },
        generate = { requestSettings, source, language ->
            generationHandler.translateText(requestSettings, source, language)
        },
        save = historyRepository::add,
        onError = { errorFlow.emit(it) },
    )
    val translating = session.translating
    val inputText = session.inputText
    val translatedText = session.translatedText
    val targetLanguage = session.targetLanguage
    val history = Pager(PagingConfig(pageSize = 20, enablePlaceholders = false)) {
        historyRepository.pagingAll()
    }.flow.cachedIn(viewModelScope)

    fun updateSettings(settings: Settings) {
        val previous = this.settings.value
        viewModelScope.launch {
            settingsStore.updateFrom(previous, settings)
        }
    }

    fun updateInputText(text: String) {
        session.updateInputText(text)
    }

    fun updateTargetLanguage(language: Locale) {
        session.updateTargetLanguage(language)
    }

    fun translate() = session.translate()

    fun cancelTranslation() = session.cancelTranslation()

    fun restoreHistory(entry: TranslationHistoryEntity) = session.restoreHistory(entry)

    fun deleteHistory(entry: TranslationHistoryEntity) = editHistory { historyRepository.deleteById(entry.id) }

    fun clearHistory() = editHistory { historyRepository.deleteAll() }

    private fun editHistory(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorFlow.emit(error)
            }
        }
    }
}

/** The editor can already show a newer optimistic snapshot while its disk write is still pending. */
internal suspend fun translationRequestSettings(current: Settings, loadPersisted: suspend () -> Settings): Settings =
    if (current.init) loadPersisted() else current
