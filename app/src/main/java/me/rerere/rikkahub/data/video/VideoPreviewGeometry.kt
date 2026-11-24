/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import kotlin.math.cos
import kotlin.math.sin

/** 二维浮点坐标（图像像素 / 视图像素都用它）。纯值类型，不依赖 Android。 */
data class Point2(val x: Float, val y: Float)

/**
 * 2D 仿射变换，语义与 `android.graphics.Matrix` 的前两行完全一致（y 轴向下）：
 *
 * ```
 * x' = a*x + c*y + e
 * y' = b*x + d*y + f
 * ```
 *
 * 单测直接对**具体像素点**做映射，因此"转错方向 / 转两次 / 非等比拉伸"这类
 * 只在真机上才看得出来的错误，可以在 JVM 上钉死。
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f,
) {
    fun map(x: Float, y: Float): Point2 = Point2(a * x + c * y + e, b * x + d * y + f)

    fun map(point: Point2): Point2 = map(point.x, point.y)

    /** 先做 `this`，再做 [next]。 */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    /** 只取线性部分作用在向量上（不含平移），用来验证"等比"。 */
    fun mapVector(x: Float, y: Float): Point2 = Point2(a * x + c * y, b * x + d * y)

    /** 线性部分的统一缩放系数；非等比时返回 NaN。 */
    val uniformScale: Float
        get() {
            val xAxis = mapVector(1f, 0f)
            val yAxis = mapVector(0f, 1f)
            val sx = kotlin.math.hypot(xAxis.x, xAxis.y)
            val sy = kotlin.math.hypot(yAxis.x, yAxis.y)
            val dot = xAxis.x * yAxis.x + xAxis.y * yAxis.y
            return if (kotlin.math.abs(sx - sy) <= 1e-3f && kotlin.math.abs(dot) <= 1e-3f) sx else Float.NaN
        }

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float): Affine = Affine(e = dx, f = dy)

        fun scale(sx: Float, sy: Float): Affine = Affine(a = sx, d = sy)

        fun scale(scale: Float): Affine = scale(scale, scale)

        /** 顺时针旋转（Android 的 y 轴向下，所以正角度=顺时针）。 */
        fun rotateDegrees(degrees: Int): Affine {
            val radians = Math.toRadians(degrees.toDouble())
            val cosValue = cos(radians).toFloat()
            val sinValue = sin(radians).toFloat()
            return Affine(a = cosValue, b = sinValue, c = -sinValue, d = cosValue)
        }

        /** 以 [centerX],[centerY] 为不动点旋转。 */
        fun rotateAround(degrees: Int, centerX: Float, centerY: Float): Affine =
            translate(-centerX, -centerY).then(rotateDegrees(degrees)).then(translate(centerX, centerY))

        /** 以 [centerX],[centerY] 为不动点水平镜像。 */
        fun mirrorAround(centerX: Float, centerY: Float): Affine =
            translate(-centerX, -centerY).then(scale(-1f, 1f)).then(translate(centerX, centerY))
    }
}

/**
 * 预览层最终要交给 `TextureView.setTransform` 的变换。
 *
 * [matrix] 是**视图坐标系**下的完整矩阵（含抵消 TextureView 默认拉伸的部分 + 用户校准），
 * UI 层直接 `setValues` 即可；[rotationDegrees]/[calibrationDegrees]/[mirrorHorizontally]
 * 用于日志与测试断言。[contentSize] 是**生产 UI 也要用**的字段：预览卡的宽高比必须按它设置，
 * 否则预览会为了铺满卡片而裁掉一部分，而抓帧 JPEG 仍是整张源图。
 */
data class PreviewTransform(
    /** 由 display 推出来的转正角度（不含用户校准）。 */
    val rotationDegrees: Int = 0,
    /** 用户手动校准的角度（同时作用于预览与抓帧快照）。 */
    val calibrationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
    /**
     * 旋转 + 校准之后**正立源图**的尺寸（90/270 时宽高互换）。
     *
     * 预览卡的宽高比必须等于 `width / height`：此时等比 cover 刚好贴合，整张源图可见；
     * 抓帧快照的正立尺寸也是这个比例。[width]/[height] 为 0 表示尺寸还没就绪。
     */
    val contentSize: PixelSize = PixelSize(0, 0),
    val matrix: Affine = Affine.IDENTITY,
)

