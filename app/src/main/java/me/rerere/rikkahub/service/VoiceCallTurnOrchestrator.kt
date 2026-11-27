/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.video.TransientAudioClip
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCallSetting
import me.rerere.rikkahub.data.video.VideoFrameRequestScope
import me.rerere.rikkahub.data.video.VideoFrameStore
import me.rerere.rikkahub.data.video.VideoGiftProtocol
import me.rerere.rikkahub.data.video.VideoVoiceAvailability
import me.rerere.rikkahub.data.video.VoiceCallTurnGate
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import me.rerere.tts.model.SpeechMarkup
import kotlin.uuid.Uuid

private const val TURN_TAG = "VoiceCallService"

internal interface VoiceCallTurnHost {
    val applicationContext: Context
    val serviceScope: CoroutineScope
    val uiState: MutableStateFlow<VoiceCallUiState>
    val settingsStore: SettingsStore
    fun conversationIdOrNull(): Uuid?
    fun conversation(): StateFlow<Conversation>
    val videoMode: Boolean
    val callEnded: Boolean
    val videoCoordinator: VideoCallCoordinator?
    val hasTts: Boolean
    val currentTts: CustomTtsState?
    val hasMicrophone: Boolean
    val currentMicrophone: VoiceCallMicrophone?
    val callTranscript: VoiceCallTranscript
    fun shouldListenToCall(): Boolean
    fun syncMicrophone()
    fun startListening(recoveryMessage: String? = null)
    fun interruptSpeaking()
    fun postNotice(text: String?)
    fun notifyVideoVoice(text: String)
    fun videoVoiceAvailability(): VideoVoiceAvailability
    fun refreshVideoVoiceInput()
    fun resumeVideoHandsFree()
    fun recordVideoCallMessage(id: Uuid)
    fun beginVideoTurnRequest(token: String): Boolean
    fun finishVideoTurnRequest(token: String, cancelled: Boolean)
    fun submitCallMessage(
        parts: List<UIMessagePart>,
        transientSystemPrompt: String?,
        transientImageUrls: List<String>,
        ownedImageUrls: List<String>,
        transientSystemPromptWithoutImages: String?,
        requestToken: String?,
        transientAudio: TransientAudioClip?,
        imageScope: VideoFrameRequestScope?,
    ): QueuedMessage?

    fun cancelCallRequest(id: Uuid)
}

/**
 * Coordinates per-turn submission, streaming TTS synthesis, barge-in detection,
 * and request authorization token isolation for [VoiceCallService].
 */
