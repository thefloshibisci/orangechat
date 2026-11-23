package me.rerere.rikkahub.data.service

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.HostQuickJsLoader
import me.rerere.rikkahub.RikkaHubApp
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.gongdu.GongduDoorbell
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = RikkaHubApp::class, sdk = [28], shadows = [HostQuickJsLoader::class])
class ProactiveFullSurfaceTest {

    @Test fun fullTurnIncludesTheFinalForegroundToolsButDisabledMemoryStaysHidden() {
        val generator = GlobalContext.get().get<GenerationHandler>()
        val base = listOf(Tool("configured_mcp", "", needsApproval = true, execute = { error("must not execute") }))
        val assistant = Assistant(enableMemory = true, enableLocalSceneTools = true)
        val full = generator.assembleChatTurnTools(assistant, Uuid.random().toString(), base)
        val names = full.map { it.name }
        assertTrue(names.containsAll(listOf("memory_tool", "local_scene_search", "local_scene_read", "write_files", "configured_mcp")))
        assertEquals(names.distinct(), names); assertTrue(full.last().needsApproval)
        assertEquals(listOf("write_files", "configured_mcp"), generator.assembleChatTurnTools(Assistant(), null, base).map { it.name })
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], manifest = Config.NONE)
class ProactiveReceiptStorageTest {
    @Test fun receiptMatchesStoredMetadataInItsOwnChatNotCopiedPlainText() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = me.rerere.rikkahub.AppScope()
        try {
            val files = me.rerere.rikkahub.data.files.FilesManager(context,
                me.rerere.rikkahub.data.repository.FilesRepository(db.managedFileDao()), scope)
            val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(),
                db, files, me.rerere.rikkahub.data.db.fts.MessageFtsManager(db))
            val chat = Uuid.random(); val other = Uuid.random(); val marker = GongduDoorbell.marker("likes:123")
            val owner = Uuid.random()
            for (id in listOf(chat, other)) db.conversationDao().insert(me.rerere.rikkahub.data.db.entity.ConversationEntity(
                id.toString(), owner.toString(), "", "[]", 0, 0, "[]", false))
            assertEquals(owner, repo.doorbellChatOwner(chat))
            suspend fun store(id: Uuid, msg: UIMessage) = db.messageNodeDao().insert(MessageNodeEntity(
                Uuid.random().toString(), id.toString(), 0, JsonInstant.encodeToString(listOf(msg)), 0))
            store(other, UIMessage.user("\"gongdu_event_id\":\"$marker\""))
            assertFalse(repo.hasDoorbellReceipt(other, marker))
            store(chat, UIMessage.user("通知").copy(parts = listOf(UIMessagePart.Text("通知", metadata = buildJsonObject { put("gongdu_event_id", marker) }))))
            assertTrue(repo.hasDoorbellReceipt(chat, marker)); assertFalse(repo.hasDoorbellReceipt(other, marker))
            assertFalse(repo.hasDoorbellReceipt(chat, GongduDoorbell.marker("likes:456")))
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); db.close() }
    }
}
