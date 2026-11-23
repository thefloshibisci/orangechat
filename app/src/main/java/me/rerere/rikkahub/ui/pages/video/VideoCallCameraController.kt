/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.video.CameraDisplayRotationTracker
import me.rerere.rikkahub.data.video.CameraFacing
import me.rerere.rikkahub.data.video.CameraGenerationGate
import me.rerere.rikkahub.data.video.CameraGenerationLifecycle
import me.rerere.rikkahub.data.video.cameraStartAllowed
import me.rerere.rikkahub.data.video.PixelSize
import me.rerere.rikkahub.data.video.PreviewCalibration
import me.rerere.rikkahub.data.video.PreviewTransform
import me.rerere.rikkahub.data.video.VideoCallCameraGateway
import me.rerere.rikkahub.data.video.VideoCallRuntime
import me.rerere.rikkahub.data.video.VideoCameraState
import me.rerere.rikkahub.data.video.VideoCameraStatus
import me.rerere.rikkahub.data.video.VideoFrame
import me.rerere.rikkahub.data.video.VideoFramePolicy
import me.rerere.rikkahub.data.video.VideoPreviewGeometry
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

private const val TAG = "VideoCallCamera"

/**
 * 单帧 JPEG 的**原始字节**上限：交给下游整张解码/压缩前的兜底。
 * 抓帧现在是所见 TextureView 的一次快照（[VideoFramePolicy.MAX_CAPTURE_EDGE] 上限内），
 * 正常远小于 1.5MB；4MB 是硬上限，超过就丢弃这一帧，绝不二次解码超大图。
 */
private const val MAX_CAPTURE_JPEG_BYTES = 4 * 1024 * 1024

/**
 * 视频面板专用的相机控制器。
 *
 * 与 [me.rerere.rikkahub.data.service.CameraService]（AI 后台单张拍照）互不干扰：
 * 这里长期持有一路预览会话，只在本面板可见时存活，离开/挂断立即释放。
 *
 * ## 生命周期按"一代"（generation）管理
 * 每次 bind/切换镜头/unbind/方向变化都换一代。旧一代的异步回调（onOpened / onConfigured /
 * onImageAvailable）必须在**同一把锁内**先确认自己仍是当前代才动手；发现自己过期就只
 * 释放自己的资源，既不改当前状态、也不会关掉新一代的会话。
 *
 * ## 线程模型（REVIEW_R5 §1）
 * 所有状态迁移都走 [CameraGenerationGate]：
 * - 公开方法（attachSurface / setPreviewAllowed / unbind / updateViewSize / switchCamera）
 *   与相机回调（onOpened / onConfigured / onDisconnected / onError / onImageAvailable /
 *   onCaptureStarted）都在 `gate.exclusive { }` 里完成 **check → 资源接管 → 操作 → 状态发布**；
 * - 上一版的 `@Synchronized isCurrent()` 只包住了一次 read，check 与 act 之间 stop/switch
 *   可以插进来，旧回调仍会把 status 写回 Previewing —— 这里由 [CameraGenerationGate.ifCurrent]
 *   原子化，过期就不再执行；
 * - 资源**释放**（`CameraDevice.close()` 等）一律在锁外：先摘除、再用返回值释放，
 *   不存在"持控制器锁等 generation 锁"的反向锁序。
 *
 * ## 换代的顺序（REVIEW_R6 §3）
 * 所有"结束/换代"都经过 [CameraGenerationLifecycle]：**门闩内**摘除当前代并让
 * `VideoCallRuntime` 的相机代次失效（epoch +1、清待发送帧、撤销留影许可），
 * **锁外**才释放相机资源。旧实现把失效放在释放之后，那段窗口里运行时尚认为旧代有效，
 * 排队中的抓帧/已发出的留影许可还能消费旧画面。
 * 锁序因此固定为 **gate → runtime**：运行时侧读本 gateway（`available` / `capture`）
 * 时绝不持自己的监视器（见 `VideoCallRuntime.requestSnapshot`），不会反向要门闩。
 * `startInternal` 还会重验"仍然允许预览 + 仍是同一个 surface/texture"，
 * UI 拆掉 Surface 之后之前排队的 start 不会再把相机开在已销毁的 texture 上。
 *
 * ## 抓帧：所见即所发（TextureView 快照）
 * `capture()` 不再走 ImageReader 的 STILL_CAPTURE。它把**屏幕上正在显示的预览**原样
 * 截下来：面板在 [attachPreviewView] 里把 TextureView 交给控制器，`capture()` 在
 * `Dispatchers.Main.immediate` 上调 `TextureView.getBitmap`——但 `getBitmap` 只回默认拉伸的
 * 原始 buffer、**不含** `setTransform`，所以要再用 [snapshotMatrix] 把视图变换叠回去，
 * 得到的才是用户看到的那帧（已含旋转/镜像/裁剪），再压成 JPEG。
 * 方向与镜像因此恒为 0/false：快照本身已经正立。提交时与交付前都复核
 * [geometryRevision]，期间改校准/镜像/显示方向/换代都会让这次抓帧作废返回 null；
 * `CancellationException` 原样透传，绝不吞。
 *
 * ## 方向与取景范围（REVIEW_R5 §4）
 * - 预览矩阵来自纯函数 [VideoPreviewGeometry]；预览是全屏 center-crop（fit = false），
 *   快照把这一整块都截给模型，不再依赖"预览与 JPEG 同宽高比"的配对。
 * - 预览输出仍按 [VideoPreviewGeometry.chooseCameraStreamPlan] 选（4:3 优先），但**不再**
 *   要求配一张同比例 JPEG；抓帧尺寸由视图大小按 [VideoFramePolicy.fit] 缩到上限内。
 * - 显示方向靠 `DisplayManager` 监听刷新（180° 时 view 尺寸不变，只靠
 *   `onSurfaceTextureSizeChanged` 永远不会更新），方向真的变了就重新发布矩阵、
 *   作废在飞的抓帧，并同步让运行时相机代次失效；
 * - 用户改校准角/镜像时同样作废在飞的抓帧：旧几何下的快照不能当成新几何的结果。
 */
