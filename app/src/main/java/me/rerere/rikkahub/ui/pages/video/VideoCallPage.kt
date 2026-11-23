/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.os.IBinder
import android.util.Log
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.video.VIDEO_GIFT_EFFECT_DURATION_MS
import me.rerere.rikkahub.data.video.CameraFacing
import me.rerere.rikkahub.data.video.DanmakuLine
import me.rerere.rikkahub.data.video.PreviewCalibration
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallPolicy
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCallStartAction
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFrameScheduler
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.video.skipIfMatching
import me.rerere.rikkahub.data.video.VideoGiftEffectQueue
import me.rerere.rikkahub.data.video.VideoGiftEffectRequest
import me.rerere.rikkahub.data.video.toAndroidMatrix
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.VoiceCallService
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallEnd01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.CameraOff01
import me.rerere.hugeicons.stroke.CameraRotated01
import me.rerere.hugeicons.stroke.FlipHorizontal
import me.rerere.hugeicons.stroke.MailSend01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.RotateCrop
import me.rerere.rikkahub.data.video.VideoVoicePolicy
import me.rerere.rikkahub.data.video.VideoVoiceStatus
import me.rerere.rikkahub.data.video.VideoVoiceUiState
import me.rerere.rikkahub.ui.components.message.InteractionColors
import me.rerere.rikkahub.ui.components.message.interactionColors
import me.rerere.rikkahub.ui.components.ui.AvatarDisplay
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.PermissionStatus
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.uuid.Uuid

private const val TAG = "VideoCallPage"

// 旧的"上浮弹幕"（6 秒淡出、同屏最多 6 条）已被常驻字幕列表取代：
// 真机要求文字常驻可回看，寿命动画恰好是"看不到刚才那句"的原因。
// 保留常量只为说明历史行为，界面上已经不再引用。