internal class VoiceCallTurnOrchestrator(
    private val host: VoiceCallTurnHost,
) {
    /** 每轮对话的令牌门禁：打断/挂断先失效，旧任务在检查点就会自己退出。 */
    private val turnGate = VoiceCallTurnGate()
    private var turnJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var interruptDetectJob: Job? = null

    private var activeVoiceMessage: QueuedMessage? = null
    private val voiceRequestIds = mutableSetOf<Uuid>()

    // 跟踪 AI 消息的增量, 用于流式 TTS
    private var lastAssistantText: String = ""
    var hasSentCurrentMessage: Boolean = false
        private set

    // 流式 TTS: 记录已发送给 TTS 的文本长度
    private var ttsSentLength: Int = 0

    /** 当前这一轮的请求 token（视频通话授权凭证）；随队列项不可变地传给生成管线。 */
    private var activeRequestToken: String? = null

    fun resetForNewSession() {
        turnGate.invalidate()
        endActiveRequestToken(cancelled = true)
    }

    fun resetForListening() {
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = false
        interruptDetectJob?.cancel()
    }

    fun resetTurnTextForVoiceInterrupt() {
        activeVoiceMessage = null
        lastAssistantText = ""
        ttsSentLength = 0
    }

    fun cancelInterruptDetection() {
        interruptDetectJob?.cancel()
        interruptDetectJob = null
    }

    fun endCallCleanup(withdrawRequests: Boolean) {
        // 先让这一轮彻底失效（等待抓帧/等待提交的旧任务会自己退出），再取消。
        turnGate.invalidate()
        turnJob?.cancel()
        turnJob = null
        // 挂断：这一轮的 token 与它发出的留影许可全部作废（下面 exitVideoCall 还会整体清一遍）。
        endActiveRequestToken(cancelled = true)
        activeVoiceMessage = null
        speakingMonitorJob?.cancel()
        conversationMonitorJob?.cancel()
        interruptDetectJob?.cancel()
        if (withdrawRequests) {
            voiceRequestIds.toList().forEach { host.cancelCallRequest(it) }
        }
        voiceRequestIds.clear()
    }

    /**
     * 让"当前这一轮"失效并撤回它的请求：打断/挂断/开始新一轮都走这里。
     *
     * 先失效令牌（旧协程在下一个检查点会自己退出），再取消旧任务，最后撤回队列里的请求。
     */
    fun invalidateTurnAndWithdraw() {
        turnGate.invalidate()
        turnJob?.cancel()
        turnJob = null
        speakingMonitorJob?.cancel()
        speakingMonitorJob = null
        interruptDetectJob?.cancel()
        val entry = activeVoiceMessage
        activeVoiceMessage = null
        // 授权先关掉：被打断的这一轮即使还有旧回调跑完，也不可能再送礼/留影。
        // 取消（而不是完成）还要撤销它已经发出的留影许可。
        endActiveRequestToken(cancelled = true)
        if (entry != null) {
            voiceRequestIds -= entry.id
            host.cancelCallRequest(entry.id)
        }
        host.currentTts?.stop()
    }

    /** 生成一轮视频请求的授权 token；非视频通话返回 null（也就没有任何送礼/留影权限）。 */
    internal fun beginTurnRequestIfVideoMode(): String? {
        if (!host.videoMode) return null
        val candidate = "vc-${Uuid.random()}"
        if (!host.beginVideoTurnRequest(candidate)) return null
        activeRequestToken = candidate
        return candidate
    }

    internal fun endActiveRequestToken(cancelled: Boolean = false) {
        val token = activeRequestToken ?: return
        activeRequestToken = null
        closeRequestToken(token, cancelled)
    }

    /**
     * 关闭**指定的**那个 token，并且只在它仍是"当前 token"时才清全局变量。
     *
     * 旧实现只有一个全局 [activeRequestToken]：上一轮抓帧的协程在新一轮 `begin` 之后才跑到
     * finally，它一把 `activeRequestToken` 置空/关闭，就会把**新一轮**的授权关掉。
     * token 必须是局部的，全局变量只是"当前这一轮"的便捷指针。
     */
    internal fun closeRequestToken(token: String, cancelled: Boolean) {
        if (activeRequestToken == token) activeRequestToken = null
        host.finishVideoTurnRequest(token, cancelled)
    }

    /**
     * 开一轮通话对话。
     *
     * @param text 真正发给模型的文字（视频直传路线是占位文本，ASR 路线是转写，手打就是原文）。
     * @param bubbleText 面板上显示的那一行（直传路线不带"仅本轮提供"这种说明，免得气泡太长）。
     * @param transientAudio 这一轮仅本次请求可用的录音（音频直传路线）；所有权随请求移交。
     */
    fun beginTurn(
        text: String,
        fromVoice: Boolean,
        bubbleText: String = text,
        transientAudio: TransientAudioClip? = null,
    ): Boolean {
        val convId = host.conversationIdOrNull()
        if (host.callEnded || convId == null) {
            transientAudio?.release(fromRequest = false)
            return false
        }
        // 新一轮开始前先让上一轮彻底失效（挂断/打断/旧请求回调都不许再提交）
        turnGate.invalidate()
        turnJob?.cancel()
        endActiveRequestToken(cancelled = true)
        val token = turnGate.begin()
        host.uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                userTranscript = if (fromVoice) it.userTranscript else text.take(MAX_TYPED_TRANSCRIPT),
                assistantText = "",
                errorMessage = null,
            )
        }
        host.syncMicrophone()
        host.currentTts?.stop()
        ttsSentLength = 0
        lastAssistantText = ""
        hasSentCurrentMessage = true
        host.videoCoordinator?.onUserTurn(bubbleText)
        turnJob = host.serviceScope.launch {
            try {
                dispatchTurn(convId, text, token, transientAudio)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TURN_TAG, "发送通话消息失败, conversationId=$convId", error)
                if (turnGate.isCurrent(token)) recoverAfterReplyFailure("发送失败: ${error.message}")
            }
        }
        return true
    }

    private suspend fun dispatchTurn(
        conversationId: Uuid,
        text: String,
        token: Long,
        transientAudio: TransientAudioClip? = null,
    ) {
        if (!turnGate.isCurrent(token) || host.callEnded) {
            transientAudio?.release(fromRequest = false)
            return
        }
        val appContext = host.applicationContext
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text(text))
        // 仅本次请求有效的临时图片（自动抓帧），请求结束后由 ChatService 删除。
        val transientImages = mutableListOf<String>()
        // 手动夹帧是永久文件：没交出去（打断/挂断/提交失败）就必须自己删，否则留下孤儿照片。
        val permanentImages = mutableListOf<String>()
        var handedOff = false
        var audioHandedOff = false
        // 视频通话的请求 token：由这里生成，随队列项不可变地传到生成管线。
        // 非视频通话没有 token，礼物/留影因此不会在任何其它生成里生效。
        val requestToken = beginTurnRequestIfVideoMode()
        val imageScope = VideoCallRuntime.frameRequestScope(conversationId.toString(), requestToken)
        try {
            // 视频模式：本轮要附带的画面。手动夹帧永久保存，自动帧只作本次请求的临时输入。
            host.videoCoordinator?.let { coordinator ->
                val attachment = coordinator.frameAttachmentForTurn()
                if (attachment != null && (imageScope == null || !VideoCallRuntime.isFrameRequestScopeCurrent(imageScope))) {
                    // Closing the camera during compression must not leak a serialized old frame.
                    if (attachment.permanent) VideoFrameStore.deleteOwned(appContext, attachment.url)
                    else VideoFrameStore.deleteTransient(appContext, listOf(attachment.url))
                } else if (attachment != null) {
                    if (attachment.permanent) {
                        parts += UIMessagePart.Image(url = attachment.url)
                        permanentImages += attachment.url
                        // Keep the camera image in history, but authorize it only for this request.
                    } else {
                        transientImages += attachment.url
                    }
                }
            }
            // 抓帧/压缩是挂起操作，期间可能已经被打断或挂断：提交前必须再校验一次。
            if (!turnGate.isCurrent(token) || host.callEnded) {
                if (transientImages.isNotEmpty()) VideoFrameStore.deleteTransient(appContext, transientImages)
                permanentImages.forEach { VideoFrameStore.deleteOwned(appContext, it) }
                transientAudio?.release(fromRequest = false)
                return
            }
            val attachedPicture = parts.any { it is UIMessagePart.Image } || transientImages.isNotEmpty()
            val permanentPicture = parts.any { it is UIMessagePart.Image }
            val audioAttached = transientAudio != null
            val prompts = callSystemPrompts(
                pictureAttached = attachedPicture,
                audioAttached = audioAttached,
                permanentPicture = permanentPicture,
                hasTransientAttachment = transientImages.isNotEmpty() || audioAttached,
            )
            val entry = requireNotNull(
                host.submitCallMessage(
                    parts.toList(),
                    prompts.actual,
                    transientImages.toList(),
                    // 本次新建、还没进历史的永久帧：只有"没写入历史"时才允许删（ChatService 负责）。
                    permanentImages.toList(),
                    // 队列暂停会摘掉"本轮附图/本轮录音"，届时改用无附件版本的视频上下文。
                    prompts.withoutTransientAttachments,
                    // 授权随队列项不可变传入；入队与授权不存在"先失败一半"的中间态。
                    requestToken,
                    // 录音只给这一条请求；所有权在 ChatService 的请求 finally 里释放并删除。
                    transientAudio,
                    imageScope?.copy(systemPromptWithoutCamera = prompts.withoutCamera),
                ),
            ) { "语音内容为空或会话已删除" }
            handedOff = true
            audioHandedOff = true
            if (!turnGate.isCurrent(token)) {
                // 提交过程中被打断：立刻撤回这条请求，不留在队列里。
                voiceRequestIds -= entry.id
                host.cancelCallRequest(entry.id)
                return
            }
            activeVoiceMessage = entry
            host.callTranscript.record(
                "user:" + entry.id,
                "user",
                host.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "我" },
                text,
            )
            // 视频通话：把这条用户消息记进"这通电话覆盖了哪些消息"（见 endCall 写入通话卡）。
            if (host.videoMode) host.recordVideoCallMessage(entry.id)
            voiceRequestIds += entry.id
            speakingMonitorJob?.cancel()
            speakingMonitorJob = host.serviceScope.launch {
                try {
                    val finalText = entry.reply.await()
                    voiceRequestIds -= entry.id
                    if (activeVoiceMessage !== entry) return@launch
                    if (finalText == null) {
                        failVoiceReply(entry, "本次语音消息已撤回或中断")
                    } else if (finalText.isBlank()) {
                        failVoiceReply(entry, "本次未返回可朗读的回复，请到聊天页查看")
                    } else {
                        publishVoiceReply(
                            entry,
                            queuedSpeechReplyText(host.conversation().value.currentMessages, entry.id)
                                .ifBlank { finalText },
                        )
                        onGenerationDone(entry)
                    }
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    voiceRequestIds -= entry.id
                    failVoiceReply(entry, "本次回复已中断，请重新发送")
                } catch (_: MessageQueuePausedException) {
                    // A paused entry remains queued; hang-up can still withdraw this utterance by ID.
                    failVoiceReply(entry, "发送队列已暂停，请先在聊天页继续或撤回")
                } catch (_: Exception) {
                    voiceRequestIds -= entry.id
                    failVoiceReply(entry, "本次回复失败，请到聊天页查看错误")
                }
            }
        } finally {
            // 没交出去的临时帧必须自己清掉；交出去的那份由 ChatService 在请求结束时删除。
            if (!handedOff) {
                if (transientImages.isNotEmpty()) {
                    VideoFrameStore.deleteTransient(appContext, transientImages)
                }
                // 手动夹帧同样是"没交出去就删"：不能留下没人引用的永久照片。
                permanentImages.forEach { VideoFrameStore.deleteOwned(appContext, it) }
                // 这一轮根本没进队列：**这个** token 的生命周期到此结束。
                // 交出去之后 token 的所有权在请求本身（ChatService job 的 finally 决定
                // 是 complete 还是 cancel），这里绝不能提前关掉它 —— 那会让本轮礼物/留影全部失效。
                // 关键：只关自己的 token，旧协程晚到的 finally 不许把新一轮的授权关掉。
                requestToken?.let { closeRequestToken(it, cancelled = true) }
            }
            // 录音：没交出去（提交失败/被打断）就必须自己释放并删文件；
            // 交出去之后所有权在请求本身（job 的 finally），这里绝不能提前删。
            if (!audioHandedOff) transientAudio?.release(fromRequest = false)
        }
    }

    private fun callSystemPrompts(
        pictureAttached: Boolean,
        audioAttached: Boolean,
        permanentPicture: Boolean = false,
        hasTransientAttachment: Boolean = false,
    ): TurnSystemPrompts = buildCallSystemPrompts(
        ttsProvider = host.settingsStore.settingsFlow.value.getSelectedTTSProvider(),
        coordinator = host.videoCoordinator,
        pictureAttached = pictureAttached,
        audioAttached = audioAttached,
        permanentPicture = permanentPicture,
        hasTransientAttachment = hasTransientAttachment,
    )

    private data class VoiceCallRouteKey(
        val providers: List<ProviderSetting>,
        val assistants: List<Assistant>,
        val asrProviders: List<ASRProviderSetting>,
        val selectedASRProviderId: Uuid?,
        val chatModelId: Uuid,
        val videoCallSetting: VideoCallSetting,
    )

    /**
     * 监听对话流变化, 实现:
     * 1. 流式 TTS (检测到新句子即朗读)
     * 2. AI 开始输出时立即进入 Speaking 状态, 让用户可以打断
     * 3. AI 回复完成后回到 Listening
     */
    fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = host.serviceScope.launch {
            // 只在"语音输入可用性真的变了"（模型/服务商/权限/ASR 配置）时才刷新。
            // 无条件刷新会把一次性的提示（例如"没有识别到内容，请重新录一段"）在每次
            // settings 发射时清回 Idle，用户就再也看不到失败原因了。
            var lastAvailability: VideoVoiceAvailability? = null
            combine(
                host.conversation(),
                host.settingsStore.settingsFlow.map {
                    VoiceCallRouteKey(
                        providers = it.providers,
                        assistants = it.assistants,
                        asrProviders = it.asrProviders,
                        selectedASRProviderId = it.selectedASRProviderId,
                        chatModelId = it.chatModelId,
                        videoCallSetting = it.videoCallSetting,
                    )
                }.distinctUntilChanged(),
            ) { conv, _ -> conv }.collect { conv ->
                // Route follows this conversation's model/provider and current settings while idle.
                // Active recordings retain their frozen route until they finish or are cancelled.
                val availability = runCatching { host.videoVoiceAvailability() }.getOrNull()
                if (availability != null) {
                    // 第一次发射只记录基线：进入通话时已经由 startVideoCall 刷新过一次，
                    // 这里再"刷新"会把期间刚设置的一次性提示（例如"没有识别到内容"）清回 Idle。
                    if (lastAvailability == null) {
                        lastAvailability = availability
                    } else if (availability != lastAvailability) {
                        lastAvailability = availability
                        host.refreshVideoVoiceInput()
                    }
                }
                if (host.uiState.value.status != VoiceCallStatus.Processing &&
                    host.uiState.value.status != VoiceCallStatus.Speaking
                ) return@collect

                val entry = activeVoiceMessage ?: return@collect
                val currentText = queuedSpeechReplyText(conv.currentMessages, entry.id)
                if (currentText.isNotBlank()) publishVoiceReply(entry, currentText)
            }
        }
    }

    private fun publishVoiceReply(entry: QueuedMessage, currentText: String) {
        if (activeVoiceMessage !== entry || host.uiState.value.status !in
            setOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)
        ) return
        // 防御：任何协议标签都不允许进入气泡/字幕/TTS。
        val text = VideoGiftProtocol.strip(currentText)
        host.uiState.update { it.copy(assistantText = SpeechMarkup.transcript(text)) }
        host.callTranscript.record(
            "assistant:" + entry.id,
            "assistant",
            host.settingsStore.settingsFlow.value.assistants.firstOrNull {
                it.id == host.conversation().value.assistantId
            }?.name?.ifBlank { "对方" } ?: "对方",
            text,
        )
        host.videoCoordinator?.onAssistantText(text)
        host.currentTts?.let { tts ->
            if (text.length > ttsSentLength) {
                val newText = text.substring(ttsSentLength)
                // 流式朗读：整句先到先读；等不到整句时按够长的分句先出声，首字更快。
                val ready = SpeechMarkup.streamPrefixLength(newText)
                if (ready > 0) tts.enqueueText(newText.substring(0, ready))
                ttsSentLength += ready
            }
        }
        if (host.uiState.value.status == VoiceCallStatus.Processing && text.isNotBlank()) {
            host.uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
            if (host.hasMicrophone) startInterruptDetection()
        }
        lastAssistantText = text
    }

    private fun failVoiceReply(entry: QueuedMessage, message: String) {
        if (activeVoiceMessage !== entry) return
        recoverAfterReplyFailure(message)
    }

    private fun recoverAfterReplyFailure(message: String) {
        if (host.callEnded) return
        activeVoiceMessage = null
        // 这一轮失败了：授权随之关闭，并且撤销它已经发出的留影许可 ——
        // 一次失败的回复不该还能把画面写进聊天。
        endActiveRequestToken(cancelled = true)
        interruptDetectJob?.cancel()
        // Invalidate partial ASR text from the failed turn, even with reply-listening enabled.
        host.currentMicrophone?.update(VoiceCallStatus.Idle, muted = true, keepListeningDuringReply = false)
        host.startListening(message + "；已恢复聆听，请重新说。")
        host.resumeVideoHandsFree()
    }

    private suspend fun onGenerationDone(entry: QueuedMessage) {
        if (activeVoiceMessage !== entry || host.uiState.value.status !in
            setOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)
        ) return
        // 朗读最后剩余的文本
        val finalText = lastAssistantText
        host.currentTts?.let { tts ->
            if (finalText.length > ttsSentLength) {
                val remaining = finalText.substring(ttsSentLength)
                if (remaining.isNotBlank()) {
                    tts.enqueueText(remaining)
                    ttsSentLength = finalText.length
                }
            }
        }
        // 收尾：把最后半句也补成一条气泡
        host.videoCoordinator?.onAssistantTurnFinished(finalText)
        // 这一轮**正常结束**：关闭授权 token，但保留它已经发出的留影许可
        // （合法的异步留影本来就落在请求结束之后）。
        endActiveRequestToken(cancelled = false)

        host.uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
        if (host.hasMicrophone) startInterruptDetection()
        host.currentTts?.let { tts -> waitForTtsToFinish(tts) }

        // 回到监听
        if (activeVoiceMessage === entry && host.uiState.value.status == VoiceCallStatus.Speaking) {
            activeVoiceMessage = null
            host.startListening()
            // 免提：答完了就接着听下一段，跟语音通话一样不用动手。
            host.resumeVideoHandsFree()
        }
    }

    private suspend fun waitForTtsToFinish(tts: CustomTtsState) = coroutineScope {
        // 等待整条朗读队列完成，而不是重新等待已经结束的播放开始。
        val completion = async(start = CoroutineStart.UNDISPATCHED) { tts.awaitCompletion() }
        var previous = tts.playbackState.value
        var previousChunk = tts.currentChunk.value
        var stalledMs = 0L
        try {
            // 等待 TTS 播放完成。
            // 不能只靠 isSpeaking：它在 worker 的 finally 里才会变 false。
            // worker 挂在合成或播放时，持续观察真实播放进度，不能把恒为 true 当作进展。
            // 有进度就继续等；没有进度才累计超时。
            // 正常播完、无 TTS 或合成失败的队列可以立即退出，不再固定等待。
            // 超时前先停止旧队列，避免恢复采音后才冒出迟到的朗读。
            while (!completion.isCompleted) {
                delay(100)
                val playback = tts.playbackState.value
                val chunk = tts.currentChunk.value
                stalledMs = if (playback.positionMs != previous.positionMs ||
                    playback.currentChunkIndex != previous.currentChunkIndex || chunk != previousChunk
                ) {
                    0L
                } else {
                    stalledMs + 100L
                }
                previous = playback
                previousChunk = chunk
                // 连续 30 秒无播放进展才停止，不截断仍在正常播放的长回复。
                if (stalledMs >= 30_000L) {
                    Log.w(TURN_TAG, "TTS 连续 30 秒无播放进展，停止朗读并恢复通话")
                    tts.stop()
                    val notice = "朗读暂时没有响应，已停止朗读"
                    if (host.videoMode) host.notifyVideoVoice(notice) else host.postNotice(notice)
                    break
                }
            }
            // 传播队列完成时的异常，交给本轮的恢复路径处理。
            if (completion.isCompleted) completion.await()
        } finally {
            // 等待被打断时也不遗留子任务。
            completion.cancel()
        }
    }

    /**
     * Speaking 状态下的打断检测.
     *
     * 与 startVadDetection (判断"该发送了") 职责不同:
     * 这里只关心"用户是否开始说话了", 一旦检测到就立即打断, 不等静音判断.
     *
     * 用比 Listening 状态更高的音量阈值 (0.15f vs 0.05f), 降低被 AI 自己声音误触发的概率.
     * 残留风险: AEC 不是 100% 完美, 外放音量很大或低端机型硬件 AEC 差时仍可能误触发,
     * 后续可加音量差阈值调优, 但不阻塞现在的实现.
     */
    private fun startInterruptDetection() {
        // 视频通话手动模式不监听麦克风，因此没有"听着听着被打断"这条路径。
        if (host.videoMode) return
        interruptDetectJob?.cancel()
        interruptDetectJob = host.serviceScope.launch {
            val baselineTranscript = host.uiState.value.userTranscript
            while (true) {
                delay(150)
                if (host.uiState.value.status != VoiceCallStatus.Speaking) break
                val mic = host.currentMicrophone
                if (!host.shouldListenToCall() || mic == null || !mic.isListening) continue

                val currentTranscript = host.uiState.value.userTranscript
                val amplitudes = host.uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                // 转写文本相较于进入 Speaking 时有新增内容, 或者音量突然超过阈值,
                // 都视为"用户开始说话了"
                val hasNewTranscript = currentTranscript.length > baselineTranscript.length + 1
                val hasLoudVoice = recentAmplitude > 0.15f

                if (hasNewTranscript || hasLoudVoice) {
                    Log.d(TURN_TAG, "检测到用户打断, amplitude=$recentAmplitude")
                    host.interruptSpeaking()
                    break
                }
            }
        }
    }
}
