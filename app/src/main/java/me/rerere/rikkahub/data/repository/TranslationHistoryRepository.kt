package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlin.uuid.Uuid

private const val MAX_HISTORY_COUNT = 100

class TranslationHistoryRepository(
    private val dao: TranslationHistoryDAO,
) {
    fun observeRecent(): Flow<List<TranslationHistoryEntity>> = dao.observeRecent()

    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: Uuid,
    ) {
        if (sourceText.isBlank() || translatedText.isBlank()) return
        dao.saveAndTrim(
            TranslationHistoryEntity(
                id = Uuid.random().toString(),
                sourceText = sourceText,
                translatedText = translatedText,
                targetLanguage = targetLanguage,
                modelId = modelId.toString(),
                createAt = System.currentTimeMillis(),
            ),
            MAX_HISTORY_COUNT,
        )
    }

    suspend fun deleteById(id: String) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