/**
 * 相机预览 / 抓帧快照 / EXIF 的方向计算（全部算成**纯数据**，可 JVM 单测）。
 *
 * Camera2 的 `SurfaceTexture` 已经替应用转过方向：它给每个 buffer 带上变换
 * （sensor orientation，前摄再加一次水平镜像），`TextureView` 在 `setTransform` **之前**
 * 就通过它的 transform matrix 应用掉。所以交给 `setTransform` 的 M 看到的不是原始
 * buffer，而是"设备自然方向下已经正立"的内容：
 *
 * - 尺寸 = [naturalContentSize]：sensor 为 90/270 时宽高互换；
 * - 旋转角 = [previewRotationDegrees]：`360 - display + 校准`（Camera2Basic 的
 *   `configureTransform` 模型），sensor orientation 只参与尺寸交换，不再进旋转公式。
 *
 * 预览矩阵（[textureViewTransform]）与抓帧快照（`TextureView.getBitmap`）共用同一套
 * 几何：屏幕上看到的方向/镜像就是发给模型的方向/镜像。
 */
object VideoPreviewGeometry {
    /**
     * 抓帧请求里给 HAL 的 `JPEG_ORIENTATION`。
     *
     * **固定 0**：camera2 允许 HAL 自己旋转像素，也允许只写 EXIF 标签；如果我们请求 90
     * 同时又按 90 转一次，在"真的转了像素"的机型上就会**双转**（旧实现正是这样）。
     * 请求 0 等于告诉 HAL"什么都别做"，像素保持 sensor 原始朝向，
     * 旋转只由 [uprightImageAffine] 施加一次，而且我们重新编码时会丢掉 EXIF，
     * 所以落盘文件的方向是唯一确定的。
     */
    const val REQUESTED_JPEG_ORIENTATION: Int = 0

    /**
     * `Surface.ROTATION_*` / `Display.getRotation()` 常量 → 角度（REVIEW_R5 §4）。
     *
     * 故意只用 Int 而不是 `android.view.Surface` 常量：这样这段映射是纯函数，
     * 可以在 JVM 上把 0/90/180/270 与"未知值退回 0"逐条钉住。
     * 对应关系与 Android 一致：`ROTATION_0 = 0`、`ROTATION_90 = 1`、
     * `ROTATION_180 = 2`、`ROTATION_270 = 3`。
     */
    fun displayRotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    /** 把任意角度归一化到 0/90/180/270。 */
    fun normalizeDegrees(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when {
            normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            normalized < 315 -> 270
            else -> 0
        }
    }

    /** 旋转 90/270 之后宽高会互换。 */
    fun swapped(width: Int, height: Int, rotationDegrees: Int): Boolean =
        rotationDegrees % 180 != 0

    /**
     * `setTransform` 的 M 实际作用的内容尺寸。
     *
     * `SurfaceTexture` 的变换在 `setTransform` 之前就把画面转正到设备自然方向：
     * sensor 为 90/270 时内容宽高与 buffer 互换，否则一致。
     */
    fun naturalContentSize(sensorOrientation: Int, bufferWidth: Int, bufferHeight: Int): PixelSize =
        if (normalizeDegrees(sensorOrientation) % 180 != 0) {
            PixelSize(width = bufferHeight, height = bufferWidth)
        } else {
            PixelSize(width = bufferWidth, height = bufferHeight)
        }

    /**
     * 预览要补的顺时针旋转角（Camera2Basic `configureTransform` 模型）：
     * 自然方向的内容已经正立，只需在设备离开自然方向时转回来。
     */
    fun previewRotationDegrees(displayRotationDegrees: Int, calibrationDegrees: Int = 0): Int =
        normalizeDegrees(360 - normalizeDegrees(displayRotationDegrees) + PreviewCalibration.normalize(calibrationDegrees))

