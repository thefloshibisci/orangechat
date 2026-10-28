package me.rerere.rikkahub.data.ai

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.toLocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RecentChatsPromptTest {
    @Test fun promptReadsOnlyHeadersAndPreservesLimitOrderingDatesAndAssistantIsolation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = me.rerere.rikkahub.AppScope()
        try {
            val assistant = Assistant()
            val dao = db.conversationDao()
            val time = 1_793_059_200_000L
            repeat(12) { index ->
                dao.insert(ConversationEntity(
                    id = Uuid.random().toString(), assistantId = assistant.id.toString(),
                    title = "chat-$index", nodes = "invalid legacy message JSON",
                    createAt = time, updateAt = time + index * 1000,
                    chatSuggestions = "invalid suggestions", isPinned = index == 0,
                    compressionSummaries = "invalid summaries",
                ))
            }
            dao.insert(ConversationEntity(Uuid.random().toString(), Uuid.random().toString(),
                "other-assistant", "[]", time, time + 99999, "[]", true))
            val noBodyReads = object : MessageNodeDAO by db.messageNodeDao() {
                override suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity> =
                    error("Recent chat prompt must never read message bodies")
            }
            val repo = ConversationRepository(dao, noBodyReads, db.favoriteDao(), db,
                FilesManager(context, FilesRepository(db.managedFileDao()), scope), MessageFtsManager(db))
            val headers = repo.getRecentConversationHeaders(assistant.id)
            assertEquals(listOf(0, 11, 10, 9, 8, 7, 6, 5, 4, 3).map { "chat-$it" }, headers.map { it.title })
            val prompt = buildRecentChatsPrompt(assistant, repo)
            assertTrue(prompt.contains("**Recent Chats**"))
            assertTrue(prompt.indexOf("chat-0") < prompt.indexOf("chat-11"))
            assertTrue(prompt.contains(Instant.ofEpochMilli(time).toLocalDate().toString()))
            assertFalse(prompt.contains("other-assistant"))
            assertFalse(prompt.contains("invalid"))
            assertEquals("", buildRecentChatsPrompt(Assistant(), repo))
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
