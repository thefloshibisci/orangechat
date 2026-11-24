package me.rerere.rikkahub.data.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.withVideoTurnSystemContext
import me.rerere.rikkahub.data.ai.generateModelTurn
import me.rerere.rikkahub.service.MessageQueue
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VideoCallFrameRevocationTest {
    private val user = UIMessage.user("continue speaking").copy(parts = listOf(
        UIMessagePart.Text("continue speaking"), UIMessagePart.Image("file:///durable.jpg"),
    ))
    private val messages = listOf(user).withTransientRequestAudio("file:///voice.wav", user.id.toString())
    private val urls = listOf("file:///vcframe-current.jpg")
    private val cameraPrompt = "camera frame attached; audio attached; gifts available"
    private val revokedPrompt = "camera frame revoked; audio attached; gifts available"

    @After fun clean() = VideoCallRuntime.reset()

    private fun scope(): VideoFrameRequestScope {
        VideoCallRuntime.reset()
        VideoCallRuntime.enterVideoCall("call")
        assertTrue(VideoCallRuntime.beginTurnRequest("call", "turn"))
        return requireNotNull(VideoCallRuntime.frameRequestScope("call", "turn"))
            .copy(systemPromptWithoutCamera = revokedPrompt)
    }

    private fun preparedMessages() = messages.withVideoTurnSystemContext(cameraPrompt).markVideoTurnSystemContext(cameraPrompt)

    private fun request(scope: VideoFrameRequestScope?, token: String = "turn") =
        videoFrameAuthorization(urls, user.id.toString(), scope, "call", token).applyTo(preparedMessages())

    private fun images(request: List<UIMessage>) = request.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Image>().map { it.url }

    @Test fun closingWhileQueuedPreservesAudioDurablePhotoAndTurn() {
        val captured = scope()
        val queue = MessageQueue()
        queue.enqueue(user.parts, transientImageUrls = urls, transientImageScope = captured, requestToken = "turn")
        assertTrue(VideoCallRuntime.setCameraEnabled("call", false))
        val queued = requireNotNull(queue.takeNext())
        val actual = videoFrameAuthorization(queued.transientImageUrls, user.id.toString(),
            queued.transientImageScope, "call", queued.requestToken).applyTo(preparedMessages())
        assertEquals(listOf("file:///durable.jpg"), images(actual))
        assertEquals(1, actual.flatMap { it.parts }.filterIsInstance<UIMessagePart.Audio>().size)
        assertEquals(revokedPrompt, actual.first { it.role == MessageRole.SYSTEM }.toText())
        assertTrue(VideoCallRuntime.isTurnGranted("call", "turn"))
        assertEquals(2, user.parts.size)
    }

    @Test fun toolContinuationCannotReuseFrameAfterClose() {
        val captured = scope()
        assertEquals(listOf("file:///durable.jpg", urls.single()), images(request(captured)))
        VideoCallRuntime.setCameraEnabled("call", false)
        assertEquals(listOf("file:///durable.jpg"), images(request(captured)))
    }

    @Test fun reopeningNeverReauthorizesOldFrame() {
        val captured = scope()
        VideoCallRuntime.setCameraEnabled("call", false)
        VideoCallRuntime.setCameraEnabled("call", true)
        assertEquals(listOf("file:///durable.jpg"), images(request(captured)))
        assertNull(VideoCallRuntime.frameRequestScope("call", "turn"))
        assertTrue(VideoCallRuntime.beginTurnRequest("call", "fresh"))
        assertEquals(2, images(request(VideoCallRuntime.frameRequestScope("call", "fresh"), "fresh")).size)
    }

    @Test fun newCallAndCancelledTokenRejectPreviousFrame() {
        val captured = scope()
        VideoCallRuntime.exitVideoCall("call")
        VideoCallRuntime.enterVideoCall("call")
        VideoCallRuntime.beginTurnRequest("call", "turn")
        assertEquals(1, images(request(captured)).size)
        val new = requireNotNull(VideoCallRuntime.frameRequestScope("call", "turn"))
        VideoCallRuntime.cancelTurnRequest("turn")
        assertEquals(1, images(request(new)).size)
    }

    @Test fun missingOrMismatchedAuthorizationFailsClosed() {
        val captured = scope()
        assertEquals(1, images(request(null)).size)
        assertEquals(1, images(request(captured.copy(requestToken = "other"))).size)
        assertEquals(1, images(request(captured.copy(conversationId = "other"))).size)
    }

    @Test fun replacingCameraRevokesPreparedFrame() {
        val captured = scope()
        VideoCallRuntime.registerCamera("call", object : VideoCallCameraGateway {
            override val available = true
            override val unavailableReason = ""
            override suspend fun capture(): VideoFrame? = null
        })
        assertEquals(1, images(request(captured)).size)
        assertNull(VideoCallRuntime.frameRequestScope("call", "turn"))
    }

    @Test fun providerBoundaryRefreshesBothStreamingAndNonstreamingRequests() = runBlocking {
        for (stream in listOf(true, false)) {
            val captured = scope()
            val prepared = request(captured)
            assertEquals(2, images(prepared).size)
            VideoCallRuntime.setCameraEnabled("call", false)
            var calls = 0
            val provider = object : Provider<ProviderSetting> {
                fun inspect(sent: List<UIMessage>): MessageChunk {
                    calls++
                    assertEquals(listOf("file:///durable.jpg"), images(sent))
                    assertEquals(revokedPrompt, sent.first { it.role == MessageRole.SYSTEM }.toText())
                    return MessageChunk("reply", "model", listOf(UIMessageChoice(
                        index = 0, delta = null, message = UIMessage.assistant("audio continues"), finishReason = "stop",
                    )))
                }
                override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = flow { emit(inspect(messages)) }
                override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) = inspect(messages)
                override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = error("unused")
                override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): ImageGenerationResult = error("unused")
            }
            val model = Model()
            val boundary = provider.withVideoRequestAuthorization(
                videoFrameAuthorization(urls, user.id.toString(), captured, "call", "turn"),
            )
            generateModelTurn(boundary, ProviderSetting.OpenAI(), preparedMessages(), messages, TextGenerationParams(model),
                model, stream, mergeUsage = false, onUpdateMessages = {})
            assertEquals(1, calls)
        }
    }
}