    /**
     * 抓帧落盘时的**唯一一次**归一化：sensor 原始像素 → 正立图像。
     *
     * 输出图像的尺寸由 [uprightSize] 给出（90/270 时宽高互换）。
     * mirror 由图像调用方指定；视频通话把**预览的镜像开关**原样传进来，保证模型收到的
     * 方向/镜像与用户看到的一致。
     */
    fun uprightImageAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        width: Int,
        height: Int,
    ): Affine {
        if (width <= 0 || height <= 0) return Affine.IDENTITY
        val rotation = normalizeDegrees(rotationDegrees)
        val target = uprightSize(width, height, rotation)
        val centerX = width / 2f
        val centerY = height / 2f
        val targetCenterX = target.width / 2f
        val targetCenterY = target.height / 2f
        var matrix = Affine.translate(-centerX, -centerY)
            .then(Affine.rotateDegrees(rotation))
            .then(Affine.translate(targetCenterX, targetCenterY))
        if (mirror) {
            // 镜像在**正立之后**做：翻转的是用户看到的那一维。
            matrix = matrix.then(Affine.mirrorAround(targetCenterX, targetCenterY))
        }
        return matrix
    }

    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): PixelSize =
        if (swapped(width, height, normalizeDegrees(rotationDegrees))) {
            PixelSize(width = height, height = width)
        } else {
            PixelSize(width = width, height = height)
        }

    /**
     * 预览矩阵的**几何部分**（不含用户校准）。
     *
     * `TextureView` 先把**自然方向内容**按 buffer 尺寸拉伸到 view
     * （`S = diag(viewW/bufferW, viewH/bufferH)`），再把 `setTransform` 的矩阵 M 应用在
     * 视图坐标上——即屏幕最终画面是 M 在 S 之后：
     *
     * ```
     * 屏幕画面 = M ∘ S      （先 S 拉伸，再 M；可以断言、也有测试逐点验证）
     * ```
     *
     * 要让它等于"想要的画面" [visibleAffine] F，必须 `M = F ∘ S⁻¹`，也就是实现里的
     * `S⁻¹.then(F)`（对同一列向量先左乘 S⁻¹ 再左乘 F）。
     *
     * 前提是调用方用 `setDefaultBufferSize` 把 buffer 尺寸设成相机真实输出尺寸
     * （否则 `S` 就不是这里的 `diag(vw/bw, vh/bh)`，两者必须一致）。
     *
     * 全屏预览用 center-crop（fit = false）：`F` 把整张源图等比放大到铺满视图，
     * 超出部分被 view 裁掉。抓帧截的就是这块视图，与预览取景完全一致。
     */
    fun visibleAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        /**
         * true = letterbox the whole upright frame inside the view (never crop or stretch).
         * false keeps the historical center-crop behavior used by non-video callers.
         */
        fit: Boolean = false,
    ): Affine {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Affine.IDENTITY
        }
        val rotation = normalizeDegrees(rotationDegrees)
        val visible = uprightSize(bufferWidth, bufferHeight, rotation)
        // 等比：fit 取较小者（整图可见、留边），否则取较大者（填满、裁边）。两种都不拉伸。
        val scaleX = viewWidth.toFloat() / visible.width.toFloat()
        val scaleY = viewHeight.toFloat() / visible.height.toFloat()
        val scale = if (fit) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)
        val bufferCenterX = bufferWidth / 2f
        val bufferCenterY = bufferHeight / 2f
        val viewCenterX = viewWidth / 2f
        val viewCenterY = viewHeight / 2f
        var matrix = Affine.translate(-bufferCenterX, -bufferCenterY)
            .then(Affine.scale(scale))
            .then(Affine.rotateDegrees(rotation))
            .then(Affine.translate(viewCenterX, viewCenterY))
        if (mirror) {
            matrix = matrix.then(Affine.mirrorAround(viewCenterX, viewCenterY))
        }
        return matrix
    }

    /**
     * 给 `TextureView.setTransform` 的矩阵：屏幕最终画面是 **M ∘ S**——TextureView 先把
     * buffer 拉伸到视图大小（S，默认拉伸），再把 setTransform 的 M 应用在视图坐标上。
     * 要让屏幕等于想要的画面 F，必须 `M = F ∘ S⁻¹`，实现为 `S⁻¹.then(F)`。
     *
     * 真机实测（OPPO PKZ110 / Android 16）确认了这个复合顺序。`S⁻¹.then(F)` 对同一列向量
     * 先左乘 S⁻¹ 再左乘 F，正好抵消默认拉伸后再做旋转/贴合/镜像，得到的才是真等比的 F。
     */
    fun textureViewAffine(
        rotationDegrees: Int,
        mirror: Boolean,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        fit: Boolean = false,
    ): Affine {
        val visible = visibleAffine(
            rotationDegrees = rotationDegrees,
            mirror = mirror,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            fit = fit,
        )
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return visible
        val textureViewDefaultStretch = Affine.scale(
            viewWidth.toFloat() / bufferWidth.toFloat(),
            viewHeight.toFloat() / bufferHeight.toFloat(),
        )
        // 屏幕最终画面 = M ∘ S（先默认拉伸 S，再应用 setTransform 的 M），
        // 所以 M = F ∘ S⁻¹ = S⁻¹.then(F)。
        return textureViewDefaultStretch.inverse().then(visible)
    }

    /**
     * 预览矩阵总入口。
     *
     * [sensorOrientation] 只用来决定**自然内容尺寸**的宽高是否互换（[naturalContentSize]）；
     * 旋转角只看 display 与用户校准（[previewRotationDegrees]）——`SurfaceTexture` 的变换
     * 已经把 sensor 转正，再用 sensor 公式会双转。
     */
    fun textureViewTransform(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        /**
         * 用户手动校准角度；预览矩阵使用它。抓帧截的就是这块预览视图，所以屏幕看到的
         * 方向（含校准）就是发给模型的方向，无需再单独加一个抓帧角。
         */
        calibrationDegrees: Int = 0,
        /** 在屏幕坐标里再做一次水平镜像（用户开关，**不**从前摄自动推断）。 */
        previewMirrored: Boolean = false,
        /**
         * false（视频通话全屏预览）= center-crop：整张源图等比放大铺满视图、超出裁掉；
         * true = letterbox 整图进视图（不裁不拉伸，留边）。
         */
        fit: Boolean = false,
    ): PreviewTransform {
        val natural = naturalContentSize(sensorOrientation, bufferWidth, bufferHeight)
        val calibration = PreviewCalibration.normalize(calibrationDegrees)
        val total = previewRotationDegrees(displayRotationDegrees, calibration)
        val matrix = textureViewAffine(
            rotationDegrees = total,
            mirror = previewMirrored,
            bufferWidth = natural.width,
            bufferHeight = natural.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            fit = fit,
        )
        return PreviewTransform(
            rotationDegrees = previewRotationDegrees(displayRotationDegrees),
            calibrationDegrees = calibration,
            mirrorHorizontally = previewMirrored,
            // 旋转 + 校准之后整张源图的正立比例（UI/快照参考用；全屏预览本身不依赖它）。
            contentSize = uprightSize(natural.width, natural.height, total),
            matrix = matrix,
        )
    }

    /** 预览输出的最长边上限（与 `VideoFramePolicy.MAX_CAPTURE_EDGE` 一致）。 */
    const val PREVIEW_MAX_EDGE: Int = 1280

    /**
     * 任何被选中 JPEG 的**像素**上限（含"只有更大输出"时的兜底候选）。
     *
     * 为什么是 1920×1080（2,073,600）：下游 `VideoFrameStore` 目前是**整张 JPEG 解码**
     * 之后再缩放，ARGB_8888 一张 1080p 约 8.3MB；方向归一化还会再要一张同尺寸位图。
     * 两张大图 + 缩放目标仍在低端机可承受的瞬时预算内；超过这个上限的 JPEG 宁可停用
     * 抓帧，也不赌一次 12MP 全解码（OOM 会直接杀进程，比没有照片严重得多）。
     */
    const val CAPTURE_MAX_PIXELS: Int = 1920 * 1080

    /** 更大输出的最长边上限；与 [CAPTURE_MAX_PIXELS] 一起构成严格的解码预算。 */
    const val CAPTURE_FALLBACK_MAX_EDGE: Int = 1920

    /** 预览可以开，但设备没有任何与预览同宽高比的 JPEG 输出。 */
    const val CAPTURE_DISABLED_NO_MATCHING_ASPECT: String =
        "相机没有与预览同宽高比的照片输出，已保留预览并停用抓帧"

    /** 同比例 JPEG 存在，但最小的一张也超出安全解码预算。 */
    const val CAPTURE_DISABLED_OVERSIZED_CAPTURE: String =
        "相机同比例的照片输出都超出安全解码上限，已保留预览并停用抓帧"

    /**
     * 预览 / 抓帧选中的一组输出尺寸（**两边都不超过上限**的严格配对）。
     *
     * 两者**宽高比相同**是"预览取景范围 == 照片取景范围"的前提：否则预览按 4:3 取景、
     * 抓帧却是 16:9（或反过来），模型收到的画面必然比用户看到的多/少一块。
     */
    data class CameraStreamSizes(
        val preview: PixelSize,
        val capture: PixelSize,
    )

    /**
     * 一次相机会话实际可用的输出计划。
     *
     * [capture] 为 null 表示"预览能开，但抓帧停用"：设备没有和预览同宽高比、
     * 又不超过解码预算的 JPEG 输出。此时**绝不允许**用近似比例或拉伸凑一张图——
     * 那会让模型收到与用户所见不同的取景范围，违背"所见即所发"。
     * 调用方应保留预览（session 只挂 preview surface、不建 ImageReader），
     * 并让抓帧返回 null + [captureDisabledReason] 作为提示。
     */
    data class CameraStreamPlan(
        val preview: PixelSize,
        val capture: PixelSize?,
        val captureDisabledReason: String = "",
    ) {
        val captureEnabled: Boolean
            get() = capture != null
    }

    /** 宽高比（大边 / 小边，恒 >= 1），用于与"4:3"这类不区分横竖的目标比较。 */
    fun aspectRatio(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return maxOf(width, height).toFloat() / minOf(width, height).toFloat()
    }

    /** 单个预览候选与它选中的 JPEG（null = 这个比例没有可用 JPEG）。 */
    private class StreamCandidate(
        val preview: PixelSize,
        val capture: PixelSize?,
        val aspect: Float,
    )

    /**
     * 选一套"预览 + 抓帧"输出计划（相机兼容性修复的主入口）。
     *
     * 规则：
     * 1. **预览**只考虑正数、最长边不超过 [previewMaxEdge] 的尺寸；
     * 2. 预览与抓帧的有向 width/height 必须**精确**相等（交叉乘法），不接受横竖互换或近似比例；
     * 3. 对每个预览比例，优先选同比例、最长边不超过 [previewMaxEdge] 的 JPEG，取面积最大的那张
     *    （预算内画质最好）；所有 JPEG 都必须满足 [captureMaxPixels] 的严格解码预算；
     * 4. 如果该比例只有更大的 JPEG，选**最小的**那张同比例输出（离上限最近、解码最省），
     *    但必须同时满足 [captureFallbackMaxEdge] 与 [captureMaxPixels] 的严格预算；
     * 5. 优先 4:3（[preferredAspect]，允许横竖），其次预览面积最大的候选；
     * 6. 只要存在任何可抓帧的比例，就优先选可抓帧的候选；完全找不到同比例 JPEG 时，
     *    仍然返回**只预览**的计划（[CameraStreamPlan.capture] == null），由调用方停用抓帧，
     *    而不是让整台相机打不开，也绝不近似比例误发。
     *
     * 只有在连一个可用预览都没有时返回 null（这时相机确实开不了）。
     * 纯函数，不碰 Android，可在 JVM 上逐条钉住。
     */
    fun chooseCameraStreamPlan(
        previewSizes: List<PixelSize>,
        captureSizes: List<PixelSize>,
        previewMaxEdge: Int = PREVIEW_MAX_EDGE,
        captureMaxPixels: Int = CAPTURE_MAX_PIXELS,
        captureFallbackMaxEdge: Int = CAPTURE_FALLBACK_MAX_EDGE,
        preferredAspect: Float = 4f / 3f,
        aspectTolerance: Float = 0.02f,
    ): CameraStreamPlan? {
        if (previewMaxEdge <= 0) return null
        val previews = previewSizes.filter {
            it.width > 0 && it.height > 0 && it.maxEdge <= previewMaxEdge
        }
        if (previews.isEmpty()) return null
        val captures = captureSizes.filter { it.width > 0 && it.height > 0 }

        val candidates = previews.map { preview ->
            val aspect = aspectRatio(preview.width, preview.height)
            // Compare directed ratios exactly: 640x480 and 480x640 are different sensor axes.
            val sameAspect = captures.filter {
                it.width.toLong() * preview.height == preview.width.toLong() * it.height
            }
            val withinPreviewLimit = sameAspect
                .filter {
                    it.maxEdge <= previewMaxEdge &&
                        it.width.toLong() * it.height <= captureMaxPixels.toLong()
                }
                .maxByOrNull { it.width.toLong() * it.height }
            val boundedLarger = if (withinPreviewLimit != null) {
                null
            } else {
                sameAspect
                    .filter {
                        it.maxEdge > previewMaxEdge &&
                            it.maxEdge <= captureFallbackMaxEdge &&
                            it.width.toLong() * it.height <= captureMaxPixels.toLong()
                    }
                    // "最小同比例更大输出"：优先面积最小，其次最长边最小；绝不挑最大的那张。
                    .minWithOrNull(
                        compareBy<PixelSize> { it.width.toLong() * it.height }
                            .thenBy { it.maxEdge },
                    )
            }
            StreamCandidate(
                preview = preview,
                capture = withinPreviewLimit ?: boundedLarger,
                aspect = aspect,
            )
        }

        fun isPreferred(candidate: StreamCandidate): Boolean =
            kotlin.math.abs(candidate.aspect - preferredAspect) <= aspectTolerance

        fun rankPairs(list: List<StreamCandidate>): StreamCandidate? =
            list.maxWithOrNull(
                compareBy<StreamCandidate> { it.preview.width.toLong() * it.preview.height }
                    .thenBy { (it.capture?.let { capture -> capture.width.toLong() * capture.height }) ?: 0L },
            )

        val capturable = candidates.filter { it.capture != null }
        val chosen = (if (capturable.isNotEmpty()) {
            rankPairs(capturable.filter { isPreferred(it) }) ?: rankPairs(capturable)
        } else {
            // 只预览：没有可对齐的 JPEG，就不必为比例牺牲分辨率，取面积最大的预览。
            candidates.maxWithOrNull(
                compareBy<StreamCandidate> { it.preview.width.toLong() * it.preview.height }
                    .thenBy { if (isPreferred(it)) 1 else 0 },
            )
        }) ?: return null

        val capture = chosen.capture
        val reason = if (capture != null) {
            ""
        } else {
            // 全局判断：只要存在任何一个"预览比例 == 某个 JPEG 输出"的组合，
            // 说明停用抓帧不是因为没有同比例，而是那些同比例输出都超预算。
            val hasCommonAspect = previews.any { preview ->
                captures.any {
                    it.width.toLong() * preview.height == preview.width.toLong() * it.height
                }
            }
            if (hasCommonAspect) {
                CAPTURE_DISABLED_OVERSIZED_CAPTURE
            } else {
                CAPTURE_DISABLED_NO_MATCHING_ASPECT
            }
        }
        return CameraStreamPlan(
            preview = chosen.preview,
            capture = capture,
            captureDisabledReason = reason,
        )
    }

    /**
     * 严格版配对：预览与抓帧**两边**都不超过 [maxEdge]，找不到就返回 null。
     *
     * 大 JPEG 的兜底逻辑只在 [chooseCameraStreamPlan] 里启用；这个入口保持旧契约，
     * 供"必须把两边都压在上限内"的调用方/回归测试使用。
     */
    fun chooseCameraStreamSizes(
        previewSizes: List<PixelSize>,
        captureSizes: List<PixelSize>,
        maxEdge: Int = PREVIEW_MAX_EDGE,
        preferredAspect: Float = 4f / 3f,
        aspectTolerance: Float = 0.02f,
    ): CameraStreamSizes? {
        val plan = chooseCameraStreamPlan(
            previewSizes = previewSizes,
            captureSizes = captureSizes,
            previewMaxEdge = maxEdge,
            // 这个入口不允许任何一边超过 maxEdge，因此关掉大图兜底；像素预算也放开，
            // 保持"两边都 <= maxEdge"的旧契约。
            captureMaxPixels = Int.MAX_VALUE,
            captureFallbackMaxEdge = maxEdge,
            preferredAspect = preferredAspect,
            aspectTolerance = aspectTolerance,
        ) ?: return null
        val capture = plan.capture ?: return null
        return CameraStreamSizes(preview = plan.preview, capture = capture)
    }

    private fun Affine.inverse(): Affine {
        val determinant = a * d - b * c
        if (determinant == 0f) return Affine.IDENTITY
        val inverseA = d / determinant
        val inverseB = -b / determinant
        val inverseC = -c / determinant
        val inverseD = a / determinant
        return Affine(
            a = inverseA,
            b = inverseB,
            c = inverseC,
            d = inverseD,
            e = -(inverseA * e + inverseC * f),
            f = -(inverseB * e + inverseD * f),
        )
    }
}

