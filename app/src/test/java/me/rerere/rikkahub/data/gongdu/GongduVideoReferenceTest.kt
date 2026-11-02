package me.rerere.rikkahub.data.gongdu

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.ai.transformers.historyMediaTransformers
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class GongduVideoReferenceTest {
    private val server = Uuid.random()
    private val assistant = Assistant(mcpServers = setOf(server))
    private val settings = Settings.dummy().copy(gongdu = GongduSetting(serverId = server))
    private fun context(allowed: Boolean = true) = TransformerContext(RuntimeEnvironment.getApplication(), Model(),
        if (allowed) assistant else assistant.copy(mcpServers = emptySet()), settings)
    private fun saved() = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(
        "视频概览", metadata = gongduVideoReference(GongduFeed("same-note", "视频", "note-token")),
    )))

    @Test fun uiVideoReferenceSurvivesStorageAndReachesModelThroughSharedRequestPipeline() = runBlocking {
        val saved = Json.decodeFromString<UIMessage>(Json.encodeToString(saved()))
        val result = historyMediaTransformers.fold(listOf(saved)) { messages, transformer -> transformer.transform(context(), messages) }
        val text = (result.single().parts.single() as UIMessagePart.Text).text
        assertTrue(text.contains("gongdu_read_video"))
        assertTrue(text.contains("\"feed_id\":\"same-note\""))
        assertTrue(text.contains("\"xsec_token\":\"note-token\""))
        assertEquals("视频概览", (saved.parts.single() as UIMessagePart.Text).text)
    }
    @Test fun disabledAssistantReceivesNoExtraToolArguments() = runBlocking {
        val original = listOf(saved())
        assertEquals(original, GongduVideoReferenceTransformer.transform(context(false), original))
    }
    @Test fun nestedToolReferenceWorksAndRequestCopyDoesNotAppendItTwice() = runBlocking {
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("read", "gongdu_read_video", "{}", saved().parts)))
        val first = GongduVideoReferenceTransformer.transform(context(), listOf(tool))
        val text = ((first.single().parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("same-note"))
        assertEquals(first, GongduVideoReferenceTransformer.transform(context(), first))
    }
    @Test fun incompleteReferenceIsNotAdvertisedAsUsable() = runBlocking {
        val broken = saved().copy(parts = listOf(UIMessagePart.Text("视频", metadata = gongduVideoReference(GongduFeed("id", "视频", "")))))
        assertEquals(listOf(broken), GongduVideoReferenceTransformer.transform(context(), listOf(broken)))
    }
}
