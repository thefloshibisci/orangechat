package me.rerere.rikkahub.ui.pages.video

import android.app.Application
import android.graphics.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [snapshotMatrix]（`T * M * T⁻¹`）的逐点验证。
 *
 * getBitmap 拿到的原始内容是默认拉伸的 buffer，屏幕画面 = 视图变换 M 作用后的结果；
 * 这里断言叠回目标位图的那次合成变换确实把视图坐标正确映过去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class VideoSnapshotMatrixTest {

    private fun mapped(matrix: Matrix, x: Float, y: Float): FloatArray {
        val pt = floatArrayOf(x, y)
        matrix.mapPoints(pt)
        return pt
    }

    @Test
    fun identityViewTransformAtSameSizeIsIdentity() {
        val m = snapshotMatrix(
            viewWidth = 500, viewHeight = 1000,
            targetWidth = 500, targetHeight = 1000,
            viewTransform = Matrix(),
        )
        assertArrayEquals(floatArrayOf(0f, 0f), mapped(m, 0f, 0f), 1e-3f)
        assertArrayEquals(floatArrayOf(500f, 1000f), mapped(m, 500f, 1000f), 1e-3f)
    }

    @Test
    fun mirrorViewTransformMapsCornersAcrossTheViewCenter() {
        // 前摄镜像：绕视图中心水平翻转（scale(-1,1, px=500, py=1000)）。
        val mirror = Matrix().apply { setScale(-1f, 1f, 500f, 1000f) }
        val m = snapshotMatrix(
            viewWidth = 1000, viewHeight = 2000,
            targetWidth = 500, targetHeight = 1000,
            viewTransform = mirror,
        )
        // 左上角 (0,0) 翻到右上 (500,0)；右下 (500,1000) 翻到左下 (0,1000)。
        assertArrayEquals(floatArrayOf(500f, 0f), mapped(m, 0f, 0f), 1e-3f)
        assertArrayEquals(floatArrayOf(0f, 1000f), mapped(m, 500f, 1000f), 1e-3f)
    }

    @Test
    fun rotationPlusScaleMatchesTMT() {
        // 视图变换 = 绕中心转 90° 后再做一点非等比缩放。
        val viewTransform = Matrix().apply {
            setRotate(90f, 500f, 1000f)
            postScale(1.1f, 0.9f, 500f, 1000f)
        }
        val viewW = 1000
        val viewH = 2000
        val targetW = 500
        val targetH = 1000
        val m = snapshotMatrix(viewW, viewH, targetW, targetH, viewTransform)

        // 手算期望 = T * M * T⁻¹（T = scale(target/view)）。
        val t = Matrix().apply {
            setScale(targetW.toFloat() / viewW, targetH.toFloat() / viewH)
        }
        val tInv = Matrix().apply {
            setScale(viewW.toFloat() / targetW, viewH.toFloat() / targetH)
        }
        val expected = Matrix().apply {
            setConcat(t, Matrix().apply { setConcat(viewTransform, tInv) })
        }
        for (pt in arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(500f, 0f),
            floatArrayOf(0f, 1000f),
            floatArrayOf(500f, 1000f),
            floatArrayOf(250f, 500f),
        )) {
            val a = pt.copyOf(); m.mapPoints(a)
            val b = pt.copyOf(); expected.mapPoints(b)
            assertArrayEquals("point ${pt.contentToString()}", b, a, 1e-2f)
        }
    }
}