/**
 * 预览方向的**人工校准**（预览怎么转，发给模型的抓帧快照就怎么转）。
 *
 * 自动几何只负责 display 方向的转正（`SurfaceTexture` 已按 sensor 转过），
 * 剩下个别机型的偏差用「右转 90° / 左转 90° / 复位」兜底；结果落在设置里，下次进来还是这个角度。
 *
 * ## 边界
 *
 * - 影响 [VideoPreviewGeometry.textureViewTransform] 的预览矩阵——屏幕上看到的方向
 *   就是发给模型的方向（抓帧是这块预览视图的快照）；
 * - 换镜头、退后台再回来、页面重建都**不清空**（角度是用户的设置，不是一次性状态）；
 * - 运行时改变角度会让**正在飞的抓帧**作废（旧的几何不能套在新画面上）。
 */
object PreviewCalibration {
    /** 默认不附加旋转（所有机型一致）。 */
    const val DEFAULT_DEGREES: Int = 0

    /** 一次按钮点击转动的角度。 */
    const val STEP_DEGREES: Int = 90

    /** 面板上的四个档位，与 [normalize] 之后的值一一对应。 */
    val STEPS: List<Int> = listOf(0, 90, 180, 270)

    /** 任意输入 → 0/90/180/270（非法值既不会写进设置，也不会进矩阵）。 */
    fun normalize(degrees: Int): Int = VideoPreviewGeometry.normalizeDegrees(degrees)

    /** 画面顺时针再转 90°。 */
    fun rotateRight(current: Int): Int = normalize(current + STEP_DEGREES)

    /** 画面逆时针再转 90°（发现转多了就用它回退）。 */
    fun rotateLeft(current: Int): Int = normalize(current - STEP_DEGREES)

    /** 复位成"不做人工校准"。 */
    fun reset(): Int = 0

    /** 面板上显示的角度文本。 */
    fun label(degrees: Int): String = "${normalize(degrees)}°"
}