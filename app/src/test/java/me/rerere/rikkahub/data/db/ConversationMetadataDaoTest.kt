/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class ConversationMetadataDaoTest {
    @Test
    fun metadataUpdatesPreserveMessagesCompressionAndConversationFields() = runBlocking {
        withDatabase { db ->
            val dao = db.conversationDao()
            val initial = conversation()
            val node = MessageNodeEntity(
                id = "node", conversationId = initial.id, nodeIndex = 0,
                messages = "branch alternatives and latest streaming content", selectIndex = 1,
            )
            dao.insert(initial)
            db.messageNodeDao().insert(node)

            assertEquals(1, dao.updateTitle(initial.id, "generated title"))
            assertEquals(1, dao.updateSuggestions(initial.id, "[\"next\"]"))

            assertEquals(
                initial.copy(title = "generated title", chatSuggestions = "[\"next\"]"),
                dao.getConversationById(initial.id),
            )
            assertEquals(listOf(node), db.messageNodeDao().getNodesByIds(listOf(node.id)))
        }
    }

    @Test
    fun lateMetadataCannotRecreateDeletedConversationOrMessages() = runBlocking {
        withDatabase { db ->
            val dao = db.conversationDao()
            val initial = conversation()
            dao.insert(initial)
            dao.deleteById(initial.id)

            assertEquals(0, dao.updateTitle(initial.id, "late title"))
            assertEquals(0, dao.updateSuggestions(initial.id, "[\"late\"]"))
            assertNull(dao.getConversationById(initial.id))
            assertEquals(0, dao.countAll())
            assertEquals(0, db.messageNodeDao().getNodeCountOfConversation(initial.id))
        }
    }

    @Test
    fun actualMostRecentTargetIgnoresPinnedOrderingAndOtherAssistants() = runBlocking {
        withDatabase { db ->
            val dao = db.conversationDao()
            val oldPinned = conversation().copy(id = "pinned", updateAt = 10)
            val recent = conversation().copy(id = "recent", updateAt = 100, isPinned = false)
            val otherAssistant = conversation().copy(id = "other", assistantId = "other-assistant", updateAt = 500)
            dao.insert(oldPinned)
            dao.insert(recent)
            dao.insert(otherAssistant)

            assertEquals("recent", dao.getMostRecentConversationIdOfAssistant("assistant"))
            assertEquals("pinned", dao.getRecentConversationsOfAssistant("assistant", 1).single().id)
            assertNull(dao.getMostRecentConversationIdOfAssistant("missing-assistant"))
        }
    }

    @Test
    fun mostRecentTargetTiesUseCreationTimeThenStableId() = runBlocking {
        withDatabase { db ->
            val dao = db.conversationDao()
            for ((id, created) in listOf("older" to 1L, "b" to 2L, "a" to 2L)) {
                dao.insert(conversation().copy(id = id, createAt = created, updateAt = 100))
            }
            assertEquals("a", dao.getMostRecentConversationIdOfAssistant("assistant"))
        }
    }

    @Test
    fun concurrentAtomicPinTogglesPreserveAllOtherConversationAndMessageFields() = runBlocking {
        withDatabase { db ->
            val dao = db.conversationDao()
            val initial = conversation()
            val message = MessageNodeEntity("message", initial.id, 0, "original history", 0)
            dao.insert(initial)
            db.messageNodeDao().insert(message)

            val counts = coroutineScope {
                (1..21).map { async(Dispatchers.Default) { dao.togglePinStatus(initial.id) } }.awaitAll()
            }

            assertEquals(List(21) { 1 }, counts)
            assertEquals(false, dao.getPinStatus(initial.id))
            assertEquals(initial.copy(isPinned = false), dao.getConversationById(initial.id))
            assertEquals(listOf(message), db.messageNodeDao().getNodesOfConversation(initial.id))
            assertEquals(1, dao.togglePinStatus(initial.id))
            assertEquals(initial, dao.getConversationById(initial.id))
        }
    }

    @Test
    fun pinToggleCannotRecreateMissingConversation() = runBlocking {
        withDatabase { db ->
            assertEquals(0, db.conversationDao().togglePinStatus("missing"))
            assertNull(db.conversationDao().getPinStatus("missing"))
            assertEquals(0, db.conversationDao().countAll())
        }
    }

    private fun conversation() = ConversationEntity(
        id = "conversation", assistantId = "assistant", title = "original", nodes = "legacy nodes",
        createAt = 10, updateAt = 20, chatSuggestions = "[]", isPinned = true,
        customSystemPrompt = "custom", folderId = "folder", compressionSummaries = "summaries",
        compressedNodeIds = "compressed ids", compressionRevision = 5, sendCompressedSummary = false,
        legacyCompressedSummary = "legacy summary", compressionHistory = "history",
    )

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
