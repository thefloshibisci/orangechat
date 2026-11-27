/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.service.ProactiveMessageService
import me.rerere.rikkahub.data.service.ProactiveWakeupStore
import me.rerere.rikkahub.data.video.FrameFileOwnership
import me.rerere.rikkahub.data.video.TransientAudioClip
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoFrameRequestScope
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.diagnostics.LoadingDiagnostics
import me.rerere.rikkahub.diagnostics.TaskWatchdog
import me.rerere.rikkahub.plugin.loader.PluginLoader
import kotlin.uuid.Uuid

private const val QUEUED_RUNNER_TAG = "ChatService"

/**
 * Builds the lazy queued-send coroutine job managing proactive slot reset, user message
 * persistence with attachment ownership, completion execution, and cancellation cleanup.
 */
internal class ChatQueuedGenerationRunner(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val pluginLoader: PluginLoader,
    private val initializeConversation: suspend (Uuid) -> Unit,
    private val preprocessUserInputParts: (List<UIMessagePart>, Assistant) -> List<UIMessagePart>,
    private val saveConversationWithOwnership: suspend (
        session: ConversationSession,
        conversationId: Uuid,
        conversation: Conversation,
        ownership: FrameFileOwnership?,
        publishedMessageId: Uuid,
    ) -> Unit,
    private val handleMessageComplete: suspend (
        conversationId: Uuid,
        transientSystemPrompt: String?,
        enableTools: Boolean,
        diagnosticTrace: TaskWatchdog.Trace?,
        transientImageUrls: List<String>,
        transientImageScope: VideoFrameRequestScope?,
        transientImageAnchorMessageId: String?,
        requestToken: String?,
        videoCallUserMessageId: Uuid?,
        transientAudioUrl: String?,
        transientAudioAnchorMessageId: String?,
    ) -> Boolean,
    private val clearQueueOwner: (Uuid, Uuid) -> Unit,
    private val emitGenerationDone: suspend (Uuid) -> Unit,
    private val addError: (Throwable, Uuid, String?) -> Unit,
) {
    fun createJob(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val transientSystemPrompt = queued.transientSystemPrompt
        val enableTools = queued.enableTools
        val interaction = queued.interaction
        // 本次请求专用的临时图片（视频通话自动抓帧）：请求结束（含失败/取消）后删除。
        val transientImageUrls = queued.transientImageUrls
        val transientImageScope = queued.transientImageScope
        // 本次新建、还没被历史引用的永久帧（手动夹帧）：**只有没落库**才允许删。
        val ownedImageUrls = queued.ownedImageUrls
        // 临时图的锚点：就是这条用户消息自己的 ID；工具多步插进来的消息不会把它顶掉。
        val transientImageAnchorMessageId = (queued.existingMessageId ?: queued.id).toString()
        // 这一轮的授权 token（视频通话）；非通话请求为 null。
        val requestToken = queued.requestToken
        // 这一轮仅本次请求可用的录音（视频通话手动录音，直传路线）。
        val transientAudio = queued.transientAudio
        val audioOwnership = transientAudio?.let { TransientAudioClip.RequestOwnership(it) }

        val trace = LoadingDiagnostics.begin(context, "chat.send", timeoutMillis = 0)
        // 本次新建的永久帧（手动夹帧/留影）的归属状态。
        // 必须在协程外面创建：`invokeOnCompletion` 的兜底和协程体的 finally 必须看**同一份**状态
        // （旧实现把 Boolean 声明在 launch 里，兜底根本引用不到，编译就过不去；即使能引用，
        // 一个布尔也区分不了"取消时事务可能已提交"这种真正危险的中间态）。
        // 礼物/转账这种已有持久消息的请求从一开始就是 PUBLISHED：文件归历史所有，永不删除。
        val frameOwnership = FrameFileOwnership(alreadyDurable = queued.existingMessageId != null)
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                // 用户一开口就立即撤销旧主动闹钟。新的休眠计时必须等本轮正常回复结束后再开始，
                // 避免 AI 正在聊天时主动器到点，也避免长回复吃掉休眠间隔。
                val currentProactiveSetting = settingsStore.settingsFlow.value.proactiveMessageSetting
                if (currentProactiveSetting.enabled) {
                    ProactiveMessageService.resetConversationSlot(context, conversationId.toString())
                    if (currentProactiveSetting.assistantConfigs.orEmpty().any {
                            it.enabled && it.conversationId != conversationId.toString()
                        } ||
                        (currentProactiveSetting.allowAiScheduledWakeup &&
                            ProactiveWakeupStore(context).all().isNotEmpty())
                    ) {
                        // Other conversations keep their appointments while this one is chatting.
                        // A busy target retains its appointment and retries after ownership is released.
                        ProactiveMessageService.scheduleNext(context, currentProactiveSetting)
                    } else {
                        ProactiveMessageService.cancel(context)
                    }
                }
                // 录音的所有权先交给请求：在构造 provider payload 之前认领，
                // 这样"重新派发队列/暂停/撤回"都不可能把正在序列化的文件删掉。
                check(audioOwnership == null || audioOwnership.beginRequest()) {
                    "本轮录音已释放或正被其他请求使用，已停止发送"
                }
                trace.stage("initializing conversation")
                initializeConversation(conversationId)
                val settings = settingsStore.settingsFlow.first()

                // A gift is already a durable user message. Reply through the normal queue
                // without appending a fabricated sentence or duplicating user event hooks.
                if (queued.existingMessageId == null) {
                    // 读取最新状态 -> 追加用户消息 -> 落库，整体加锁。
                    // 防止跟同一时刻可能在跑的标题生成/建议生成/语音通话挂断反馈互相覆盖对方刚写入的消息。
                    trace.stage("waiting for conversation state lock")
                    val (assistant, processedContent) = session.withStateLock {
                        val latestConversation = session.state.value
                        val assistant = settings.getAssistantOf(latestConversation)
                        val processedContent = if (interaction == null) {
                            preprocessUserInputParts(content, assistant)
                        } else {
                            content
                        }

                        val newConversation = latestConversation.copy(
                            messageNodes = latestConversation.messageNodes + UIMessage(
                                id = queued.id,
                                role = MessageRole.USER,
                                parts = processedContent,
                                annotations = listOfNotNull(interaction),
                            ).toMessageNode(),
                        )
                        trace.stage("saving user message")
                        // 归属交接就在这一句里：发布之前先标记"可能已发布"，成功之后才确认 PUBLISHED。
                        // 取消落在 Room 的挂起提交中间时状态停在 PUBLISHING —— 两条兜底路径都不会删文件。
                        saveConversationWithOwnership(
                            session,
                            conversationId,
                            newConversation,
                            frameOwnership,
                            queued.id,
                        )
                        assistant to processedContent
                    }

                    // 触发 message_sent 事件钩子
                    // 关键: 这里用 appScope.launch 提交独立协程, 而不是直接 await callEvent。
                    // 原因: callEvent 内部对订阅插件的 handler 在单线程 pluginDispatcher 上串行执行,
                    // supabase_memory 等插件会同步 fetch 网络请求 (最长 15s 超时)。若直接 await,
                    // 用户点发送后会卡在这里直到所有插件 handler 跑完才继续走 sendMessage 后续逻辑。
                    // 改为 fire-and-forget 提交到 AppScope (SupervisorJob) 上, 不挂在当前 sendMessage
                    // 的 job 下 —— 这样用户连续发消息触发 session.getJob()?.cancel() 取消上一条消息 job 时,
                    // 不会把这次插件同步也连累取消掉 (Supabase 记录保持完整)。
                    runCatching {
                        val eventData = JsonObject(
                            mapOf(
                                "assistant_id" to JsonPrimitive(assistant.id.toString()),
                                "conversation_id" to JsonPrimitive(conversationId.toString()),
                                "message" to JsonPrimitive(
                                    processedContent.mapNotNull { part ->
                                        if (part is UIMessagePart.Text) part.text else null
                                    }.joinToString("\n"),
                                ),
                                "role" to JsonPrimitive("user"),
                                "timestamp" to JsonPrimitive(System.currentTimeMillis()),
                            ),
                        )
                        appScope.launch {
                            try {
                                pluginLoader.callEvent("message_sent", eventData)
                            } catch (e: Exception) {
                                Log.w(QUEUED_RUNNER_TAG, "Failed to trigger message_sent event", e)
                            }
                        }
                    }.onFailure { e ->
                        Log.w(QUEUED_RUNNER_TAG, "Failed to trigger message_sent event", e)
                    }
                } else {
                    session.withStateLock {
                        check(
                            session.state.value.currentMessages.any {
                                it.id == queued.existingMessageId && it.role == MessageRole.USER
                            },
                        ) {
                            "红包或转账已不在当前分支，未继续生成"
                        }
                    }
                }

                // 开始补全
                if (answer) {
                    val completed = handleMessageComplete(
                        conversationId,
                        transientSystemPrompt,
                        enableTools,
                        trace,
                        transientImageUrls,
                        transientImageScope,
                        transientImageAnchorMessageId,
                        requestToken,
                        queued.id.takeIf { requestToken != null },
                        // 音频锚点与图片锚点是同一条用户消息（队列项 ID）；工具多步不会把它顶掉。
                        transientAudio?.fileUrl,
                        transientImageAnchorMessageId,
                    )
                    if (!completed) {
                        failure = IllegalStateException("生成失败，请检查会话中的错误信息")
                        session.messageQueue.pause()
                    }
                }
                if (session.state.value.currentMessages.lastOrNull()?.getTools().orEmpty()
                        .any { it.isPending || it.canResumeExecution }
                ) {
                    queued.reply.completeExceptionally(IllegalStateException("请先处理工具审批"))
                }

                emitGenerationDone(conversationId)
            } catch (e: CancellationException) {
                failure = e
                throw e
            } catch (e: Exception) {
                // A cancelled provider may throw a transport error during cleanup. It cannot pause a newer queue.
                if (!coroutineContext.isActive) {
                    failure = CancellationException("Generation cancelled", e)
                    throw CancellationException("Generation cancelled", e)
                }
                failure = e
                session.messageQueue.pause()
                Log.e(QUEUED_RUNNER_TAG, "sendMessage failed, conversationId=$conversationId", e)
                addError(e, conversationId, context.getString(R.string.error_title_send_message))
            } finally {
                session.messageQueue.complete(
                    queued.id,
                    result = queuedReplyText(
                        session.state.value.currentMessages,
                        queued.existingMessageId ?: queued.id,
                    ),
                    error = failure,
                )
                clearQueueOwner(conversationId, queued.id)
                // 请求作用域结束：删除本次专用的临时帧（自动抓帧），聊天历史里只有文字。
                // 临时帧的所有权始终在本请求，任何队列清理都不会提前删掉它们。
                VideoFrameStore.deleteTransient(context, transientImageUrls)
                // 这一轮的录音到此为止：请求结束（完成/失败/取消）后立刻删文件，
                // 不留任何"受管音频"在磁盘上；历史里本来就只有占位文字。
                audioOwnership?.release()
                // 本次**新建**的永久帧（手动夹帧）：只有"从未被历史引用"才删。
                // 落库了它就是历史的一部分（旧实现按目录删 parts 里的 Image，会把历史照片删掉）。
                // claimDeletion 是幂等的 CAS：这个 finally 和下面的 invokeOnCompletion 兜底
                // 只有一条会真正删；状态一旦进过 PUBLISHING 就永远返回 false。
                if (frameOwnership.claimDeletion()) {
                    ownedImageUrls.forEach { url -> VideoFrameStore.deleteOwned(context, url) }
                }
                // 这一轮的通话授权（礼物/留影）：请求自己决定是"正常完成"还是"被取消"。
                // complete 保留已经发出的留影许可（合法的异步留影落在请求结束之后）；
                // cancel 会撤销这次请求的全部许可。
                requestToken?.let { token ->
                    if (failure == null) {
                        VideoCallRuntime.completeTurnRequest(token)
                    } else {
                        VideoCallRuntime.cancelTurnRequest(token)
                    }
                }
                // An API failure must not leave the proactive timer cancelled forever. Do not
                // restart it for a cancelled job that was superseded by a newer user message.
                if (coroutineContext.isActive && !session.messageQueue.hasPendingOrInFlight) {
                    val proactiveSetting = settingsStore.settingsFlow.value.proactiveMessageSetting
                    if (proactiveSetting.enabled) {
                        // Other participants kept their alarms while this request ran. Start
                        // this conversation's full idle interval only after its queue is done.
                        ProactiveMessageService.resetConversationSlot(context, conversationId.toString())
                        ProactiveMessageService.resetTimer(context, proactiveSetting)
                    }
                }
            }
        }
        job.invokeOnCompletion { cause ->
            trace.close()
            // A lazy request can be cancelled before its body (and finally block) ever starts.
            session.messageQueue.complete(
                queued.id,
                result = queuedReplyText(
                    session.state.value.currentMessages,
                    queued.existingMessageId ?: queued.id,
                ),
                error = cause,
            )
            clearQueueOwner(conversationId, queued.id)
            // 连协程体都没跑起来就被取消：临时帧和授权都必须在这里收尾。
            if (cause != null) {
                VideoFrameStore.deleteTransient(context, transientImageUrls)
                // 未启动只释放 QUEUED；已启动只凭本 job 的认领释放，不能删其他请求的录音。
                audioOwnership?.release()
                if (frameOwnership.claimDeletion()) {
                    ownedImageUrls.forEach { url -> VideoFrameStore.deleteOwned(context, url) }
                }
                requestToken?.let { VideoCallRuntime.cancelTurnRequest(it) }
            }
        }
        return job
    }
}
