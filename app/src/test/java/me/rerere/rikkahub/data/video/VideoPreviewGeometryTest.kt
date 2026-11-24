/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 预览方向的**逐点几何**测试（Camera2Basic `configureTransform` 模型）。
 *
 * `SurfaceTexture` 的变换在 `TextureView.setTransform` 之前已经把内容转正到设备自然方向
 * （sensor 90/270 时内容宽高与 buffer 互换，前摄另含一次水平镜像）。因此交给
 * `setTransform` 的 M 只需要：display 补转 + 抵消"自然内容 → 视图"的默认拉伸 + 等比贴合。
 *
 * 这里验证**真正被看到的变换**：
 * 1. `setTransform` 拿到的矩阵 M 与默认拉伸 S 合起来（`M ∘ S`）必须等比、铺满/留边正确；
 * 2. 代表性像素点（中心、边中点）逐点落位；
 * 3. sensor 角度只进"自然尺寸"的宽高交换，不再进旋转公式（那正是旧的"画面侧躺/变形"来源）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class VideoPreviewGeometryTest {
    private val delta = 0.01f

    /** TextureView 把自然方向内容拉伸到视图大小的默认变换。 */
    private fun textureViewDefaultStretch(
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Affine = Affine.scale(
        viewWidth.toFloat() / bufferWidth.toFloat(),
        viewHeight.toFloat() / bufferHeight.toFloat(),
    )

    @Test
    fun `natural content size swaps only for ninety degree sensors`() {
        assertEquals(PixelSize(960, 1280), VideoPreviewGeometry.naturalContentSize(90, 1280, 960))
        assertEquals(PixelSize(960, 1280), VideoPreviewGeometry.naturalContentSize(270, 1280, 960))
        assertEquals(PixelSize(1280, 960), VideoPreviewGeometry.naturalContentSize(0, 1280, 960))
        assertEquals(PixelSize(1280, 960), VideoPreviewGeometry.naturalContentSize(180, 1280, 960))
    }

    @Test
    fun `preview rotation counter rotates the display and folds calibration`() {
        assertEquals(0, VideoPreviewGeometry.previewRotationDegrees(0))
        assertEquals(270, VideoPreviewGeometry.previewRotationDegrees(90))
        assertEquals(180, VideoPreviewGeometry.previewRotationDegrees(180))
        assertEquals(90, VideoPreviewGeometry.previewRotationDegrees(270))
        assertEquals(90, VideoPreviewGeometry.previewRotationDegrees(0, calibrationDegrees = 90))
        assertEquals(0, VideoPreviewGeometry.previewRotationDegrees(90, calibrationDegrees = 90))
    }

    /**
     * 竖屏手机 + 后摄（sensor 90）：natural = 960x1280，view 1080x2400。
     *
     * M 把 960x1280 的自然内容压回 540x1200 并放到视图正中央；与 S 复合后是
     * 1.875 倍等比 cover（宽被裁到视图边缘，上下各多出一截被视图裁掉）。
     */
    @Test
    fun `portrait back camera matrix centers upright content and composes to a uniform cover`() {
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
        )
        assertEquals(0, transform.rotationDegrees)
        assertEquals(PixelSize(960, 1280), transform.contentSize)
        val matrix = transform.matrix
        assertEquals(1.6667f, matrix.a, 1e-3f)
        assertEquals(0f, matrix.b, delta)
        assertEquals(0f, matrix.c, delta)
        assertEquals(1.0f, matrix.d, delta)
        assertEquals(-360f, matrix.e, 0.5f)
        assertEquals(0f, matrix.f, delta)

        // S = scale(1080/960, 2400/1280)；屏幕上真正显示的是 M ∘ S。
        val shown = textureViewDefaultStretch(960, 1280, 1080, 2400).then(matrix)
        assertEquals(1.875f, shown.uniformScale, delta)
        // 自然内容中心 -> 视图中心；左上角被 cover 裁到视图左外。
        assertPoint(540f, 1200f, shown.map(480f, 640f))
        assertPoint(-360f, 0f, shown.map(0f, 0f))
    }

    @Test
    fun `portrait front camera with a ninety degree sensor shares the back matrix`() {
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 270,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
        )
        assertEquals(0, transform.rotationDegrees)
        assertFalse("镜像只来自用户的预览镜像开关，不再按镜头朝向自动加", transform.mirrorHorizontally)
        assertEquals(PixelSize(960, 1280), transform.contentSize)
        val back = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
        )
        assertEquals(back.matrix, transform.matrix)
    }

    @Test
    fun `landscape rotates natural content ninety degrees uniformly`() {
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 90,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 2400,
            viewHeight = 1080,
        )
        assertEquals(270, transform.rotationDegrees)
        assertEquals(PixelSize(1280, 960), transform.contentSize)
        val shown = textureViewDefaultStretch(960, 1280, 2400, 1080).then(transform.matrix)
        assertEquals(1.875f, shown.uniformScale, delta)
        // 自然中心 -> 视图中心；自然上边缘中点 -> 视图左边缘中点（270° 顺时针）。
        assertPoint(1200f, 540f, shown.map(480f, 640f))
        assertPoint(0f, 540f, shown.map(480f, 0f))
    }

    @Test
    fun `calibration swaps the content size and stays uniform`() {
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
            calibrationDegrees = 90,
        )
        assertEquals(0, transform.rotationDegrees)
        assertEquals(90, transform.calibrationDegrees)
        assertEquals(PixelSize(1280, 960), transform.contentSize)
        val shown = textureViewDefaultStretch(960, 1280, 1080, 2400).then(transform.matrix)
        assertFalse("校准之后仍必须等比", shown.uniformScale.isNaN())
        // 校准转回横向后，960x1280 内容转 90° 成 1280x960：x 方向需要放大更多。
        assertEquals(2400f / 960f, shown.uniformScale, 0.05f)
    }

    @Test
    fun `fit letterboxes the whole frame instead of cropping`() {
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
            fit = true,
        )
        val shown = textureViewDefaultStretch(960, 1280, 1080, 2400).then(transform.matrix)
        // letterbox 取较小缩放（1.125）：960x1280 整图可见、等宽贴边，上下居中留边。
        assertEquals(1.125f, shown.uniformScale, delta)
        assertPoint(0f, 480f, shown.map(0f, 0f))
        assertPoint(1080f, 1920f, shown.map(960f, 1280f))
    }

    @Test
    fun `mirror flips x around the view center after everything else`() {
        val plain = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
        )
        val mirrored = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 1280,
            bufferHeight = 960,
            viewWidth = 1080,
            viewHeight = 2400,
            previewMirrored = true,
        )
        assertTrue(mirrored.mirrorHorizontally)
        val stretch = textureViewDefaultStretch(960, 1280, 1080, 2400)
        val shownPlain = stretch.then(plain.matrix)
        val shownMirrored = stretch.then(mirrored.matrix)
        // 同一点：镜像后 x 关于视图中心翻转，y 不变。
        listOf(0f to 0f, 480f to 640f, 960f to 1280f, 200f to 900f).forEach { (x, y) ->
            val before = shownPlain.map(x, y)
            val after = shownMirrored.map(x, y)
            assertEquals(1080f - before.x, after.x, delta)
            assertEquals(before.y, after.y, delta)
        }
    }

    @Test
    fun `unknown sizes degrade to identity instead of crashing`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 90,
            mirror = true,
            bufferWidth = 0,
            bufferHeight = 0,
            viewWidth = 0,
            viewHeight = 0,
        )
        assertEquals(Affine.IDENTITY, visible)
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 0,
            bufferHeight = 0,
            viewWidth = 0,
            viewHeight = 0,
        )
        assertEquals(0, transform.rotationDegrees)
        assertFalse(transform.mirrorHorizontally)
    }

    // ------------------------------------------------------------------
    // visibleAffine / textureViewAffine 的底层性质（与入口无关，直接钉矩阵）
    // ------------------------------------------------------------------

    /**
     * 核心不变量：**用户看到的** = 想要的几何。
     *
     * TextureView 实际渲染 `M ∘ S`（M 是我们 setTransform 的矩阵，S 是它的默认拉伸）。
     * 这里把几组真实尺寸下的 `M ∘ S` 与 `visibleAffine` 逐系数比对。
     */
    @Test
    fun `texture view matrix cancels its own default stretch`() {
        val cases = listOf(
            // natural 内容尺寸 -> view；rotation/mirror 是给 M 的参数
            Case(960, 1280, 1080, 2400, 0, false),
            Case(720, 1280, 1080, 1920, 90, false),
            Case(640, 480, 1080, 1920, 90, false),
            Case(960, 1280, 2400, 1080, 270, false),
            Case(720, 1280, 1080, 2340, 270, true),
            Case(720, 1280, 1080, 1920, 0, true),
        )
        cases.forEach { case ->
            val received = VideoPreviewGeometry.textureViewAffine(
                rotationDegrees = case.rotation,
                mirror = case.mirror,
                bufferWidth = case.bufferWidth,
                bufferHeight = case.bufferHeight,
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
            )
            // TextureView 先做默认拉伸 S，再应用 setTransform 的 M → 屏幕画面 = M ∘ S。
            val shown = textureViewDefaultStretch(
                case.bufferWidth,
                case.bufferHeight,
                case.viewWidth,
                case.viewHeight,
            ).then(received)
            val expected = VideoPreviewGeometry.visibleAffine(
                rotationDegrees = case.rotation,
                mirror = case.mirror,
                bufferWidth = case.bufferWidth,
                bufferHeight = case.bufferHeight,
                viewWidth = case.viewWidth,
                viewHeight = case.viewHeight,
            )
            assertEquals(case.describe(), expected.a, shown.a, 1e-3f)
            assertEquals(case.describe(), expected.b, shown.b, 1e-3f)
            assertEquals(case.describe(), expected.c, shown.c, 1e-3f)
            assertEquals(case.describe(), expected.d, shown.d, 1e-3f)
            assertEquals(case.describe(), expected.e, shown.e, 1e-3f)
            assertEquals(case.describe(), expected.f, shown.f, 1e-3f)
        }
    }

    /**
     * 1280x720 内容顺时针转 90° 后可见尺寸是 720x1280，等比放大 1.5 倍正好铺满
     * 1080x1920，因此四个角点**精确落到**四个 view 角点上。
     */
    @Test
    fun `portrait 1280x720 buffer lands exactly on the 1080x1920 view corners`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 90,
            mirror = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        // 90° 顺时针：左上 -> 右上，右下 -> 左下。
        assertPoint(1080f, 0f, visible.map(0f, 0f))
        assertPoint(1080f, 1920f, visible.map(1280f, 0f))
        assertPoint(0f, 1920f, visible.map(1280f, 720f))
        assertPoint(0f, 0f, visible.map(0f, 720f))
        // 中心必须落在视图中心。
        assertPoint(540f, 960f, visible.map(640f, 360f))
        // 等比：1.5 倍，两个方向一致。
        assertEquals(1.5f, visible.uniformScale, delta)
    }

    /**
     * 4:3 的内容塞进 1080x1920 的竖屏视图：等比放大 4 倍后左右被裁掉，
     * 上下正好贴合 —— 这就是 centerCrop，绝不能是拉伸。
     */
    @Test
    fun `4 by 3 buffer is center cropped into a tall view without distortion`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 0,
            mirror = false,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertEquals(4f, visible.uniformScale, delta)
        // 上下边正好对齐视图上下边。
        assertPoint(540f, 0f, visible.map(320f, 0f))
        assertPoint(540f, 1920f, visible.map(320f, 480f))
        // 左右被裁：中心 x=540 两侧各延伸 1280。
        assertPoint(-740f, 960f, visible.map(0f, 240f))
        assertPoint(1820f, 960f, visible.map(640f, 240f))
        assertCoversView(visible, 640, 480, 1080, 1920)
    }

    /**
     * 关键回归：因为有默认拉伸，交给 TextureView 的矩阵**本身不能是**"旋转 + 等比缩放"。
     * 如果它是，就说明代码还在用那个伪正确公式（拉伸根本没被抵消）。
     */
    @Test
    fun `the matrix handed to texture view is not a plain uniform scale`() {
        val received = VideoPreviewGeometry.textureViewAffine(
            rotationDegrees = 0,
            mirror = false,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        assertTrue(
            "buffer 与 view 比例不同时，必须用非等比矩阵抵消 TextureView 的默认拉伸",
            received.uniformScale.isNaN(),
        )
        // 但把默认拉伸抵消之后，屏幕上的效果必须是等比的。
        val shown = textureViewDefaultStretch(640, 480, 1080, 1920).then(received)
        assertEquals(4f, shown.uniformScale, delta)
    }

    @Test
    fun `front camera preview is mirrored horizontally`() {
        val visible = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 270,
            mirror = true,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        // 镜像前左上角落在右上角，镜像后应落在左上角。
        val unmirrored = VideoPreviewGeometry.visibleAffine(
            rotationDegrees = 270,
            mirror = false,
            bufferWidth = 1280,
            bufferHeight = 720,
            viewWidth = 1080,
            viewHeight = 1920,
        )
        val before = unmirrored.map(0f, 0f)
        assertPoint(1080f - before.x, before.y, visible.map(0f, 0f))
        // 中心点镜像后仍是中心。
        assertPoint(540f, 960f, visible.map(640f, 360f))
    }

    /** 任意旋转/镜像组合都必须铺满视图（不留黑边）并且等比。 */
    @Test
    fun `every rotation covers the view with uniform scale`() {
        val buffers = listOf(1280 to 720, 640 to 480, 720 to 1280, 1920 to 1080)
        val views = listOf(1080 to 1920, 1080 to 2400, 2400 to 1080, 800 to 800)
        listOf(0, 90, 180, 270).forEach { rotation ->
            listOf(false, true).forEach { mirror ->
                buffers.forEach { (bufferWidth, bufferHeight) ->
                    views.forEach { (viewWidth, viewHeight) ->
                        val visible = VideoPreviewGeometry.visibleAffine(
                            rotationDegrees = rotation,
                            mirror = mirror,
                            bufferWidth = bufferWidth,
                            bufferHeight = bufferHeight,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                        assertFalse(
                            "rotation=$rotation mirror=$mirror ${bufferWidth}x$bufferHeight -> " +
                                "${viewWidth}x$viewHeight 不是等比变换",
                            visible.uniformScale.isNaN(),
                        )
                        assertCoversView(visible, bufferWidth, bufferHeight, viewWidth, viewHeight)
                    }
                }
            }
        }
    }

    /**
     * 抓帧落盘的那一次归一化（AI 单张拍照路径仍在用）：原始像素 → 正立图像。
     *
     * 用 4x2 的小图逐点验证：90° 顺时针必须把左上角送到新图的右上角，
     * 并且新图尺寸宽高互换。
     */
    @Test
    fun `upright image affine rotates pixels clockwise exactly once`() {
        val upright = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 90,
            mirror = false,
            width = 4,
            height = 2,
        )
        assertEquals(PixelSize(width = 2, height = 4), VideoPreviewGeometry.uprightSize(4, 2, 90))
        assertPoint(2f, 0f, upright.map(0f, 0f)) // 左上 -> 右上
        assertPoint(0f, 4f, upright.map(4f, 2f)) // 右下 -> 左下
        assertPoint(0f, 0f, upright.map(0f, 2f)) // 左下 -> 左上
        assertPoint(2f, 4f, upright.map(4f, 0f)) // 右上 -> 右下
        assertEquals(1f, upright.uniformScale, delta)
    }

    @Test
    fun `upright image affine mirrors after rotating`() {
        val mirrored = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 90,
            mirror = true,
            width = 4,
            height = 2,
        )
        // 旋转后左上角在 (2,0)，镜像后回到 (0,0)。
        assertPoint(0f, 0f, mirrored.map(0f, 0f))
        assertPoint(2f, 4f, mirrored.map(4f, 2f))
        assertEquals(1f, mirrored.uniformScale, delta)
    }

    @Test
    fun `zero degree orientation leaves the image untouched`() {
        val identity = VideoPreviewGeometry.uprightImageAffine(
            rotationDegrees = 0,
            mirror = false,
            width = 5,
            height = 3,
        )
        assertPoint(0f, 0f, identity.map(0f, 0f))
        assertPoint(5f, 3f, identity.map(5f, 3f))
        assertEquals(PixelSize(width = 5, height = 3), VideoPreviewGeometry.uprightSize(5, 3, 0))
    }

    // ------------------------------------------------------------------
    // 预览人工校准（PreviewCalibration）
    // ------------------------------------------------------------------

    /**
     * 校准必须真的把**最终画面**转过去。
     *
     * display=0 时基准角为 0，校准 90° 后 1280x720 自然内容转 90° 成 720x1280，
     * 等比放大 8/3 倍正好铺满 1080x1920。
     */
    @Test
    fun `manual calibration rotates the final preview around the view centre`() {
        val bufferWidth = 1280
        val bufferHeight = 720
        val viewWidth = 1080
        val viewHeight = 1920
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 0,
            displayRotationDegrees = 0,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            calibrationDegrees = 90,
        )
        assertEquals("几何角度不受校准影响", 0, transform.rotationDegrees)
        assertEquals(90, transform.calibrationDegrees)
        assertEquals(PixelSize(720, 1280), transform.contentSize)

        val stretch = textureViewDefaultStretch(bufferWidth, bufferHeight, viewWidth, viewHeight)
        // 中心必须不动；转 90° 后 720x1280 的可见尺寸按 cover（2.8125x）居中铺满。
        val shown = stretch.then(transform.matrix)
        assertPoint(540f, 960f, shown.map(640f, 360f))
        assertEquals(1.5f, shown.uniformScale, delta)
        assertCoversView(shown, bufferWidth, bufferHeight, viewWidth, viewHeight)
        // buffer 上缘中点转到视图右缘中点（顺时针 90°）。
        assertPoint(viewWidth.toFloat(), 960f, shown.map(640f, 0f))
    }

    @Test
    fun `calibration wraps to right angles and can be undone`() {
        assertEquals(0, PreviewCalibration.normalize(360))
        assertEquals(270, PreviewCalibration.normalize(-90))
        assertEquals(90, PreviewCalibration.rotateRight(0))
        assertEquals(0, PreviewCalibration.rotateRight(270))
        assertEquals(270, PreviewCalibration.rotateLeft(0))
        assertEquals(0, PreviewCalibration.rotateLeft(90))
        // 转反了必须能原路退回：右转一次再左转一次 = 原地不动。
        assertEquals(90, PreviewCalibration.rotateLeft(PreviewCalibration.rotateRight(90)))
        assertEquals(listOf(0, 90, 180, 270), PreviewCalibration.STEPS)
    }

    @Test
    fun `zero calibration changes nothing and every device defaults to zero`() {
        fun transform(calibrationDegrees: Int) = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = 640,
            bufferHeight = 480,
            viewWidth = 480,
            viewHeight = 640,
            calibrationDegrees = calibrationDegrees,
        )

        val withoutCalibration = transform(0)
        assertEquals("0° 校准必须等于完全不加校准", withoutCalibration.matrix, transform(0).matrix)
        assertEquals(0, withoutCalibration.calibrationDegrees)
        assertTrue("90° 校准必须真的改变矩阵", transform(90).matrix != withoutCalibration.matrix)
        // 没有任何机型暗含设备补偿：默认校准在所有设备上都是 0°。
        assertEquals(0, PreviewCalibration.DEFAULT_DEGREES)
        assertEquals(0, PreviewCalibration.reset())
        assertEquals("90°", PreviewCalibration.label(90))
        assertEquals("0°", PreviewCalibration.label(360))
    }

    // ------------------------------------------------------------------
    // 预览 / 抓帧尺寸配对：同一宽高比，优先 4:3
    // ------------------------------------------------------------------

    @Test
    fun `stream sizes prefer a matching four by three pair over larger wide sizes`() {
        val previews = listOf(
            PixelSize(1920, 1080),
            PixelSize(1280, 720),
            PixelSize(1280, 960),
            PixelSize(640, 480),
            PixelSize(320, 240),
        )
        val captures = listOf(
            PixelSize(1920, 1080),
            PixelSize(1280, 720),
            PixelSize(1280, 960),
            PixelSize(640, 480),
        )
        val chosen = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(previews, captures, maxEdge = 1280),
        )
        assertEquals(PixelSize(1280, 960), chosen.preview)
        assertEquals(PixelSize(1280, 960), chosen.capture)
        assertEquals(
            VideoPreviewGeometry.aspectRatio(chosen.preview.width, chosen.preview.height),
            VideoPreviewGeometry.aspectRatio(chosen.capture.width, chosen.capture.height),
            1e-6f,
        )
    }

    @Test
    fun `stream sizes pair different resolutions when they share an aspect`() {
        // 预览只有 640x480(4:3)，JPEG 有更大的 1280x960(4:3)：允许不同分辨率，只要比例相同。
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
            captureSizes = listOf(PixelSize(1280, 720), PixelSize(1280, 960)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(640, 480), chosen?.preview)
        assertEquals(PixelSize(1280, 960), chosen?.capture)
    }

    @Test
    fun `stream sizes fall back to a shared wide aspect when no four by three exists`() {
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 720), PixelSize(960, 540)),
            captureSizes = listOf(PixelSize(1920, 1080), PixelSize(1280, 720)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(1280, 720), chosen?.preview)
        assertEquals(PixelSize(1280, 720), chosen?.capture)
    }

    @Test
    fun `stream sizes reject invalid and oversized entries`() {
        val chosen = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(0, 480), PixelSize(4032, 3024), PixelSize(640, 480)),
            captureSizes = listOf(PixelSize(0, 0), PixelSize(4000, 3000), PixelSize(1280, 960)),
            maxEdge = 1280,
        )
        assertEquals(PixelSize(640, 480), chosen?.preview)
        assertEquals(PixelSize(1280, 960), chosen?.capture)
    }

    @Test
    fun `stream pairing rejects transposed axes and merely close ratios`() {
        val preview = listOf(PixelSize(640, 480))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(preview, listOf(PixelSize(480, 640))))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(preview, listOf(PixelSize(640, 482))))
        val exact = VideoPreviewGeometry.chooseCameraStreamSizes(
            preview,
            listOf(PixelSize(1280, 964), PixelSize(800, 600)),
        )
        assertEquals(PixelSize(800, 600), exact?.capture)
    }

    @Test
    fun `stream sizes return null when no common aspect exists`() {
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1280, 720)),
            ),
        )
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(emptyList(), listOf(PixelSize(640, 480))))
        assertNull(VideoPreviewGeometry.chooseCameraStreamSizes(listOf(PixelSize(640, 480)), emptyList()))
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamSizes(
                listOf(PixelSize(640, 480)),
                listOf(PixelSize(640, 480)),
                maxEdge = 0,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 相机兼容：同比例优先、只有大 JPEG 时的有界兜底、无同比例时保留预览
    // ------------------------------------------------------------------

    @Test
    fun `stream plan picks the smallest larger same aspect jpeg when none fits the preview limit`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
                captureSizes = listOf(
                    PixelSize(3840, 2160),
                    PixelSize(1920, 1080),
                    PixelSize(2560, 1440),
                ),
            ),
        )
        assertEquals(PixelSize(1280, 720), plan.preview)
        // 只有 16:9 能配对：选最小的更大输出 1920x1080，而不是 4K/2K。
        assertEquals(PixelSize(1920, 1080), plan.capture)
        assertTrue(plan.captureEnabled)
        assertEquals("", plan.captureDisabledReason)
        val capture = requireNotNull(plan.capture)
        assertTrue("兜底 JPEG 必须大于预览上限", capture.maxEdge > VideoPreviewGeometry.PREVIEW_MAX_EDGE)
        assertTrue(
            capture.width.toLong() * capture.height <=
                VideoPreviewGeometry.CAPTURE_MAX_PIXELS.toLong(),
        )
        // 比例必须精确相等：所见即所发不允许近似比例。
        assertEquals(
            VideoPreviewGeometry.aspectRatio(plan.preview.width, plan.preview.height),
            VideoPreviewGeometry.aspectRatio(capture.width, capture.height),
            1e-6f,
        )
    }

    @Test
    fun `stream plan chooses the smallest bounded fallback not the largest jpeg`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(
                    PixelSize(4000, 3000),
                    PixelSize(1600, 1200),
                    PixelSize(2048, 1536),
                ),
            ),
        )
        assertEquals(PixelSize(640, 480), plan.preview)
        // 1600x1200 在该比例下是唯一落在 1080p 级预算内的更大输出；
        // 2048x1536(3.1MP) 与 4000x3000(12MP) 都必须被拒绝（整张解码会 OOM）。
        assertEquals(PixelSize(1600, 1200), plan.capture)
    }

    @Test
    fun `stream plan keeps preview and disables capture when every matching jpeg is too large`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 960)),
                captureSizes = listOf(
                    PixelSize(1920, 1440),
                    PixelSize(3264, 2448),
                    PixelSize(4000, 3000),
                ),
            ),
        )
        assertEquals(PixelSize(1280, 960), plan.preview)
        assertNull("超预算的同比例 JPEG 不能当成可用抓帧", plan.capture)
        assertFalse(plan.captureEnabled)
        assertEquals(
            VideoPreviewGeometry.CAPTURE_DISABLED_OVERSIZED_CAPTURE,
            plan.captureDisabledReason,
        )
    }

    @Test
    fun `stream plan keeps preview and never approximates when no aspect is shared`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(960, 540)),
                captureSizes = listOf(PixelSize(1280, 960), PixelSize(640, 480)),
            ),
        )
        // 没有共同比例：保留面积最大的预览，但绝不把 16:9 的预览配给 4:3 的 JPEG。
        assertEquals(PixelSize(1280, 720), plan.preview)
        assertNull(plan.capture)
        assertEquals(
            VideoPreviewGeometry.CAPTURE_DISABLED_NO_MATCHING_ASPECT,
            plan.captureDisabledReason,
        )
    }

    @Test
    fun `preview only plan keeps a valid preview geometry without any jpeg`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720)),
                captureSizes = listOf(PixelSize(1280, 960)),
            ),
        )
        assertNull("4:3 JPEG 不能配给 16:9 预览", plan.capture)
        // 停用抓帧不能影响预览几何：正立内容的宽高比仍等于自然内容的宽高比。
        val transform = VideoPreviewGeometry.textureViewTransform(
            sensorOrientation = 90,
            displayRotationDegrees = 0,
            bufferWidth = plan.preview.width,
            bufferHeight = plan.preview.height,
            viewWidth = 480,
            viewHeight = 640,
        )
        assertTrue(transform.contentSize.width > 0 && transform.contentSize.height > 0)
        assertEquals(
            VideoPreviewGeometry.aspectRatio(plan.preview.height, plan.preview.width),
            VideoPreviewGeometry.aspectRatio(transform.contentSize.width, transform.contentSize.height),
            1e-6f,
        )
    }

    @Test
    fun `stream plan prefers a within limit jpeg over a larger fallback`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1600, 1200), PixelSize(1280, 960)),
            ),
        )
        assertEquals(PixelSize(640, 480), plan.preview)
        // 有 <=1280 的同比例输出时就用它，不必去解一张更大的图。
        assertEquals(PixelSize(1280, 960), plan.capture)
        // 严格入口（只允许两边都 <=1280）也不会去选那张更大的 1600x1200。
        assertEquals(
            PixelSize(1280, 960),
            VideoPreviewGeometry.chooseCameraStreamSizes(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1600, 1200), PixelSize(1280, 960)),
            )?.capture,
        )
    }

    @Test
    fun `stream plan never falls back to a merely close or transposed aspect`() {
        val exact = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1920, 1080), PixelSize(1600, 1200)),
            ),
        )
        // 1600x1200 是精确 4:3；1920x1080 只是"接近"，不能拿来兜底。
        assertEquals(PixelSize(1600, 1200), exact.capture)
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(1920, 1080)),
            )?.capture,
        )
        // 横竖互换也不是同一个 sensor 轴，不能配对。
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(480, 640)),
                captureSizes = listOf(PixelSize(1600, 1200)),
            )?.capture,
        )
    }

    @Test
    fun `stream plan prefers a capturable preview over a larger preview only one`() {
        val plan = requireNotNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1280, 720), PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(640, 480)),
            ),
        )
        // 4:3 能配对，就选 640x480 的预览 + 抓帧；不是保留 1280x720 预览却停用抓帧。
        assertEquals(PixelSize(640, 480), plan.preview)
        assertEquals(PixelSize(640, 480), plan.capture)
    }

    @Test
    fun `stream plan needs a preview even when a matching jpeg exists`() {
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = emptyList(),
                captureSizes = listOf(PixelSize(640, 480)),
            ),
        )
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(1920, 1080)),
                captureSizes = listOf(PixelSize(1280, 720)),
            ),
        )
        assertNull(
            VideoPreviewGeometry.chooseCameraStreamPlan(
                previewSizes = listOf(PixelSize(640, 480)),
                captureSizes = listOf(PixelSize(640, 480)),
                previewMaxEdge = 0,
            ),
        )
    }

    @Test
    fun `preview card aspect and capture jpeg aspect agree for every orientation`() {
        val pair = VideoPreviewGeometry.chooseCameraStreamSizes(
            previewSizes = listOf(PixelSize(1280, 960)),
            captureSizes = listOf(PixelSize(1280, 960)),
        )
        assertNotNull(pair)
        // sensor 90/270 时正立内容的宽高比 = buffer 比例取反；0/180 时相等。
        listOf(90, 270).forEach { sensor ->
            val transform = VideoPreviewGeometry.textureViewTransform(
                sensorOrientation = sensor,
                displayRotationDegrees = 0,
                bufferWidth = pair!!.preview.width,
                bufferHeight = pair.preview.height,
                viewWidth = 480,
                viewHeight = 640,
            )
            assertEquals(PixelSize(960, 1280), transform.contentSize)
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private data class Case(
        val bufferWidth: Int,
        val bufferHeight: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val rotation: Int,
        val mirror: Boolean,
    ) {
        fun describe(): String =
            "natural=${bufferWidth}x$bufferHeight view=${viewWidth}x$viewHeight rotation=$rotation mirror=$mirror"
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, point: Point2) {
        assertEquals(expectedX, point.x, delta)
        assertEquals(expectedY, point.y, delta)
    }

    /** cover 的不变量：映射后的内容矩形必须**覆盖**整个视图（不能留黑边）。 */
    private fun assertCoversView(
        affine: Affine,
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val corners = listOf(
            affine.map(0f, 0f),
            affine.map(bufferWidth.toFloat(), 0f),
            affine.map(bufferWidth.toFloat(), bufferHeight.toFloat()),
            affine.map(0f, bufferHeight.toFloat()),
        )
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        assertTrue("左边缘 $minX 必须 <= 0", minX <= delta)
        assertTrue("上边缘 $minY 必须 <= 0", minY <= delta)
        assertTrue("右边缘 $maxX 必须 >= $viewWidth", maxX >= viewWidth - delta)
        assertTrue("下边缘 $maxY 必须 >= $viewHeight", maxY >= viewHeight - delta)
    }
}