class VideoCallCameraController(
    private val context: Context,
    initialFacing: CameraFacing = CameraFacing.Front,
) : VideoCallCameraGateway {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /**
     * 相机代次的串行化门闩：这个类里所有跨线程状态都只在它里面读写。
     * 纯 Kotlin，配套 JVM 交错测试（[me.rerere.rikkahub.data.video.CameraGenerationGate]）。
     */
    private val gate = CameraGenerationGate<Generation>()

    /**
     * 结束/换代一代相机资源的**唯一顺序**（REVIEW_R6 §3）：
     * 门闩内摘除 + 同临界区让运行时失效 → 锁外才释放相机。
     *
     * 这样"stop / 切镜头 / 退后台"从结束那一代开始，运行时就已经不认旧代的帧与留影许可，
     * 不存在"资源还没释放完、旧画面还能被塞进下一句"的窗口。
     */
    private val generationLifecycle = CameraGenerationLifecycle(
        gate = gate,
        invalidateRuntime = { VideoCallRuntime.notifyCameraGenerationChanged(this) },
        release = { generation -> generation.release() },
    )

    /** 显示方向的变化检测：同一角度连发不重复发布，180° 也能发现。 */
    private val rotationTracker = CameraDisplayRotationTracker()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val _status = MutableStateFlow(VideoCameraStatus(facing = initialFacing))
    val status: StateFlow<VideoCameraStatus> = _status.asStateFlow()

    /** 预览内容需要做的变换（旋转 + 等比填满 + 前摄镜像 + 抵消默认拉伸 + 用户校准）。 */
    private val _previewTransform = MutableStateFlow(PreviewTransform())
    val previewTransform: StateFlow<PreviewTransform> = _previewTransform.asStateFlow()

    /**
     * 用户校准角度同时作用于预览和发送给模型的抓帧快照。
     *
     * 故意放在控制器上、而不是放进 [Generation]：换镜头、退后台再回来、页面重建都会重建
     * generation，如果角度跟着 generation 走，用户每切一次前后摄就得重新校一次。
     * 这里它是控制器级状态，只有设置被改才变（来源是 `VideoCallSetting.previewRotationFrontDegrees`
     * 与 `previewRotationBackDegrees`），前后摄分开存。
     */
    private var previewCalibrationFront: Int = PreviewCalibration.DEFAULT_DEGREES
    private var previewCalibrationBack: Int = PreviewCalibration.DEFAULT_DEGREES
    private var previewMirrored = false
    // Monotonic under gate: A → B → A must still reject a capture submitted before B.
    private var geometryRevision = 0L

    /** 按镜头取校准角；必须在门闩内调用。 */
    private fun calibrationLocked(frontFacing: Boolean): Int =
        if (frontFacing) previewCalibrationFront else previewCalibrationBack

    /**
     * 显示着预览的那个 [TextureView]（弱引用，防页面泄漏）。
     *
     * 抓帧就是对它 `getBitmap`：屏幕上正在显示什么，模型就收到什么。
     * 由 [CameraPreviewSurface] 在建/拆时调用 [attachPreviewView]；只在门闩内读写。
     */
    private var previewView: WeakReference<TextureView>? = null

    /** 面板把显示预览的 TextureView 挂进来（null = 视图销毁/换 surface）。 */
    fun attachPreviewView(view: TextureView?) {
        gate.exclusive { previewView = view?.let { WeakReference(it) } }
    }

    fun setPreviewMirrored(mirrored: Boolean) {
        gate.exclusive {
            if (previewMirrored == mirrored) return@exclusive
            previewMirrored = mirrored
            invalidateGeometryLocked()
            gate.currentOrNull()?.let { publishTransformLocked(it) }
        }
    }

    override fun closeCamera() = unbind()

    /**
     * 写入预览校准角度（面板按钮 / 设置页调用）。
     *
     * 角度同时进入预览矩阵和抓帧 JPEG 的归一化旋转。重算预览矩阵、作废在飞的抓帧票据；
     * 下一个抓帧请求读取新的校准角度。角度会先归一化到 0/90/180/270，非法值不会进矩阵。
     * [frontFacing] 决定这个角度写到哪一支镜头。
     */
    fun setPreviewCalibrationDegrees(degrees: Int, frontFacing: Boolean) {
        val normalized = PreviewCalibration.normalize(degrees)
        gate.exclusive {
            if (normalized == calibrationLocked(frontFacing)) return@exclusive
            if (frontFacing) previewCalibrationFront = normalized else previewCalibrationBack = normalized
            invalidateGeometryLocked()
            gate.currentOrNull()?.let { publishTransformLocked(it) }
        }
    }

    /** 当前预览校准角度（面板显示用）；[frontFacing] 指定是哪一支镜头。 */
    fun previewCalibration(frontFacing: Boolean): Int = gate.exclusive { calibrationLocked(frontFacing) }

    /** 面板是否已经把一个可用的 SurfaceTexture 交了过来（Surface 就绪）。 */
    private val _surfaceReady = MutableStateFlow(false)
    val surfaceReady: StateFlow<Boolean> = _surfaceReady.asStateFlow()

    /** 当前这一代相机资源；所有回调都闭包持有自己的 [Generation]。 */
    private class Generation(
        val surface: Surface,
        /** 预览用的 SurfaceTexture：`setDefaultBufferSize` 只在这个公开 API 上（`Surface` 上是 @hide）。 */
        val texture: SurfaceTexture,
        /** **实际选中**那颗镜头的朝向（不是用户请求的兜底朝向）。 */
        val frontFacing: Boolean,
        val sensorOrientation: Int,
        val cameraId: String,
    ) {
        /** 开这一代时的显示方向；方向变化时在锁内更新（不再是打开时的 val）。 */
        @Volatile
        var displayRotationDegrees: Int = 0

        @Volatile
        var thread: HandlerThread? = null

        @Volatile
        var handler: Handler? = null

        @Volatile
        var device: CameraDevice? = null

        @Volatile
        var session: CameraCaptureSession? = null

        @Volatile
        var viewWidth: Int = 0

        @Volatile
        var viewHeight: Int = 0

        /** `setDefaultBufferSize` 用的 buffer 尺寸 = 相机真实输出尺寸。 */
        @Volatile
        var previewWidth: Int = 0

        @Volatile
        var previewHeight: Int = 0

        /**
         * 释放自己的全部资源。
         *
         * 幂等，而且**永远在控制器锁外**被调用（见类注释的锁序约定）；
         * 这里的 `@Synchronized` 只保护它自己的字段，与控制器锁没有嵌套关系。
         */
        @Synchronized
        fun release() {
            val closingSession = session
            val closingDevice = device
            session = null
            device = null
            runCatching { closingSession?.close() }
            runCatching { closingDevice?.close() }
            val closingThread = thread
            thread = null
            handler = null
            runCatching { closingThread?.quitSafely() }
        }
    }

    private enum class SessionStart { Started, Failed }

    // ------------------------------------------------------------------
    // 只读状态
    // ------------------------------------------------------------------

    /**
     * 相机能不能**交付一帧**（运行时据此决定是否抓帧、以及告诉模型"相机可用"）。
     *
     * 抓帧是所见 TextureView 的快照，所以只要这一代在预览、会话已建就能交付；
     * 快照本身在 [capture] 里再校验"TextureView 仍附着且有尺寸"。
     */
    override val available: Boolean
        get() = gate.exclusive {
            val current = gate.currentOrNull()
            _status.value.isPreviewing && current?.session != null
        }

    override val unavailableReason: String
        get() = when (_status.value.state) {
            VideoCameraState.PermissionDenied -> "没有相机权限"
            VideoCameraState.Unavailable -> _status.value.message.ifBlank { "没有可用的相机" }
            VideoCameraState.Failed -> _status.value.message.ifBlank { "相机打开失败" }
            VideoCameraState.Requesting -> "相机正在启动"
            VideoCameraState.Idle -> "相机未启动（离开视频面会立即释放）"
            VideoCameraState.Previewing -> ""
        }

    val isFrontCamera: Boolean
        get() = _status.value.facing == CameraFacing.Front

    /** 面板/服务用来确认"这一代相机还在预览"（预览-only 也算在预览）。 */
    fun isPreviewingGeneration(): Boolean = gate.exclusive {
        _status.value.isPreviewing && gate.currentOrNull()?.session != null
    }

    /** 当前这一代是否就是面板期望的朝向（切换镜头后旧帧不会串台）。 */
    fun currentFacing(): CameraFacing = _status.value.facing

    // ------------------------------------------------------------------
    // 面板驱动：Surface / 门禁 / 尺寸 / 切镜头 / 解绑
    // ------------------------------------------------------------------

    /**
     * TextureView 的 SurfaceTexture 就绪。
     *
     * 是否立刻开相机由 [setPreviewAllowed] 的门禁结果决定——授权弹窗/退后台期间
     * surface 可能在、但不该开相机。
     */
    fun attachSurface(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        var toRelease: Surface? = null
        var startSurface: Surface? = null
        var startTexture: SurfaceTexture? = null
        var startWidth = 0
        var startHeight = 0
        gate.exclusive {
            val existing = ownedSurface
            // 同一个 SurfaceTexture 重新 attach（页面重建）时复用已有的 Surface，
            // 只有真的换成另一个 texture 才新建并释放旧的。
            val surface = if (ownedTexture === texture && existing != null) existing else Surface(texture)
            if (existing !== surface) {
                ownedSurface = surface
                toRelease = existing
            }
            ownedTexture = texture
            if (viewWidth > 0 && viewHeight > 0) {
                pendingViewWidth = viewWidth
                pendingViewHeight = viewHeight
            }
            _surfaceReady.value = true
            registerDisplayListenerLocked()
            val current = gate.currentOrNull()
            when {
                !previewAllowed -> current?.let { updateViewSizeLocked(it, viewWidth, viewHeight) }
                current != null && current.surface === surface -> {
                    updateViewSizeLocked(current, viewWidth, viewHeight)
                }
                else -> {
                    startSurface = surface
                    startTexture = texture
                    startWidth = pendingViewWidth
                    startHeight = pendingViewHeight
                }
            }
        }
        toRelease?.let { runCatching { it.release() } }
        val surface = startSurface ?: return
        val textureToUse = startTexture ?: return
        startInternal(surface, textureToUse, startWidth, startHeight)
    }

    /** TextureView 的 SurfaceTexture 被回收：必须先停相机，再释放我们自己的 Surface。 */
    fun detachSurface() {
        var ended: Generation? = null
        var toRelease: Surface? = null
        gate.exclusive {
            _surfaceReady.value = false
            ownedTexture = null
            unregisterDisplayListenerLocked()
            // 摘除 + 让运行时失效在同一个临界区，之后才（锁外）释放资源。
            ended = stopInternalLocked()
            toRelease = ownedSurface
            ownedSurface = null
        }
        ended?.release()
        toRelease?.let { runCatching { it.release() } }
    }

    /**
     * 相机门禁结果变化：允许就（重新）开预览，不允许就立刻释放相机。
     *
     * 这条路径同时覆盖：授权弹窗期间的 ON_STOP/ON_START、页面重建、挂断，
     * 因此不会出现"背景里还留着 Surface 却在反复开相机"。
     */
    fun setPreviewAllowed(allowed: Boolean) {
        var ended: Generation? = null
        var startSurface: Surface? = null
        var startTexture: SurfaceTexture? = null
        var startWidth = 0
        var startHeight = 0
        gate.exclusive {
            previewAllowed = allowed && VideoCallRuntime.isCameraEnabled()
            if (!previewAllowed) {
                unregisterDisplayListenerLocked()
                ended = stopInternalLocked()
                return@exclusive
            }
            registerDisplayListenerLocked()
            if (gate.currentOrNull() != null) return@exclusive
            val surface = ownedSurface ?: return@exclusive
            val texture = ownedTexture ?: return@exclusive
            startSurface = surface
            startTexture = texture
            startWidth = pendingViewWidth
            startHeight = pendingViewHeight
        }
        ended?.release()
        val surface = startSurface ?: return
        val texture = startTexture ?: return
        // startInternal 自己会重验门禁：关掉预览之后这里也不会再开旧相机。
        startInternal(surface, texture, startWidth, startHeight)
    }

    /** 纯文字模式 / 退后台的兜底停止：只停相机，surface 留给回前台后重开。 */
    fun unbind() {
        setPreviewAllowed(false)
    }

    /** TextureView 尺寸变化（旋转屏幕 / 键盘弹出 / 分屏）。 */
    fun updateViewSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            gate.exclusive {
                pendingViewWidth = width
                pendingViewHeight = height
            }
        }
        gate.exclusive {
            val generation = gate.currentOrNull() ?: return@exclusive
            updateViewSizeLocked(generation, width, height)
        }
    }

    /**
     * 切换前后摄像头。
     *
     * 换代是一等动作：旧一代立刻从门闩里摘除（它的回调之后只会看到 stale），
     * 资源在锁外释放，再开新一代。
     */
    fun switchCamera() {
        var ended: Generation? = null
        gate.exclusive {
            requestedFacing = requestedFacing.toggled()
            ended = stopInternalLocked()
            // 只有在真的重开一代时才进入 Requesting：没有会话时保持 Idle，
            // 绝不把面板永久留在"相机正在启动…"。
            if (ended != null) {
                _status.value = VideoCameraStatus(
                    state = VideoCameraState.Requesting,
                    facing = requestedFacing,
                    message = "",
                )
            }
        }
        val previous = ended
        previous?.release()
        // 没有在跑的会话时什么都不做：等下次 bind 再开，绝不空转重开。
        if (previous != null) {
            startInternal(previous.surface, previous.texture, previous.viewWidth, previous.viewHeight)
        }
    }

    private fun updateViewSizeLocked(generation: Generation, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        generation.viewWidth = width
        generation.viewHeight = height
        publishTransformLocked(generation)
    }

    /** 必须在门闩内调用。 */
    private fun publishTransformLocked(generation: Generation) {
        if (gate.currentOrNull() !== generation) return
        // 尺寸没到位时**绝对不能**发布单位阵：单位阵的语义是"不旋转 + 按 buffer 拉伸到视图"，
        // 在竖屏 + 后摄(sensor 90) 的真机上，画面正好就侧躺 90°。
        // 旧实现会在这条路径上静默写出 [PreviewTransform]，等于把"还没算"显示成"不用转"。
        if (generation.viewWidth <= 0 || generation.viewHeight <= 0 ||
            generation.previewWidth <= 0 || generation.previewHeight <= 0
        ) {
            Log.i(
                TAG,
                "预览几何：尺寸未就绪，暂不发布变换 view=${generation.viewWidth}x${generation.viewHeight} " +
                    "buffer=${generation.previewWidth}x${generation.previewHeight}",
            )
            return
        }
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = generation.sensorOrientation,
            displayRotationDegrees = generation.displayRotationDegrees,
            bufferWidth = generation.previewWidth,
            bufferHeight = generation.previewHeight,
            viewWidth = generation.viewWidth,
            viewHeight = generation.viewHeight,
            // 预览矩阵带上校准与镜像：屏幕上看到的方向就是快照发给模型的方向。
            calibrationDegrees = calibrationLocked(generation.frontFacing),
            previewMirrored = previewMirrored,
            // 全屏预览用 center-crop（fit = false）铺满视图；快照截的就是这一整块。
            fit = false,
        )
        _previewTransform.value = transform
        logPreviewGeometry(generation, transform)
    }

    /**
     * 预览方向的几何日志（**不含任何画面内容**，只有角度/尺寸/矩阵）。
     *
     * 真机核对专用：一行里同时有 sensor / display / 前摄 / buffer / view / 用户校准 /
     * 最终矩阵，任何一处取值不对都能直接看出来。只在数值变化时打一次，不刷屏。
     *
     * 这里**不再打印 `SurfaceTexture.getTransformMatrix()`**：那份矩阵表示采样坐标变换，
     * 常规就带 Y-flip 与 crop/scale，"非单位阵 = 厂商多转了一次"是错误因果
     * （见 RESPONSE_DEVICE_FIX_R4 §1），据此推断方向只会误导排障。
     */
    private fun logPreviewGeometry(generation: Generation, transform: PreviewTransform) {
        val matrix = transform.matrix
        val line = buildString {
            append("预览几何 camera=").append(generation.cameraId)
            append(" facing=").append(if (generation.frontFacing) "front" else "back")
            append(" sensor=").append(generation.sensorOrientation)
            append(" display=").append(generation.displayRotationDegrees)
            append(" buffer=").append(generation.previewWidth).append('x').append(generation.previewHeight)
            append(" content=").append(transform.contentSize.width).append('x').append(transform.contentSize.height)
            append(" view=").append(generation.viewWidth).append('x').append(generation.viewHeight)
            append(" rotate=").append(transform.rotationDegrees)
            append(" calibration=").append(transform.calibrationDegrees)
            append(" mirror=").append(transform.mirrorHorizontally)
            append(" matrix=[")
            append(listOf(matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f).joinToString(","))
            append(']')
        }
        if (line == lastGeometryLog) return
        lastGeometryLog = line
        Log.i(TAG, line)
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 显示方向（REVIEW_R5 §4）
    // ------------------------------------------------------------------

    private var displayListenerRegistered = false

    /** 上一次打过的一行几何日志（只用于去重，避免每次尺寸变化都刷屏）。 */
    private var lastGeometryLog: String? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            onDisplayRotationMaybeChanged()
        }
    }

    /** 必须在门闩内调用。 */
    private fun registerDisplayListenerLocked() {
        val manager = displayManager ?: return
        if (displayListenerRegistered) return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess { displayListenerRegistered = true }
    }

    /** 必须在门闩内调用。 */
    private fun unregisterDisplayListenerLocked() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        val manager = displayManager ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
    }

    /**
     * 显示方向可能变了。
     *
     * 180° 旋转时 view 尺寸不变，`onSurfaceTextureSizeChanged` 不会触发，所以这条
     * 监听是唯一入口。方向真的变了：更新当前代的快照、重新发布矩阵、作废在飞的抓帧
     * 票据（按旧方向算出来的那一帧不能当这一代的结果），并同步让运行时相机代次失效。
     */
    private fun onDisplayRotationMaybeChanged() {
        val degrees = currentDisplayRotationDegrees()
        if (!rotationTracker.observe(degrees)) return
        gate.exclusive {
            val generation = gate.currentOrNull() ?: return@exclusive
            if (generation.displayRotationDegrees == degrees) return@exclusive
            generation.displayRotationDegrees = degrees
            invalidateGeometryLocked()
            publishTransformLocked(generation)
        }
    }

    /**
     * 让**正在飞的抓帧**作废。必须在门闩内调用。
     *
     * 校验角、镜像、显示方向变化后，旧几何下开始的抓帧不能当成新几何的结果。
     * 抓帧在提交与交付两处都复核 [geometryRevision]，所以这里只要把它 +1
     * （并让运行时相机代次失效）就足以让那次快照作废、返回 null。
     */
    private fun invalidateGeometryLocked() {
        geometryRevision++
        // Clear pending frames and revoke old permits atomically with the geometry change.
        invalidateRuntimeGeneration()
    }

    private fun currentDisplayRotationDegrees(): Int = runCatching {
        @Suppress("DEPRECATION")
        val surfaceRotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        VideoPreviewGeometry.displayRotationDegrees(surfaceRotation)
    }.getOrDefault(0)

    // ------------------------------------------------------------------
    // 开 / 停
    // ------------------------------------------------------------------

    /** 一颗实际存在的镜头：id + 特性 + **它自己的**朝向。 */
    private class CameraCandidate(
        val id: String,
        val characteristics: CameraCharacteristics,
        val facing: CameraFacing,
    )

    private fun startInternal(surface: Surface, texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        var dropped: Generation? = null
        gate.exclusive {
            // 排队期间 UI 可能已经拆掉 Surface / 关掉预览门禁（REVIEW_R6 §3）：
            // 这时绝不能再拿旧 surface 开一次相机，否则新相机会挂在已销毁的 texture 上。
            if (!VideoCallRuntime.isCameraEnabled() ||
                !cameraStartAllowed(previewAllowed, ownedSurface, surface, ownedTexture, texture)
            ) {
                return@exclusive
            }

            // 新一代：先把上一代从门闩里摘掉（它的回调从此只会看到 stale），
            // 并在**同一个临界区**里让运行时失效，锁外再释放资源。
            dropped = generationLifecycle.endLocked()

            if (!hasPermission()) {
                // surface 留着：用户随后授权时 setPreviewAllowed(true) 要能直接开相机。
                _status.value = VideoCameraStatus(
                    VideoCameraState.PermissionDenied,
                    requestedFacing,
                    "没有相机权限",
                )
                return@exclusive
            }
            val candidate = pickCamera(requestedFacing)
            if (candidate == null) {
                _status.value = VideoCameraStatus(VideoCameraState.Unavailable, requestedFacing, "没有可用的相机")
                return@exclusive
            }

            val generation = Generation(
                surface = surface,
                texture = texture,
                // 用**实际选中**那颗镜头的朝向：请求前摄但设备只有后摄时，
                // 旧实现仍然按"前摄"算法算旋转/镜像，画面方向和预览就会错。
                frontFacing = candidate.facing == CameraFacing.Front,
                sensorOrientation = candidate.characteristics
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                cameraId = candidate.id,
            )
            generation.viewWidth = viewWidth
            generation.viewHeight = viewHeight
            generation.displayRotationDegrees = currentDisplayRotationDegrees()
            rotationTracker.observe(generation.displayRotationDegrees)
            val backgroundThread = HandlerThread("VideoCallCamera").apply { start() }
            generation.thread = backgroundThread
            generation.handler = Handler(backgroundThread.looper)

            // 新一代上线同样在门闩内让旧相机代次失效（旧帧/旧许可一律作废）。
            generationLifecycle.adoptLocked(generation)
            _status.value = VideoCameraStatus(
                state = VideoCameraState.Requesting,
                facing = candidate.facing,
                message = "",
            )

            val failure = try {
                cameraManager.openCamera(
                    candidate.id,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            val opened = gate.ifCurrent(generation) { current ->
                                // 资源接管 + 建会话都在锁内：与 stop/switch 互斥。
                                current.device = camera
                                startSessionLocked(current, camera)
                            }
                            if (opened == null) {
                                // 过期的一代：只关掉自己这台设备，绝不碰当前会话与状态。
                                runCatching { camera.close() }
                                generation.release()
                                return
                            }
                            if (opened == SessionStart.Failed) {
                                failGeneration(
                                    generation,
                                    VideoCameraStatus(
                                        VideoCameraState.Failed,
                                        facingOf(generation),
                                        "启动相机会话失败",
                                    ),
                                )
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            runCatching { camera.close() }
                            failGeneration(
                                generation,
                                VideoCameraStatus(
                                    VideoCameraState.Unavailable,
                                    candidate.facing,
                                    "相机被其它功能占用",
                                ),
                            )
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            runCatching { camera.close() }
                            failGeneration(
                                generation,
                                VideoCameraStatus(
                                    VideoCameraState.Failed,
                                    candidate.facing,
                                    "相机错误($error)",
                                ),
                            )
                        }
                    },
                    generation.handler,
                )
                null
            } catch (error: CameraAccessException) {
                VideoCameraStatus(
                    VideoCameraState.Failed,
                    candidate.facing,
                    "无法打开相机: ${error.message}",
                )
            } catch (error: SecurityException) {
                VideoCameraStatus(
                    VideoCameraState.PermissionDenied,
                    candidate.facing,
                    "没有相机权限",
                )
            } catch (error: Exception) {
                VideoCameraStatus(
                    VideoCameraState.Failed,
                    candidate.facing,
                    "无法打开相机: ${error.message}",
                )
            }

            if (failure != null) {
                // 这一代没能起来：同样在门闩内摘除 + 失效，锁外释放。
                dropped = generationLifecycle.endLocked()
                _status.value = failure
                _previewTransform.value = PreviewTransform()
            }
        }
        dropped?.release()
    }

    /**
     * 建立预览会话（**必须在门闩内**，因为 onConfigured 的 check+act 也要在这把锁里）。
     *
     * @return [SessionStart.Failed] 表示配置/请求失败（调用方负责 failGeneration）。
     */
    private fun startSessionLocked(generation: Generation, camera: CameraDevice): SessionStart {
        val handler = generation.handler ?: return SessionStart.Failed
        val configMap = runCatching {
            cameraManager.getCameraCharacteristics(generation.cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull()
        val previewSize = pickStreamSizes(configMap) ?: return SessionStart.Failed
        generation.previewWidth = previewSize.width
        generation.previewHeight = previewSize.height

        // 关键：让 SurfaceTexture 的 buffer 尺寸等于相机真实输出尺寸。
        // 默认 buffer 尺寸是"视图大小"，那样 TextureView 的默认拉伸比就和我们算矩阵时
        // 假设的不一样，等比 centerCrop 也就无从下手（REVIEW_R4 §5）。
        // 注意公开 API 在 SurfaceTexture 上：android.view.Surface#setDefaultBufferSize 是 @hide。
        val bufferSizeApplied = runCatching {
            generation.texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        }.isSuccess
        if (!bufferSizeApplied) {
            // 不能静默假装几何成立：buffer 不是我们算的尺寸时，预览矩阵的"抵消默认拉伸"
            // 前提就失效了。真机上会表现为方向/比例异常，日志里必须留下证据。
            Log.w(
                TAG,
                "setDefaultBufferSize(${previewSize.width}x${previewSize.height}) 失败，" +
                    "预览矩阵对 buffer 尺寸的假设可能不成立",
            )
        }

        return try {
            // 抓帧是所见 TextureView 的快照，所以会话只挂预览 surface，不再要 ImageReader。
            camera.createCaptureSession(listOf(generation.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    val outcome = gate.ifCurrent(generation) { current ->
                        // 接管会话、下发重复请求、发布状态：全部在同一把锁内。
                        current.session = configured
                        try {
                            // 先发布矩阵再开始出帧：否则第一批 buffer 会先按旧矩阵
                            // （甚至单位阵）渲染一帧，真机上表现为闪一下拉伸/侧躺。
                            publishTransformLocked(current)
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(current.surface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                            }
                            configured.setRepeatingRequest(request.build(), null, handler)
                            _status.value = VideoCameraStatus(
                                VideoCameraState.Previewing,
                                facingOf(current),
                            )
                            SessionStart.Started
                        } catch (error: Exception) {
                            Log.w(TAG, "启动预览失败", error)
                            SessionStart.Failed
                        }
                    }
                    when (outcome) {
                        SessionStart.Started -> Unit
                        SessionStart.Failed -> failGeneration(
                            generation,
                            VideoCameraStatus(
                                VideoCameraState.Failed,
                                facingOf(generation),
                                "启动预览失败",
                            ),
                        )
                        // 过期的一代：只关自己的会话，绝不让"关闭新会话"发生。
                        null -> {
                            runCatching { configured.close() }
                            generation.release()
                        }
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    runCatching { failed.close() }
                    failGeneration(
                        generation,
                        VideoCameraStatus(
                            VideoCameraState.Failed,
                            facingOf(generation),
                            "相机预览配置失败",
                        ),
                    )
                }
            }, handler)
            SessionStart.Started
        } catch (error: Exception) {
            Log.w(TAG, "创建相机会话失败", error)
            SessionStart.Failed
        }
    }

    /**
     * 结束当前这一代并发布 Idle。**必须在门闩内调用**，返回被结束的那一代，
     * 由调用方在锁外释放它。
     *
     * 结束时立刻（同一临界区）让运行时相机代次失效：这样即便释放动作还没做完，
     * 旧代的帧与留影许可也已经不可能被消费（REVIEW_R6 §3）。
     */
    private fun stopInternalLocked(): Generation? {
        val ended = generationLifecycle.endLocked()
        _status.value = VideoCameraStatus(VideoCameraState.Idle, requestedFacing)
        _previewTransform.value = PreviewTransform()

        rotationTracker.reset()
        lastGeometryLog = null
        return ended
    }

    /**
     * 这一代失败/断开：只在它仍是当前代时才改状态；过期回调是**幂等**的，
     * 不会把新一代的 [gate] 清掉。资源释放放在锁外。
     */
    private fun failGeneration(generation: Generation, status: VideoCameraStatus) {
        gate.exclusive {
            if (gate.currentOrNull() !== generation) return@exclusive
            // 摘除 + 运行时失效在同一个临界区，释放放在锁外。
            generationLifecycle.endLocked()
            _status.value = status
            _previewTransform.value = PreviewTransform()

            rotationTracker.reset()
            lastGeometryLog = null
        }
        generation.release()
    }

    /**
     * 同步告诉运行时"这一代相机结束/变了"（REVIEW_R5 §2 / REVIEW_R6 §3）。
     *
     * 只允许在门闩内调用（[generationLifecycle] 与方向变化都走这里），因此锁序固定为
     * **gate → runtime**；运行时侧读 gateway 一律不持监视器，不会反向要门闩。
     * 不依赖 Compose 的 `LaunchedEffect`：退后台（ON_STOP）、切镜头、打开失败、方向变化
     * 都在这里**当场**让 runtime 的相机代次失效并清掉本会话的待发送帧。
     */
    private fun invalidateRuntimeGeneration() {
        VideoCallRuntime.notifyCameraGenerationChanged(this)
    }

    // ------------------------------------------------------------------
    // 抓帧
    // ------------------------------------------------------------------

    /**
     * 抓一帧：把**屏幕上正在显示的预览**原样截成 JPEG（所见即所发）。
     *
     * - 预审（门闩内）：这一代必须还在、会话已建；同时快照 [geometryRevision]。
     * - 快照（`Dispatchers.Main.immediate`）：TextureView 必须还附着、可用且有尺寸。
     *   `getBitmap` 只回默认拉伸的原始 buffer、**不含** `setTransform`，所以要用
     *   [snapshotMatrix]（`T*M*T⁻¹`）把视图变换显式叠回去，尺寸按
     *   [VideoFramePolicy.fit] 缩到 [VideoFramePolicy.MAX_CAPTURE_EDGE] 内。
     * - 编码（`Dispatchers.Default`）：压成 JPEG，回收 bitmap，再限 [MAX_CAPTURE_JPEG_BYTES]。
     * - 终审（门闩内）：generation 与 revision 还得是提交时那一份，否则这次作废。
     *
     * 快照本身已经正立，所以 `rotationDegrees = 0`、`mirror = false`（方向/镜像由预览矩阵
     * 决定，不再二次旋转）。`CancellationException` 原样透传。视图没附着/没尺寸时返回
     * null，绝不退回到其它来源凑一张取景不同的图。
     */
    override suspend fun capture(): VideoFrame? {
        // 预审 + 几何快照都在门闩内：换代/改校准会让 revision 变化，随后终审会拒绝。
        val submitted = gate.exclusive {
            val generation = gate.currentOrNull()
            if (generation?.session == null) null else generation to geometryRevision
        } ?: return null
        val generation = submitted.first
        val revision = submitted.second

        // 主线程截屏：TextureView.getBitmap 只能在 UI 线程拿当前显示的那一帧。
        val bitmap = try {
            withContext(Dispatchers.Main.immediate) {
                val view = previewView?.get() ?: return@withContext null
                if (!view.isAvailable || view.width <= 0 || view.height <= 0) {
                    return@withContext null
                }
                val target = VideoFramePolicy.fit(view.width, view.height, VideoFramePolicy.MAX_CAPTURE_EDGE)
                // getBitmap 只回 SurfaceTexture 内容（默认拉伸到目标尺寸），**不含** setTransform；
                // 屏幕上看到的是 M 作用后的画面，必须把视图变换再叠回去才是"所见"。
                val raw = runCatching { view.getBitmap(target.width, target.height) }.getOrNull()
                    ?: return@withContext null
                val out = android.graphics.Bitmap.createBitmap(
                    target.width, target.height, android.graphics.Bitmap.Config.ARGB_8888,
                )
                val canvas = android.graphics.Canvas(out)
                canvas.concat(
                    snapshotMatrix(
                        viewWidth = view.width,
                        viewHeight = view.height,
                        targetWidth = target.width,
                        targetHeight = target.height,
                        viewTransform = view.getTransform(null),
                    ),
                )
                canvas.drawBitmap(
                    raw, 0f, 0f,
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
                raw.recycle()
                canvas.setBitmap(null)
                out
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } ?: return null

        val width = bitmap.width
        val height = bitmap.height
        // 压缩放在后台线程；bitmap 回收后立刻进 JPEG 字节流。
        val bytes = try {
            withContext(Dispatchers.Default) {
                val output = ByteArrayOutputStream()
                val encoded = bitmap.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    VideoFramePolicy.JPEG_QUALITY,
                    output,
                )
                bitmap.recycle()
                if (!encoded) null else output.toByteArray()
            }
        } catch (cancelled: CancellationException) {
            bitmap.recycle()
            throw cancelled
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        } ?: return null

        if (bytes.isEmpty() || bytes.size > MAX_CAPTURE_JPEG_BYTES) {
            Log.w(TAG, "抓到的 JPEG ${bytes.size}B 超过上限(${MAX_CAPTURE_JPEG_BYTES})或为空，丢弃")
            return null
        }
        // 终审：快照之后用户改了校准/镜像/显示方向，或者换了代，这一帧就作废。
        val stillCurrent = gate.exclusive {
            gate.currentOrNull() === generation && geometryRevision == revision
        }
        if (!stillCurrent) return null
        return VideoFrame(
            bytes = bytes,
            width = width,
            height = height,
            capturedAtMillis = System.currentTimeMillis(),
            // 快照已经正立：方向/镜像由预览矩阵决定，这里不再二次旋转。
            rotationDegrees = 0,
            mirror = false,
        )
    }

    /**
     * 选镜头：优先请求的朝向，没有就退回第一颗。
     * 但**朝向必须读回实际那颗镜头**的特性，不能沿用请求值。
     */
    private fun pickCamera(requested: CameraFacing): CameraCandidate? {
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull().orEmpty()
        if (ids.isEmpty()) return null
        val wanted = if (requested == CameraFacing.Front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val candidates = ids.mapNotNull { id ->
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facing = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    CameraFacing.Front
                } else {
                    CameraFacing.Back
                }
                CameraCandidate(id, characteristics, facing) to (lensFacing == wanted)
            }.getOrNull()
        }
        if (candidates.isEmpty()) return null
        return (candidates.firstOrNull { it.second } ?: candidates.first()).first
    }

    /**
     * 只选预览尺寸（4:3 优先），不再要求配一张同宽高比的 JPEG。
     *
     * 抓帧是所见 TextureView 的快照：屏幕上正在显示什么模型就收到什么，
     * 不再需要"预览输出 == 抓帧输出"的配对。`chooseCameraStreamPlan` 只用来
     * 挑预览，capture 部分忽略。
     */
    private fun pickStreamSizes(configMap: android.hardware.camera2.params.StreamConfigurationMap?): Size? {
        val previewSizes = runCatching { configMap?.getOutputSizes(SurfaceTexture::class.java) }
            .getOrNull()?.map { PixelSize(it.width, it.height) }.orEmpty()
        val plan = VideoPreviewGeometry.chooseCameraStreamPlan(
            previewSizes = previewSizes,
            captureSizes = emptyList(),
            previewMaxEdge = VideoPreviewGeometry.PREVIEW_MAX_EDGE,
            captureMaxPixels = VideoPreviewGeometry.CAPTURE_MAX_PIXELS,
            captureFallbackMaxEdge = VideoPreviewGeometry.CAPTURE_FALLBACK_MAX_EDGE,
        ) ?: run {
            // 连预览都配不出来：这时相机确实开不了。
            Log.w(
                TAG,
                "相机没有最长边 <=${VideoPreviewGeometry.PREVIEW_MAX_EDGE} 的预览输出尺寸，停止相机会话",
            )
            return null
        }
        Log.i(TAG, "相机预览尺寸=${plan.preview.width}x${plan.preview.height}")
        return Size(plan.preview.width, plan.preview.height)
    }

    private fun facingOf(generation: Generation): CameraFacing =
        if (generation.frontFacing) CameraFacing.Front else CameraFacing.Back

    // 面板当前允许开预览吗（Surface + 权限 + STARTED + 通话中 四条门禁的结果）。
    // 只在门闩内读写。
    private var previewAllowed: Boolean = false

    // 最近一次知道的 TextureView 尺寸（补开相机时用它算预览矩阵）。只在门闩内读写。
    private var pendingViewWidth: Int = 0
    private var pendingViewHeight: Int = 0

    /** 用户最近一次请求的朝向（切镜头用）；实际生效的朝向在 [Generation.frontFacing]。 */
    private var requestedFacing: CameraFacing = initialFacing

    /** 当前请求的朝向：最小化/重建页面后由页面写回通话作用域。 */
    fun requestedFacing(): CameraFacing = gate.exclusive { requestedFacing }

    /**
     * 本控制器包装出来的 [Surface]（TextureView 的 SurfaceTexture 外面那层）。
     *
     * 只有它自己的所有者（面板 detach / 换成另一个 texture）才能释放它：
     * 切换镜头、退后台再回前台都会重建相机代次，但 surface 仍然是同一个，
     * 绝不能顺手 release 掉。
     */
    private var ownedSurface: Surface? = null

    /** 与 [ownedSurface] 配套的 SurfaceTexture（`setDefaultBufferSize` 只能从这里调）。 */
    private var ownedTexture: SurfaceTexture? = null
}

/**
 * 抓帧快照的视图变换矩阵：`TextureView.getBitmap` 返回的是**默认拉伸**的原始内容
 * （不带 `setTransform` 的 M），屏幕上真实画面是 `M` 在视图坐标里作用后的结果。
 *
 * 要把它叠到目标尺寸的 out 位图上，等价变换是 `T * M * T⁻¹`
 * （`T = scale(targetW/viewW, targetH/viewH)`，先把内容从视图坐标变回 buffer 比例，
 *  用 M 做完屏幕变换，再缩到目标尺寸）。
 *
 * 纯几何（无锁、无副作用），便于 Robolectric 单测逐点验证 `Matrix.mapPoints`。
 */
internal fun snapshotMatrix(
    viewWidth: Int,
    viewHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    viewTransform: Matrix,
): Matrix {
    val result = Matrix()
    if (viewWidth <= 0 || viewHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
        result.set(viewTransform)
        return result
    }
    val sx = targetWidth.toFloat() / viewWidth.toFloat()
    val sy = targetHeight.toFloat() / viewHeight.toFloat()
    // T * M * T⁻¹  —— Canvas 上 scale→concat→scale 的顺序组合。
    val toBuffer = Matrix().apply { setScale(sx, sy) }
    val backToTarget = Matrix().apply { setScale(1f / sx, 1f / sy) }
    result.setConcat(toBuffer, Matrix().apply { setConcat(viewTransform, backToTarget) })
    return result
}