/**
 * 视频通话面板。
 *
 * 视觉：与既有语音页同一套明亮玻璃主题（`interactionColors` 渐变底 + 白色玻璃卡 + 中文小字），
 * **不再是覆盖整屏的黑色遮罩**。镜头画面缩进一张圆角卡里，始终可见但不铺满。
 *
 * 文字：常驻可回看。`CallTranscriptList` 用 LazyColumn + 稳定 id 渲染服务侧的有界历史
 * （最多 400 行），不再 6 秒淡出、也不再只留最后 6 条；手动往上翻看时新消息不会把列表拽回底部。
 *
 * 控件：切前后 / 抓帧 / 麦克风（真 Mic01/MicOff01 图标 + 中文状态）/ 挂断，点身份行可打断
 * 当前生成与朗读；礼物特效仍然盖在最上层。
 *
 * 生命周期：
 * - 相机同时受 Surface / 权限 / 生命周期 STARTED / 通话进行中 四个条件约束，任一不成立就解绑；
 * - 退后台（ON_STOP）时，**绑定模式**的通话立即结束（它不占前台服务类型，不该在后台跑），
 *   正常的前台语音/视频通话继续跑；
 * - 挂断先调服务实例的 endCall，再 stopService，不依赖页面 unbind 的时机；
 * - 启动只认"用户进入面板"这一次明确意图，另一通电话结束不会让这里自动复活/自动启动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoCallPage(
    conversationId: Uuid,
    onBack: () -> Unit,
) {
    val chatService: ChatService = org.koin.compose.koinInject()
    val context = LocalContext.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val conversation by remember(conversationId) { chatService.getConversationFlow(conversationId) }
        .collectAsStateWithLifecycle()
    val assistant = settings.getAssistantById(conversation.assistantId)
    val callState by VoiceCallService.activeUiState.collectAsStateWithLifecycle()
    val startedAt by VoiceCallService.startedAtMillis.collectAsStateWithLifecycle()
    val activeConversationId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()

    val ourConversationId = conversationId.toString()
    val callActive = activeConversationId == ourConversationId
    val otherCallActive = activeConversationId != null && !callActive

    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    val audioPermission = rememberPermissionState(PermissionRecordAudio)
    val cameraGranted = cameraPermission.allRequiredPermissionsGranted
    val micGranted = audioPermission.allRequiredPermissionsGranted

    // 权限按顺序请求：先相机，等它的结果回来再问麦克风，绝不两个请求同时 launch。
    var cameraRequested by remember { mutableStateOf(false) }
    var micRequested by remember { mutableStateOf(false) }
    val cameraStatus = cameraPermission.permissionStates.values.firstOrNull() ?: PermissionStatus.NotRequested
    val micStatus = audioPermission.permissionStates.values.firstOrNull() ?: PermissionStatus.NotRequested
    LaunchedEffect(cameraStatus, micRequested) {
        if (cameraStatus == PermissionStatus.NotRequested && !cameraRequested) {
            cameraRequested = true
            cameraPermission.requestPermissions()
            return@LaunchedEffect
        }
        // 相机的请求已经落地（或本来就有权限），这时才请求麦克风。
        if (!micRequested && micStatus == PermissionStatus.NotRequested) {
            micRequested = true
            audioPermission.requestPermissions()
        }
    }

    /**
     * "用户进入视频面板"这一次明确的启动意图，只消费一次。
     *
     * 关键：另一通电话结束时**不能**让它复活——[VideoCallStartAction.BlockedByOtherCall]
     * 会把意图直接作废，之后必须由用户重新进入页面才会再启动。
     */
    var callStartIntent by remember(conversationId) { mutableStateOf(true) }
    // 两个权限请求都落地之后才决定启动形态：绝不在权限弹窗期间就用绑定模式把会话跑起来
    // （那会立刻触发 ON_STOP / 5 秒前台窗口等一整类竞态）。
    val permissionsSettled = cameraStatus != PermissionStatus.NotRequested &&
        micStatus != PermissionStatus.NotRequested
    // 不依赖 binder 是否已经连上：服务自己公布"当前是不是绑定模式"。
    val boundOnlyCall by VoiceCallService.boundOnlyCall.collectAsStateWithLifecycle()
    val boundSession = boundOnlyCall && callActive
    val startAction = VideoCallPolicy.startAction(
        hasStartIntent = callStartIntent && permissionsSettled,
        otherCallActive = otherCallActive,
        callActive = callActive,
        boundSession = boundSession,
        cameraGranted = cameraGranted,
        micGranted = micGranted,
    )
    LaunchedEffect(startAction, callActive, boundService, cameraGranted, micGranted) {
        when (startAction) {
            // 有任一权限：前台服务。重复调用是幂等的（同会话只做能力对账/升级），
            // 这也是"绑定模式→前台"与"权限组合变化刷新类型"的唯一入口。
            VideoCallStartAction.StartForeground ->
                VoiceCallService.start(context, ourConversationId, video = true)
            // 两个权限都没有：由可见 Activity 绑定运行；服务还没连上就等下一次。
            VideoCallStartAction.StartBound -> boundService?.startBoundCall(ourConversationId, video = true)
            // 别的会话正在通话：这一次进入的意图直接作废，之后绝不自动启动。
            VideoCallStartAction.BlockedByOtherCall -> callStartIntent = false
            VideoCallStartAction.None -> Unit
        }
        // 通话真的起来了才算"意图已经兑现"，否则（权限在毫秒间被撤销等）保留意图，
        // 让下一次判断还能退回绑定模式，而不是把用户卡在一个没开始的空面板里。
        if (callActive) callStartIntent = false
    }

    /**
     * 权限组合变化时的**显式会话刷新**（REVIEW_R5 §5）。
     *
     * `startAction` 在"通话已经在前台跑着"时恒为 [VideoCallStartAction.None]，
     * 所以 camera-only 的视频通话首次拒绝麦克风、之后又授权，旧实现永远不会再让
     * service 对账一次：前台类型不会加 MICROPHONE，ASR/TTS 也不会补建。
     * 这里走服务的专用入口：只对**本会话**生效、且幂等（能力组合没变就不做事）。
     */
    LaunchedEffect(cameraGranted, micGranted, callActive, boundService) {
        if (!callActive) return@LaunchedEffect
        boundService?.refreshSessionPermissions(ourConversationId)
        // 麦克风授权/ASR 配置变化会让"录一段"从可用变不可用（或反过来），按钮状态跟着刷新。
        boundService?.refreshVideoVoiceInput()
    }

    /**
     * 挂断：先结束服务里的这通通话，再停服务。
     *
     * 只挂断属于这一页的通话；只 stopService 是不够的——页面还绑着服务时
     * onDestroy 不会发生，旧相机/旧请求会继续活着。
     */
    fun hangUp() {
        if (callActive) {
            val service = boundService
            if (service != null && service.boundConversationId() == ourConversationId) {
                service.endCall()
            }
            VoiceCallService.stop(context)
        }
        onBack()
    }

    DisposableEffect(conversationId) {
        val intent = Intent(context, VoiceCallService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            try {
                context.unbindService(serviceConnection)
            } catch (error: Exception) {
                Log.e(TAG, "unbindService 失败", error)
            }
        }
    }

    // System back / edge-swipe must behave like the top-left back button: keep the call alive
    // in the floating call surface instead of hanging up. Only the explicit 挂断 action ends it.
    BackHandler { onBack() }

    // 服务自己持有稳定的 StateFlow：coordinator 重建时 UI 订阅对象不变，
    // 弹幕/礼物计数/提示始终是当前通话的状态（旧写法会一直订阅旧 flow）。
    val service = boundService
    val panelFlow = remember(service) { service?.videoState ?: VoiceCallService.idleVideoState }
    val panelState by panelFlow.collectAsStateWithLifecycle()

    // 最小化/重建后仍从这通通话记住的朝向开镜头（否则每次进来都会翻回前摄）。
    val cameraController = remember(conversationId) {
        VideoCallCameraController(context, initialFacing = VideoCallRuntime.cameraFacing(ourConversationId))
    }
    LaunchedEffect(cameraController, callState.isPreviewMirrored) {
        cameraController.setPreviewMirrored(callState.isPreviewMirrored)
    }
    val cameraStatusState by cameraController.status.collectAsStateWithLifecycle()
    val previewTransform by cameraController.previewTransform.collectAsStateWithLifecycle()

    /**
     * 人工校准角度同步用于预览和本轮发送的抓帧快照（见 [PreviewCalibration]）。
     *
     * 角度是**设置**（持久化），不是本页的临时状态：换镜头、退后台再回来、页面重建
     * 都不会把它清掉。画面偏了就点「右转/左转」，角度会立刻生效并写回设置；前后摄分开存。
     */
    val previewCalibrationFrontDegrees =
        PreviewCalibration.normalize(settings.videoCallSetting.previewRotationFrontDegrees)
    val previewCalibrationBackDegrees =
        PreviewCalibration.normalize(settings.videoCallSetting.previewRotationBackDegrees)
    val previewFacingIsFront = cameraStatusState.facing == CameraFacing.Front
    val previewCalibrationDegrees =
        if (previewFacingIsFront) previewCalibrationFrontDegrees else previewCalibrationBackDegrees
    val settingsStore: SettingsStore = org.koin.compose.koinInject()
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(
        cameraController,
        previewCalibrationFrontDegrees,
        previewCalibrationBackDegrees,
    ) {
        cameraController.setPreviewCalibrationDegrees(previewCalibrationFrontDegrees, frontFacing = true)
        cameraController.setPreviewCalibrationDegrees(previewCalibrationBackDegrees, frontFacing = false)
    }
    fun applyPreviewCalibration(degrees: Int) {
        val normalized = PreviewCalibration.normalize(degrees)
        val front = previewFacingIsFront
        // 先让画面转起来（不等写盘），再落库；两处都以同一个归一化角度为准。
        cameraController.setPreviewCalibrationDegrees(normalized, frontFacing = front)
        uiScope.launch {
            settingsStore.update { current ->
                val video = current.videoCallSetting
                current.copy(
                    videoCallSetting = if (front) {
                        video.copy(previewRotationFrontDegrees = normalized)
                    } else {
                        video.copy(previewRotationBackDegrees = normalized)
                    },
                )
            }
        }
    }

    // 相机入口按**通话作用域**注册：进另一个会话的视频页不会替换本通话的相机。
    // 通话在跑但协调器还没建好（启动中）时不注册，等 panelState.active 变成 true 再注册。
    LaunchedEffect(cameraController, callActive, panelState.active) {
        when {
            callActive && panelState.active ->
                VideoCallRuntime.registerCamera(ourConversationId, cameraController)
            !callActive ->
                VideoCallRuntime.unregisterCamera(ourConversationId, cameraController)
        }
    }
    val latestBoundService by rememberUpdatedState(boundService)
    DisposableEffect(cameraController) {
        onDispose {
            // 最小化/返回离开页面时：正在录的那一段必须当场作废，不能让录音挂在后台。
            latestBoundService?.onHostBackground()
            VideoCallRuntime.unregisterCamera(ourConversationId, cameraController)
            cameraController.unbind()
            // 离开视频面：自动缓存必须清掉，不能把旧画面随下一句发出去。
            // 只清**本会话**这一通的帧：别的会话的页面销毁不能动正在进行的那通电话。
            VideoCallRuntime.clearPendingFrame(ourConversationId)
        }
    }

    // 生命周期 STARTED 追踪：退后台立刻解绑，回前台再按门禁重绑。
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner, boundService) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleStarted = true
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    cameraController.unbind()
                    // 旧帧（自动 + 手动）一律作废：不在没有相机的时候留用旧图。
                    // 同样只针对本会话这一通。
                    VideoCallRuntime.clearPendingFrame(ourConversationId)
                    // 正在录的那一段跟着作废（退回后台就不再采音，也不偷偷发出去）。
                    boundService?.onHostBackground()
                    // 绑定模式的通话跟着可见性走：退后台就结束。
                    // 正常的前台语音/视频通话不受影响（isBoundOnly 为 false）。
                    boundService?.endBoundCallOnHostStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 相机门禁：Surface + 权限 + STARTED + 通话进行中，缺一不可。
    // 门禁结果交给控制器执行：不成立就立刻释放相机（授权弹窗/退后台/挂断都走这条路径），
    // 成立时如果没有在预览就重新开（页面重建、从后台回来）。
    val surfaceReady by cameraController.surfaceReady.collectAsStateWithLifecycle()
    val shouldBindCamera = VideoCallPolicy.shouldBindCamera(
        surfaceReady = surfaceReady,
        permissionGranted = cameraGranted,
        lifecycleStarted = lifecycleStarted,
        callActive = callActive,
        cameraEnabled = callState.isCameraEnabled,
    )
    LaunchedEffect(shouldBindCamera) {
        cameraController.setPreviewAllowed(shouldBindCamera)
    }

    // 计时只在通话真正进行时走；挂断立刻停。
    var duration by remember { mutableStateOf("00:00") }
    LaunchedEffect(startedAt, callActive) {
        if (!callActive || startedAt <= 0L) {
            duration = "00:00"
            return@LaunchedEffect
        }
        while (true) {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            duration = "%02d:%02d".format(seconds / 60, seconds % 60)
            delay(1_000L)
        }
    }

    // 定时抓帧：只刷新"待发送帧"，不自己发消息；通话结束/退后台就停。
    val captureMode = settings.videoCallSetting.frameCaptureMode
    LaunchedEffect(captureMode, cameraStatusState.state, callActive, shouldBindCamera) {
        if (!callActive || !shouldBindCamera) return@LaunchedEffect
        if (!captureMode.timerDriven || cameraStatusState.state != VideoCameraState.Previewing) return@LaunchedEffect
        val scheduler = VideoFrameScheduler()
        while (true) {
            delay(1_000L)
            if (!callActive) break
            // 手动夹住的一帧优先：定时器不许把它换成另一张。
            if (VideoCallRuntime.pendingFrame()?.manual == true) continue
            if (scheduler.shouldCapture(captureMode)) {
                // 抓帧是挂起操作：先记下相机代次，回来之后确认"还是同一通电话、同一代相机"，
                // 否则挂断/离开面板之后才返回的这一帧会被重新 hold 进待发送队列。
                val epoch = VideoCallRuntime.currentCameraEpoch()
                val frame = VideoCallRuntime.captureFrame()
                if (frame != null &&
                    VideoCallRuntime.holdPendingFrame(frame, ourConversationId, epoch)
                ) {
                    scheduler.markCaptured()
                }
            }
        }
    }

    // 礼物全屏特效：只在页面可见且通话中排队；只收**本会话**的礼物；退后台清队列并停动画。
    // 队列/身份逻辑在 VideoGiftEffectQueue 里（纯 Kotlin，可单测）：每次出队都是带新 key 的
    // 新实例，连续两次同档位礼物不会因为 activeEffect 值相等而让 overlay 的动画不重启。
    val effectsActive = lifecycleStarted && callActive
    var activeEffect by remember { mutableStateOf<VideoGiftEffectRequest?>(null) }
    val effectQueue = remember { VideoGiftEffectQueue() }
    LaunchedEffect(effectsActive, ourConversationId) {
        if (!effectsActive) return@LaunchedEffect
        try {
            VideoCallRuntime.giftEvents
                .filter { it.conversationId == ourConversationId }
                .collect { event -> effectQueue.offer(event.id, event.gift) }
        } finally {
            effectQueue.clear()
            activeEffect = null
        }
    }
    LaunchedEffect(effectsActive, ourConversationId) {
        if (!effectsActive) {
            effectQueue.clear()
            activeEffect = null
            return@LaunchedEffect
        }
        try {
            while (true) {
                val request = effectQueue.poll()
                if (request == null) {
                    delay(150L)
                    continue
                }
                activeEffect = request
                // Skip wakes this player immediately; pending gifts retain their FIFO order.
                withTimeoutOrNull(VIDEO_GIFT_EFFECT_DURATION_MS) {
                    snapshotFlow { activeEffect?.key }.first { it != request.key }
                }
                // 只清掉自己这一条：被取消/换人时不要把后来者顶掉。
                if (activeEffect?.key == request.key) activeEffect = null
            }
        } finally {
            effectQueue.clear()
            activeEffect = null
        }
    }

    // 挂断（包括从通知挂断）：立即解绑相机、停计时/特效、清空待发送帧。
    var hasJoinedCall by remember { mutableStateOf(false) }
    LaunchedEffect(callActive) {
        if (callActive) {
            hasJoinedCall = true
        } else if (hasJoinedCall) {
            cameraController.unbind()
            VideoCallRuntime.clearPendingFrame(ourConversationId)
            effectQueue.clear()
            activeEffect = null
            hasJoinedCall = false
        }
    }

    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val pendingFrameAt by VideoCallRuntime.pendingFrameAtMillis.collectAsStateWithLifecycle()

    // 以 (notice, noticeSeq) 为键：同一句提示再发一次也要重新淡入。
    LaunchedEffect(panelState.notice, panelState.noticeSeq) {
        val message = panelState.notice ?: return@LaunchedEffect
        notice = message
        delay(2_500L)
        if (notice == message) notice = null
    }

    val boundForUs = boundService?.boundConversationId() == ourConversationId
    val canSend = callActive && boundForUs && !otherCallActive
    // 手动录音这一段的状态（安全 Idle 初值；纯语音通话恒为 Idle）。
    val voiceInput = callState.voiceInput

    val sendText: () -> Unit = {
        val text = input.trim()
        when {
            text.isEmpty() -> Unit
            otherCallActive -> toaster.show("当前有另一通通话进行中，先挂断它再发这一边")
            !canSend -> toaster.show("通话还没准备好，请稍等")
            serviceUsableSend(boundService, text) -> input = ""
            else -> toaster.show("消息没有发出去，请稍后再试")
        }
    }
    val clipFrame: () -> Unit = {
        if (!cameraStatusState.isPreviewing || !shouldBindCamera) {
            toaster.show("相机还没准备好，暂时夹不到画面")
        } else {
            scope.launch {
                // 抓帧之前记下作用域快照；await 之后只有"同一通电话 + 同一代相机"才允许 hold。
                val epoch = VideoCallRuntime.currentCameraEpoch()
                val frame = VideoCallRuntime.captureFrame()
                if (frame == null) {
                    toaster.show("这一帧没有抓到")
                } else {
                    // 手动帧优先，直到它随下一句发出去或被清空。
                    val stored = VideoCallRuntime.holdPendingFrame(
                        VideoFrame(
                            bytes = frame.bytes,
                            width = frame.width,
                            height = frame.height,
                            capturedAtMillis = frame.capturedAtMillis,
                            manual = true,
                            rotationDegrees = frame.rotationDegrees,
                            mirror = frame.mirror,
                        ),
                        ourConversationId,
                        epoch,
                    )
                    toaster.show(if (stored) "已夹住这一帧，会随下一句发送" else "通话/相机已变化，这一帧没有夹住")
                }
            }
        }
    }

    // 明亮玻璃主题：与既有语音页（GlassCallPanel / interactionColors）同一套色板，
    // 不再是覆盖整屏的黑色遮罩。镜头画面缩进一张圆角卡里，文字改成常驻可回看的列表。
    val theme = assistant?.chatInteractionTheme ?: "ice"
    val colors = interactionColors(theme)
    val previewing = cameraStatusState.state == VideoCameraState.Previewing ||
        cameraStatusState.state == VideoCameraState.Requesting

    VideoCallLayout(
        assistantName = assistant?.name?.ifBlank { "AI 助手" } ?: "AI 助手",
        assistantAvatar = assistant?.avatar,
        presence = videoCallPresence(callState),
        duration = duration,
        giftCount = panelState.tally.total,
        transcript = panelState.history,
        notice = notice,
        otherCallActive = otherCallActive,
        errorMessage = callState.errorMessage,
        micGranted = micGranted,
        pendingFrameAt = pendingFrameAt,
        voiceInput = voiceInput,
        canSend = canSend,
        cameraEnabled = callState.isCameraEnabled,
        previewing = previewing,
        mirrored = callState.isPreviewMirrored,
        speakerOn = callState.isSpeakerOn,
        muted = callState.isMuted,
        processingOrSpeaking = callState.status == me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Processing ||
            callState.status == me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus.Speaking,
        typing = WindowInsets.isImeVisible,
        calibrationDegrees = previewCalibrationDegrees,
        cameraStatus = cameraStatusState,
        cameraPermissionGranted = cameraGranted,
        shouldBindCamera = shouldBindCamera,
        input = input,
        colors = colors,
        onBack = onBack,
        onInterrupt = { boundService?.requestInterrupt() },
        onSpeakerToggle = { boundService?.toggleSpeaker() },
        onSwitchCamera = {
            cameraController.switchCamera()
            // 把"用户选了哪支镜头"记到这通通话上，最小化回来/重建页面不再翻回前摄。
            VideoCallRuntime.setCameraFacing(ourConversationId, cameraController.requestedFacing())
        },
        onMirrorToggle = { boundService?.togglePreviewMirror() },
        onClipFrame = clipFrame,
        onCameraToggle = { boundService?.toggleVideoCamera() },
        onMuteToggle = { boundService?.toggleMute() },
        onHangUp = { hangUp() },
        onInputChange = { input = it.take(200) },
        // 输入框拿到焦点 = 用户要去打字：正在录的那一段当场取消并闭麦。
        onInputFocused = { boundService?.muteForTyping() },
        onSend = sendText,
        onCameraPermission = { cameraPermission.requestPermissions() },
        onCalibrate = { applyPreviewCalibration(it) },
        giftEffect = activeEffect,
        onGiftSkip = { renderedKey -> activeEffect = activeEffect.skipIfMatching(renderedKey) },
        preview = {
            CameraPreviewSurface(
                controller = cameraController,
                transform = previewTransform,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/** 打字发送的返回值语义：true 表示这一轮已经进入队列。 */
private fun serviceUsableSend(service: VoiceCallService?, text: String): Boolean =
    service?.submitTypedText(text) == true

// ======================================================================
// 全屏布局：满幅画面 + 半透明玻璃浮层（不再有圆角预览卡）
// ======================================================================

/**
 * 全屏视频通话布局（无状态）：预览铺满整屏，控件都是半透明玻璃 pill/圆钮。
 *
 * 分层（自下而上）：
 *  1. [preview] 槽位——真正的相机画面铺满 [Modifier.fillMaxSize]；
 *  2. 顶/底两块渐变 scrim，让白字控件在亮/暗画面上都可读；
 *  3. 玻璃控件：顶部身份/礼物/外放 pill、右侧一圈动作钮、底部（提示 + 字幕 + 输入条）。
 *
 * 所有可点项的开关/回调都由调用方（[VideoCallPage]）按通话与相机状态驱动；
 * 这里只负责"怎么摆、什么观感"。
 */
@Composable
internal fun VideoCallLayout(
    assistantName: String,
    assistantAvatar: Avatar?,
    presence: VideoCallPresence,
    duration: String,
    giftCount: Int,
    transcript: List<DanmakuLine>,
    notice: String?,
    otherCallActive: Boolean,
    errorMessage: String?,
    micGranted: Boolean,
    pendingFrameAt: Long?,
    voiceInput: VideoVoiceUiState,
    canSend: Boolean,
    cameraEnabled: Boolean,
    previewing: Boolean,
    mirrored: Boolean,
    speakerOn: Boolean,
    muted: Boolean,
    processingOrSpeaking: Boolean,
    /** 键盘弹起 = 打字模式：底栏只剩输入 pill，麦克风/挂断不布局，右侧栏隐藏。 */
    typing: Boolean = false,
    calibrationDegrees: Int,
    cameraStatus: VideoCameraStatus,
    cameraPermissionGranted: Boolean,
    shouldBindCamera: Boolean,
    input: String,
    colors: InteractionColors,
    onBack: () -> Unit,
    onInterrupt: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onSwitchCamera: () -> Unit,
    onMirrorToggle: () -> Unit,
    onClipFrame: () -> Unit,
    onCameraToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onHangUp: () -> Unit,
    onInputChange: (String) -> Unit,
    /** 输入框获得焦点：服务侧把正在录的一段作废并闭麦。 */
    onInputFocused: () -> Unit = {},
    onSend: () -> Unit,
    onCameraPermission: () -> Unit,
    onCalibrate: (Int) -> Unit,
    giftEffect: VideoGiftEffectRequest?,
    onGiftSkip: (String) -> Unit,
    preview: @Composable () -> Unit,
) {
    // 瞬时提示：淡出期间要渲染"最后一次非空文本"，否则退出动画里是空 pill。
    var lastNotice by remember { mutableStateOf<String?>(null) }
    if (notice != null) lastNotice = notice
    // 「方向」钮展开的方向校准 popover；5 秒无操作自动收起。
    var showCalibration by remember { mutableStateOf(false) }
    LaunchedEffect(showCalibration, calibrationDegrees) {
        if (showCalibration) {
            delay(5_000L)
            showCalibration = false
        }
    }
    // 键盘收起（打字→正常）时清掉输入框焦点，否则下一次点按会先触发"聚焦"事件。
    val focusManager = LocalFocusManager.current
    var wasTyping by remember { mutableStateOf(typing) }
    LaunchedEffect(typing) {
        if (wasTyping && !typing) focusManager.clearFocus()
        wasTyping = typing
    }
    val presencePhase = rememberPresencePhase(presence)
    Box(Modifier.fillMaxSize().background(Color(0xFF10161C))) {
        // 1) 满幅预览：不再裁进圆角卡，看到的整块就是发给模型的画面。
        if (cameraEnabled) {
            Box(Modifier.fillMaxSize()) { preview() }
        }
        if (!cameraEnabled) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.top, colors.bottom))),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VideoCallAvatar(
                        name = assistantName,
                        avatar = assistantAvatar,
                        presence = presence,
                        phase = presencePhase,
                        modifier = Modifier.size(120.dp),
                    )
                    VideoCallStatusText(presence, presencePhase)
                    Text("摄像头已关闭 · 通话继续", color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
                }
            }
        } else if (!previewing) {
            CameraOffCard(
                status = cameraStatus,
                permissionGranted = cameraPermissionGranted,
                onRequestPermission = onCameraPermission,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2) scrim：顶部压暗白字区，底部压暗字幕/输入区。
        Box(
            Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent))),
        )
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f)))),
        )

        // 3) 控件列：顶行 → 右侧动作 → 底部块。
        Column(
            Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // ---- 顶行：身份 pill | 礼物 + 外放 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = .22f))
                        .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(24.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↙", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    VideoCallAvatar(
                        name = assistantName,
                        avatar = assistantAvatar,
                        presence = presence,
                        phase = presencePhase,
                        modifier = Modifier.size(36.dp),
                    )
                    Column(Modifier.padding(start = 8.dp, end = 6.dp)) {
                        Text(
                            assistantName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 4f)),
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VideoCallStatusText(presence, presencePhase)
                            Text(" · $duration", color = Color.White.copy(alpha = .8f), fontSize = 11.sp)
                        }
                    }
                    // 点身份行 = 打断（保留原有打断入口）。
                    Text(
                        "打断",
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = processingOrSpeaking) { onInterrupt() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White.copy(alpha = if (processingOrSpeaking) .95f else .35f),
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (giftCount > 0) {
                    Text(
                        "🎁 $giftCount",
                        Modifier.clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = .22f))
                            .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (speakerOn) "外放" else "听筒",
                    Modifier.clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = .22f))
                        .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(24.dp))
                        .then(if (canSend) Modifier.clickable { onSpeakerToggle() } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // ---- 右侧动作列（打字模式下整个隐藏，避免误触） ----
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (!typing) {
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        RailAction("翻转", HugeIcons.CameraRotated01, enabled = shouldBindCamera, onClick = onSwitchCamera)
                        RailAction(if (mirrored) "镜像 开" else "镜像 关", HugeIcons.FlipHorizontal,
                            enabled = canSend, onClick = onMirrorToggle)
                        RailAction("方向", HugeIcons.RotateCrop, enabled = true,
                            onClick = { showCalibration = !showCalibration })
                        RailAction("抓帧", HugeIcons.Camera01,
                            enabled = previewing && shouldBindCamera, onClick = onClipFrame)
                        RailAction(if (cameraEnabled) "摄像头 关" else "摄像头 开",
                            if (cameraEnabled) HugeIcons.Camera01 else HugeIcons.CameraOff01,
                            enabled = canSend, onClick = onCameraToggle)
                    }
                    // 方向校准 popover：↺ / 90° / ↻ / 复位，5 秒闲置自动收起。
                    if (showCalibration) {
                        PreviewCalibrationControls(
                            degrees = calibrationDegrees,
                            onRotateLeft = { onCalibrate(PreviewCalibration.rotateLeft(calibrationDegrees)) },
                            onRotateRight = { onCalibrate(PreviewCalibration.rotateRight(calibrationDegrees)) },
                            onReset = { onCalibrate(PreviewCalibration.reset()) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 64.dp),
                        )
                    }
                }
            }

            // ---- 底部块 ----
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                // 瞬时提示（淡出期间保留最后一次文本）。
                androidx.compose.animation.AnimatedVisibility(
                    visible = notice != null,
                    enter = androidx.compose.animation.fadeIn(tween(200)),
                    exit = androidx.compose.animation.fadeOut(tween(600)),
                ) {
                    Text(
                        lastNotice.orEmpty(),
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = .7f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        color = colors.ink.copy(alpha = .85f),
                        fontSize = 11.sp,
                    )
                }
                if (otherCallActive) {
                    StatusPill("另一个会话正在通话中。这里不会发送消息，也不会控制那通电话；请先挂断它。", warn = true)
                }
                errorMessage?.takeIf { it.isNotBlank() }?.let { StatusPill(it, warn = true) }
                if (!micGranted) {
                    StatusPill("麦克风未开启，当前是纯文字模式")
                }
                if (pendingFrameAt != null) {
                    StatusPill("📸 已夹住一帧，会随下一句发送")
                }
                // 字幕：左对齐、玻璃气泡、顶部渐隐；打字时压矮让位（高度过渡，不跳变）。
                val transcriptHeight by animateDpAsState(
                    targetValue = if (typing) 160.dp else 240.dp,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "transcript-height",
                )
                TranscriptList(
                    lines = transcript,
                    colors = colors,
                    assistantName = assistantName,
                    modifier = Modifier.fillMaxWidth(0.82f).height(transcriptHeight),
                )

                Spacer(Modifier.height(8.dp))
                BottomBar(
                    input = input,
                    colors = colors,
                    canSend = canSend,
                    voiceInput = voiceInput,
                    muted = muted,
                    otherCallActive = otherCallActive,
                    typing = typing,
                    onInputChange = onInputChange,
                    onInputFocused = onInputFocused,
                    onSend = onSend,
                    onMuteToggle = onMuteToggle,
                    onHangUp = onHangUp,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        // 礼物全屏特效永远在最上层。
        VideoGiftEffectOverlay(
            effect = giftEffect,
            modifier = Modifier.fillMaxSize(),
            onSkip = onGiftSkip,
        )
    }
}

/** 右侧一列圆形玻璃动作钮 + 下方 10sp 白字标签。 */
@Composable
private fun RailAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) .22f else .10f))
                .border(1.dp, Color.White.copy(alpha = .35f), CircleShape)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label,
                tint = Color.White.copy(alpha = if (enabled) 1f else .4f),
                modifier = Modifier.size(20.dp))
        }
        Text(label, color = Color.White.copy(alpha = if (enabled) .9f else .35f), fontSize = 10.sp,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .7f), Offset(0f, 1f), 3f)))
    }
}

/** 底部常驻小提示 pill（warn = 红色错误风格，否则白玻璃）。 */
@Composable
private fun StatusPill(text: String, warn: Boolean = false) {
    Text(
        text,
        Modifier.padding(top = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(
                if (warn) Color(0xFFFFD9E1).copy(alpha = .8f) else Color.White.copy(alpha = .6f),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = if (warn) Color(0xFF8A2340) else Color(0xFF2F4858).copy(alpha = .85f),
        fontSize = 11.sp,
    )
}

/** 底部条：输入 pill + 麦克风 + 挂断；打字模式下只剩满宽的输入 pill。 */
@Composable
private fun BottomBar(
    input: String,
    colors: InteractionColors,
    canSend: Boolean,
    voiceInput: VideoVoiceUiState,
    muted: Boolean,
    otherCallActive: Boolean,
    typing: Boolean,
    onInputChange: (String) -> Unit,
    onInputFocused: () -> Unit,
    onSend: () -> Unit,
    onMuteToggle: () -> Unit,
    onHangUp: () -> Unit,
) {
    // 键盘弹出/收起时，输入条是"长大/缩回"而不是一下子换形状。
    Row(
        Modifier.fillMaxWidth().animateContentSize(tween(220, easing = FastOutSlowInEasing)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cornerRadius by animateDpAsState(
            targetValue = if (typing) 20.dp else 24.dp,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "input-corner",
        )
        // 输入 pill（打字时满宽、最多 4 行）。
        Row(
            Modifier.weight(1f)
                .heightIn(min = 48.dp)
                .animateContentSize(tween(220, easing = FastOutSlowInEasing))
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color.White.copy(alpha = .82f))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f)
                    .onFocusChanged { if (it.isFocused) onInputFocused() },
                maxLines = if (typing) 4 else 1,
                enabled = !otherCallActive,
                textStyle = TextStyle(color = colors.ink, fontSize = if (typing) 15.sp else 14.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text("说点什么…", color = colors.ink.copy(alpha = .45f),
                            fontSize = if (typing) 15.sp else 14.sp)
                    }
                    inner()
                },
            )
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (input.isBlank() || !canSend) colors.ink.copy(alpha = .12f) else colors.accent)
                    .clickable(enabled = input.isNotBlank() && canSend) { onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(HugeIcons.MailSend01, contentDescription = "发送",
                    tint = if (input.isBlank() || !canSend) colors.ink.copy(alpha = .4f) else Color.White,
                    modifier = Modifier.size(18.dp))
            }
        }
        val controlsWidth by animateDpAsState(
            targetValue = if (typing) 0.dp else 116.dp,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "input-controls-width",
        )
        Box(Modifier.width(controlsWidth).clipToBounds()) {
            if (!typing) {
                Row(Modifier.width(116.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(10.dp))
                    // 麦克风只控制免提开关，转写、发送和回复期间也能明确静音。
                    val micActionable = canSend
                    Box(
                        Modifier.size(44.dp).clip(CircleShape)
                            .background(
                                when {
                                    !micActionable -> Color.White.copy(alpha = .10f)
                                    muted -> Color(0xFF9C3352)
                                    voiceInput.isRecording -> Color(0xFF3FAE7C)
                                    else -> Color.White.copy(alpha = .22f)
                                },
                            )
                            .border(1.dp, Color.White.copy(alpha = .35f), CircleShape)
                            .toggleable(
                                value = !muted,
                                enabled = micActionable,
                                role = Role.Switch,
                                onValueChange = { onMuteToggle() },
                            )
                            .semantics {
                                stateDescription = when {
                                    muted -> "已静音"
                                    voiceInput.isRecording -> "正在聆听"
                                    else -> "已开启，回复后继续聆听"
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (muted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                            contentDescription = "麦克风",
                            tint = Color.White.copy(alpha = if (micActionable) 1f else .4f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // 挂断（打字模式下不布局：发送键不再挨着挂断，不会误点）。
                    Box(
                        Modifier.size(52.dp).clip(CircleShape)
                            .background(Color(0xFFCF5C7A))
                            .clickable { onHangUp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(HugeIcons.CallEnd01, contentDescription = "挂断", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

/** 字幕列表：左对齐玻璃气泡、顶部渐隐遮罩（不再有左侧色条）。 */
@Composable
private fun TranscriptList(
    lines: List<DanmakuLine>,
    colors: InteractionColors,
    assistantName: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // "跟着最新一条"是显式意图，不再从布局信息推断（旧写法在新行已进布局时恒为 false，
    // 恰好错过每一次追加；而挂在 count 上又在 400 条封顶后失效）。
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // 只在"滚动刚停下"（true→false）这一刻重新判定意图；
        // 首帧那次初始 false 不算"停下"，否则从未滚动过就先脱钉了。
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) stickToBottom = !listState.canScrollForward
            wasScrolling = scrolling
        }
    }
    // 新行到来或列表高度变化（打字模式压矮）都重新钉回底部。
    val listHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    LaunchedEffect(lines.lastOrNull()?.id, lines.size, listHeight) {
        if (lines.isNotEmpty() && stickToBottom) {
            listState.scrollToItem(lines.lastIndex)
            // 最后一条气泡可能很高：scrollToItem 只保证顶部可见，滚到底让整行露出。
            listState.scroll { scrollBy(100_000f) }
        }
    }
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("开始说话，文字会留在这里。", color = Color.White.copy(alpha = .7f), fontSize = 12.sp,
                textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            },
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier.widthIn(max = 290.dp)
                        .animateContentSize(tween(180, easing = FastOutSlowInEasing))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = .70f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        if (line.fromUser) "我" else assistantName,
                        color = if (line.fromUser) colors.ink.copy(alpha = .55f) else colors.ink.copy(alpha = .75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(line.text, color = colors.ink, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

/**
 * 预览卡右下角的方向校准小控件：`↺ 左转 | 90° | ↻ 右转 | 复位`。
 *
 * 自动几何按 display 方向转正；个别机型仍有偏差时用它兜底，转多了点「左转」回退或「复位」。
 * 抓帧是这块预览视图的快照，所以屏幕看到什么方向模型就收到什么；
 * 角度来源是设置（持久化），换镜头、退后台再进来仍然是这个角度。
 */
@Composable
private fun PreviewCalibrationControls(
    degrees: Int,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = .86f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White, RoundedCornerShape(18.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewCalibrationButton("↺", "预览左转 90°", onRotateLeft)
        Text(
            PreviewCalibration.label(degrees),
            color = Color(0xFF2F4858),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        PreviewCalibrationButton("↻", "预览右转 90°", onRotateRight)
        PreviewCalibrationButton("复位", "预览方向复位", onReset)
    }
}

/** 校准控件里的单个小按钮（玻璃风格，与预览卡同一套观感）。 */
@Composable
private fun PreviewCalibrationButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = contentDescription) { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = Color(0xFF2F4858),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
/**
 * 预览：TextureView + 显式变换矩阵，全屏显示。
 *
 * `SurfaceTexture` 的变换在 `setTransform` 之前已经把内容转正到设备自然方向（前摄还含
 * 一次水平镜像），所以 M 只负责按 display 方向补转、抵消默认拉伸、等比贴合与用户镜像。
 *
 * TextureView **默认先把内容拉伸到视图大小（S），再应用 setTransform 的 M**——屏幕
 * 画面是 M∘S。所以交给它的是 [PreviewTransform.matrix] = S⁻¹.then(F)（几何在
 * [VideoPreviewGeometry] 里算成纯数据，配套 JVM 单测逐点验证）。
 * 抓帧 = 对这个视图 `getBitmap`，得到的就是用户看到的同一帧。
 */
@Composable
private fun CameraPreviewSurface(
    controller: VideoCallCameraController,
    transform: PreviewTransform,
    modifier: Modifier = Modifier,
) {
    // 回调里要读到**当前**的矩阵：AndroidView 的 update 只在状态变化时跑一次，
    // 而 SurfaceTexture 回调可能在视图尺寸就绪/变化时后到。两边都套一遍，
    // 才不会出现"矩阵算对了但没落到 TextureView 上"（真机表现为侧躺 90°）。
    val latestTransform = rememberUpdatedState(transform)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                // 抓帧 = 这个视图的一次快照：挂进来让控制器能 getBitmap 到屏幕上同一帧。
                controller.attachPreviewView(this)
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        // 是否立刻开相机由门禁决定；控制器的 Surface 所有权在 detach 时释放。
                        controller.attachSurface(texture, width, height)
                        applyPreviewTransform(this@apply, latestTransform.value, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        controller.updateViewSize(width, height)
                        // 尺寸变化后控制器会**同步**发布按新尺寸算好的矩阵；这里直接用控制器
                        // 的当前值，避免用组合里还没刷新的旧矩阵在过渡帧上再算错一次。
                        applyPreviewTransform(this@apply, controller.previewTransform.value, width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        // 相机必须先停下来，才允许系统回收 SurfaceTexture。
                        controller.detachSurface()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view -> applyPreviewTransform(view, transform) },
        onRelease = { controller.attachPreviewView(null) },
    )
}

/**
 * 把纯几何算出来的矩阵应用到 TextureView 上。
 *
 * [PreviewTransform.matrix] 已经包含"抵消 TextureView 默认拉伸"的部分（见
 * [VideoPreviewGeometry.textureViewAffine]），这里只做一次坐标转换，不再自己拼旋转/缩放，
 * 否则就等于把几何算错两次。
 */
private fun applyPreviewTransform(
    view: TextureView,
    transform: PreviewTransform,
    width: Int = view.width,
    height: Int = view.height,
) {
    if (width <= 0 || height <= 0) return
    runCatching { view.setTransform(transform.matrix.toAndroidMatrix()) }
}

/**
 * 镜头没起来时的提示：**只在这张预览卡内部**说明原因。
 *
 * 旧实现用整屏黑底 + 覆盖全屏的黑色渐变把明亮玻璃控件压成黑底白字；现在相机不可用
 * 只是卡片里的一段说明，页面其余部分仍然是明亮主题。
 */
@Composable
private fun CameraOffCard(
    status: VideoCameraStatus,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF23323D), Color(0xFF2E4152)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("镜头未开启", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                cameraStatusReason(status),
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "文字、礼物和留影照常可用",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            if (!permissionGranted) {
                Box(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .clickable { onRequestPermission() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text("允许使用相机", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun cameraStatusReason(status: VideoCameraStatus): String = when (status.state) {
    VideoCameraState.PermissionDenied -> "没有相机权限，画面不会上传；打字和礼物照常可用。"
    VideoCameraState.Unavailable -> status.message.ifBlank { "没有可用的相机。" }
    VideoCameraState.Failed -> status.message.ifBlank { "相机打开失败。" }
    VideoCameraState.Requesting -> "相机正在启动…"
    VideoCameraState.Idle -> status.message.ifBlank { "相机已关闭。" }
    VideoCameraState.Previewing -> ""
}


internal enum class VideoCallPresence(val label: String) {
    Ready("准备就绪"),
    Online("在线"),
    Listening("正在聆听"),
    Transcribing("正在转写"),
    Sending("发送中"),
    Thinking("正在思考"),
    PreparingSpeech("准备朗读"),
    Speaking("正在讲话"),
    Error("出错了"),
}

internal fun videoCallPresence(state: VoiceCallUiState): VideoCallPresence = when {
    state.status == VoiceCallStatus.Error -> VideoCallPresence.Error
    state.status == VoiceCallStatus.Idle -> VideoCallPresence.Ready
    state.isAssistantSpeaking -> VideoCallPresence.Speaking
    !state.isMuted && state.voiceInput.isRecording -> VideoCallPresence.Listening
    state.voiceInput.status == VideoVoiceStatus.Transcribing -> VideoCallPresence.Transcribing
    state.voiceInput.status == VideoVoiceStatus.Sending -> VideoCallPresence.Sending
    state.status == VoiceCallStatus.Speaking -> VideoCallPresence.PreparingSpeech
    state.status == VoiceCallStatus.Processing -> VideoCallPresence.Thinking
    else -> VideoCallPresence.Online
}

@Composable
internal fun rememberPresencePhase(presence: VideoCallPresence): State<Float> = key(presence) {
    if ((presence == VideoCallPresence.Listening || presence == VideoCallPresence.Speaking) &&
        android.animation.ValueAnimator.areAnimatorsEnabled()
    ) {
        rememberInfiniteTransition(label = "call-presence").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(
                durationMillis = if (presence == VideoCallPresence.Listening) 1_600 else 1_200,
                easing = LinearEasing,
            )),
            label = "call-presence-phase",
        )
    } else {
        remember { mutableStateOf(0f) }
    }
}

internal fun listeningLetterPulse(phase: Float, index: Int): Float {
    val progress = (phase - index * .14f + 1f) % 1f
    return if (progress < .3f) sin(progress / .3f * PI).toFloat() else 0f
}

@Composable
internal fun VideoCallStatusText(
    presence: VideoCallPresence,
    phase: State<Float>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.heightIn(min = 20.dp).clearAndSetSemantics { text = AnnotatedString(presence.label) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (presence == VideoCallPresence.Listening) {
            presence.label.forEachIndexed { index, character ->
                Text(
                    character.toString(),
                    Modifier.graphicsLayer {
                        translationY = -2.dp.toPx() * listeningLetterPulse(phase.value, index)
                    }.testTag("listening-letter-$index"),
                    color = lerp(Color.White, Color(0xFF82E9D3), listeningLetterPulse(phase.value, index)),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            Text(
                presence.label,
                color = if (presence == VideoCallPresence.Speaking) Color(0xFFB3DAFF)
                    else Color.White.copy(alpha = .85f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun VideoCallAvatar(
    name: String,
    avatar: Avatar?,
    presence: VideoCallPresence,
    phase: State<Float>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.semantics {
            contentDescription = "助手头像"
            stateDescription = presence.label
        }.drawBehind {
            when (presence) {
                VideoCallPresence.Listening -> {
                    val breath = (1f - kotlin.math.cos(phase.value * 2f * PI).toFloat()) / 2f
                    drawCircle(Color(0xFF82E9D3).copy(alpha = .35f + .35f * breath),
                        radius = size.minDimension / 2f + 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                }
                VideoCallPresence.Speaking -> repeat(2) { index ->
                    val progress = (phase.value + index * .5f) % 1f
                    drawCircle(Color(0xFF8FCBFF).copy(alpha = .65f * (1f - progress)),
                        radius = size.minDimension / 2f + (1f + 4f * progress).dp.toPx(),
                        style = Stroke(1.5.dp.toPx()))
                }
                else -> Unit
            }
        },
    ) {
        AvatarDisplay(
            name = name,
            value = avatar ?: Avatar.Dummy,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val breath = (1f - kotlin.math.cos(phase.value * 2f * PI).toFloat()) / 2f
                val scale = if (presence == VideoCallPresence.Listening) 1f + .035f * breath else 1f
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

// （已删除输入 pill 内的"语音转文字"图标：麦克风是唯一的语音开关。）
