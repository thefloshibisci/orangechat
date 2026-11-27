/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.DisplayMaterialMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalMaterialMode
import java.io.File
import kotlin.math.max

// ===== 普通气泡真实背景模糊（原型）=====
// 由 ChatPage 通过 CompositionLocal 下发的共享上下文：
// - 共享聊天背景 Painter（仅图片背景时非空）
// - 背景容器窗口原点与像素尺寸
// - 是否允许实时气泡模糊 + 模糊半径 px
@Stable
internal class LiveBubbleBlurGeometry {
    var originInWindow by mutableStateOf(Offset.Unspecified)
    var sizePx by mutableStateOf(Size.Unspecified)
}

internal data class LiveBubbleBlurContext(
    val imageBitmap: ImageBitmap? = null,
    val geometry: LiveBubbleBlurGeometry = LiveBubbleBlurGeometry(),
    // 复现页面最终背景所需的视觉参数（与 AssistantBackground 共享同一套数值）
    val baseColor: Color = Color.Unspecified,
    val imageAlpha: Float = 1f,
    val gradientTopAlpha: Float = 0f,
    val gradientBottomAlpha: Float = 0f,
    val enabled: Boolean = false,
    val radiusPx: Float = 0f,
)

internal val LocalLiveBubbleBlur = staticCompositionLocalOf { LiveBubbleBlurContext() }

/**
 * 在气泡背景片段子层 DrawScope 中，按与 AssistantBackground 完全一致的合成顺序重绘背景片段：
 * 1. 页面基础底色；
 * 2. 按背景纸不透明度（imageAlpha）绘制的 Crop + Center 图片；
 * 3. 页面垂直渐变遮罩（以完整背景容器坐标为基准，气泡只裁取对应部分）。
 * 以上全部位于同一 graphicsLayer 内，统一接受 RenderEffect 模糊。
 */
internal fun DrawScope.drawLiveBackgroundFragment(
    context: LiveBubbleBlurContext,
    layerOriginInWindow: Offset,
) {
    // Geometry changes during navigation/scroll only invalidate drawing, never the message composition.
    val backgroundOriginInWindow = context.geometry.originInWindow
    val backgroundSizePx = context.geometry.sizePx
    val imageBitmap = context.imageBitmap
    val baseColor = context.baseColor
    val imageAlpha = context.imageAlpha
    val gradientTopAlpha = context.gradientTopAlpha
    val gradientBottomAlpha = context.gradientBottomAlpha
    if (layerOriginInWindow == Offset.Unspecified) return
    if (backgroundSizePx.width <= 0f || backgroundSizePx.height <= 0f) return

    val hasBaseColor = baseColor != Color.Unspecified

    // 1. 页面基础底色（页面最终背景的最底层；气泡在背景容器内，直接填满本层即可）
    if (hasBaseColor) {
        drawRect(color = baseColor)
    }

    // 2. 与页面一致的半透明背景图片（使用与 AssistantBackground 相同的 imageAlpha）
    if (imageBitmap != null && imageBitmap.width > 0 && imageBitmap.height > 0) {
        val safeAlpha = imageAlpha.coerceIn(0f, 1f)
        // Crop：源图覆盖完整背景容器所需的统一缩放
        val scale = max(
            backgroundSizePx.width / imageBitmap.width,
            backgroundSizePx.height / imageBitmap.height,
        )
        val drawW = imageBitmap.width * scale
        val drawH = imageBitmap.height * scale
        // Center 对齐后，源图左上角在背景容器中的偏移
        val imgOffInBgX = (backgroundSizePx.width - drawW) / 2f
        val imgOffInBgY = (backgroundSizePx.height - drawH) / 2f
        // 转换为气泡片段层局部坐标 = 背景容器坐标 - 本层窗口原点
        val offX = imgOffInBgX + backgroundOriginInWindow.x - layerOriginInWindow.x
        val offY = imgOffInBgY + backgroundOriginInWindow.y - layerOriginInWindow.y
        // drawImage 是 DrawScope 公开 API：src 取源图全图，dst 为 Crop 缩放后的
        // 目标矩形（尺寸=drawW×drawH，起点=背景容器坐标减去本层窗口原点），
        // 精确复现 ContentScale.Crop + Alignment.Center；alpha 与页面背景纸一致。
        drawImage(
            image = imageBitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(imageBitmap.width, imageBitmap.height),
            dstOffset = IntOffset(offX.toInt(), offY.toInt()),
            dstSize = IntSize(drawW.toInt(), drawH.toInt()),
            alpha = safeAlpha,
        )
    }

    // 3. 页面垂直渐变遮罩：startY/endY 以完整背景容器为基准换算到气泡局部坐标，
    // 气泡只裁取完整页面渐变在当前位置对应的部分，不把渐变重新缩放进每个气泡。
    if (hasBaseColor && (gradientTopAlpha > 0f || gradientBottomAlpha > 0f)) {
        val gradTopY = backgroundOriginInWindow.y - layerOriginInWindow.y
        val gradBottomY = gradTopY + backgroundSizePx.height
        val gradientBrush = Brush.verticalGradient(
            colors = listOf(
                baseColor.copy(alpha = gradientTopAlpha.coerceIn(0f, 1f)),
                baseColor.copy(alpha = gradientBottomAlpha.coerceIn(0f, 1f)),
            ),
            startY = gradTopY,
            endY = gradBottomY,
        )
        drawRect(brush = gradientBrush)
    }
}

private const val TRANSLUCENT_BUBBLE_BASE_ALPHA = 0.72f
private const val TRANSLUCENT_BUBBLE_BORDER_ALPHA = 0.18f
private const val GLASS_BUBBLE_BORDER_ALPHA = 0.24f

@Composable
internal fun BubbleSurface(
    imagePath: String,
    cornerRadius: Dp,
    color: Color,
    overlayEnabled: Boolean,
    bubbleAlpha: Float,
    onClick: (() -> Unit)? = null,
    // 本轮原型：用户与助手的普通文本气泡传 true（最终由 LiveBubbleBlurContext 与 final 条件决定）
    enableLiveBubbleBlur: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val materialMode = LocalMaterialMode.current
    val liveContext = LocalLiveBubbleBlur.current
    val liveEnabled =
        enableLiveBubbleBlur &&
            liveContext.enabled &&
            liveContext.imageBitmap != null &&
            materialMode == DisplayMaterialMode.GLASS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            liveContext.radiusPx > 0f
    val cachedBlurEffect = remember(liveEnabled, liveContext.radiusPx) {
        if (liveEnabled) {
            AndroidRenderEffect.createBlurEffect(
                liveContext.radiusPx,
                liveContext.radiusPx,
                Shader.TileMode.CLAMP,
            ).asComposeRenderEffect()
        } else {
            null
        }
    }
    var liveLayerOriginInWindow by remember { mutableStateOf(Offset.Unspecified) }
    // 最终条件：仅此条件为 true 时才追加背景片段层（当前已验证 FINAL=1）
    val finalLiveBubbleBlurEnabled = liveEnabled && cachedBlurEffect != null
    // 注意：本体不包含 fillMaxSize/fillMaxWidth 等参与父级测量的尺寸 modifier，
    // 尺寸由调用点的 matchParentSize() 决定（只覆盖父 Box 已有尺寸，不参与父级测量）。
    val liveFragmentModifier = if (finalLiveBubbleBlurEnabled) {
        Modifier
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                if (origin != liveLayerOriginInWindow) {
                    liveLayerOriginInWindow = origin
                }
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                renderEffect = cachedBlurEffect
            }
            .drawBehind {
                // 页面背景完整合成作为该 graphicsLayer 节点的直接内容：
                // 底色 → 半透明图片 → 页面渐变遮罩，统一接受 RenderEffect 模糊
                drawLiveBackgroundFragment(
                    context = liveContext,
                    layerOriginInWindow = liveLayerOriginInWindow,
                )
            }
    } else {
        Modifier
    }
    val effectiveAlpha = when (materialMode) {
        DisplayMaterialMode.TRANSLUCENT -> TRANSLUCENT_BUBBLE_BASE_ALPHA * bubbleAlpha
        DisplayMaterialMode.GLASS -> bubbleAlpha
        DisplayMaterialMode.FOLLOW_THEME,
        DisplayMaterialMode.FLAT -> bubbleAlpha
    }
    val glassBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = GLASS_BUBBLE_BORDER_ALPHA)
    val translucentBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRANSLUCENT_BUBBLE_BORDER_ALPHA)
    val glassFillModifier = Modifier.drawWithCache {
        val gradientCenter = Offset(size.width / 2f, size.height / 2f)
        val glassFillBrush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to color.copy(alpha = 0.82f * bubbleAlpha),
                0.55f to color.copy(alpha = 0.80f * bubbleAlpha),
                0.82f to color.copy(alpha = 0.56f * bubbleAlpha),
                1f to color.copy(alpha = 0.40f * bubbleAlpha),
            ),
            center = gradientCenter,
            radius = gradientCenter.getDistance(),
        )
        onDrawBehind {
            drawRect(glassFillBrush)
        }
    }
    val glassHighlightModifier = Modifier.drawWithCache {
        val topHighlightDepth = 6.dp.toPx()
        val bottomHighlightDepth = 4.dp.toPx()
        val specularHighlightTop = 1.dp.toPx()
        val specularHighlightHeight = 1.dp.toPx()
        val glassTopHighlightBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.22f),
                0.5f to Color.White.copy(alpha = 0.08f),
                1f to Color.Transparent,
            ),
            endY = topHighlightDepth,
        )
        val glassBottomHighlightBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.05f),
                1f to Color.White.copy(alpha = 0.12f),
            ),
            startY = size.height - bottomHighlightDepth,
            endY = size.height,
        )
        val glassSpecularHighlightBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.10f to Color.White.copy(alpha = 0.14f),
                0.28f to Color.White.copy(alpha = 0.42f),
                0.52f to Color.White.copy(alpha = 0.24f),
                0.78f to Color.White.copy(alpha = 0.08f),
                1f to Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, 0f),
        )
        onDrawBehind {
            drawRect(
                brush = glassTopHighlightBrush,
                size = Size(size.width, minOf(size.height, topHighlightDepth)),
            )
            drawRect(
                brush = glassBottomHighlightBrush,
                topLeft = Offset(0f, maxOf(0f, size.height - bottomHighlightDepth)),
                size = Size(size.width, minOf(size.height, bottomHighlightDepth)),
            )
            drawRect(
                brush = glassSpecularHighlightBrush,
                topLeft = Offset(0f, specularHighlightTop),
                size = Size(
                    width = size.width,
                    height = minOf(specularHighlightHeight, size.height - specularHighlightTop),
                ),
            )
        }
    }
    // 昼夜状态：与应用实际 colorScheme 一致（SYSTEM→系统；LIGHT/DARK 手动覆盖）
    val isDarkTheme = LocalDarkMode.current
    // 实时模糊气泡专用：白色径向渐变高光遮罩（左上亮、向右下衰减；昼夜参数不同）
    val liveBubbleRadialHighlightModifier = Modifier.drawWithCache {
        val highlightCenter = if (isDarkTheme) {
            Offset(size.width * 0.20f, size.height * 0.15f)
        } else {
            Offset(size.width * 0.18f, size.height * 0.12f)
        }
        val highlightRadius = maxOf(size.width, size.height) * 0.95f
        val highlightBrush = Brush.radialGradient(
            colorStops = if (isDarkTheme) {
                arrayOf(
                    0f to Color.White.copy(alpha = 0.06f),
                    0.45f to Color.White.copy(alpha = 0.02f),
                    1f to Color.Transparent,
                )
            } else {
                arrayOf(
                    0f to Color.White.copy(alpha = 0.18f),
                    0.42f to Color.White.copy(alpha = 0.07f),
                    1f to Color.Transparent,
                )
            },
            center = highlightCenter,
            radius = highlightRadius,
        )
        onDrawBehind {
            drawRect(brush = highlightBrush)
        }
    }
    // 实时模糊气泡专用（仅夜间）：深色方向性识读遮罩，防止亮色背景导致气泡泛白、白字不可读
    val liveBubbleNightReadabilityModifier = Modifier.drawWithCache {
        val readabilityBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.06f),
                0.55f to Color.Black.copy(alpha = 0.09f),
                1f to Color.Black.copy(alpha = 0.12f),
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind {
            drawRect(brush = readabilityBrush)
        }
    }
    // 实时模糊气泡专用：沿真实圆角轮廓贴边的方向性硬高光（左上亮、向右下透明；昼夜强弱不同）
    val liveBubbleEdgeHighlightModifier = Modifier.drawWithCache {
        val strokeWidthPx = 1.dp.toPx()
        val halfStroke = strokeWidthPx / 2f
        val adjustedRadius = maxOf(0f, cornerRadius.toPx() - halfStroke)
        val edgeStartAlpha = if (isDarkTheme) 0.38f else 0.78f
        val edgeMidAlpha = if (isDarkTheme) 0.133f else 0.273f
        val edgeBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = edgeStartAlpha),
                0.45f to Color.White.copy(alpha = edgeMidAlpha),
                0.75f to Color.Transparent,
                1f to Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind {
            if (size.width > 0f && size.height > 0f) {
                drawRoundRect(
                    brush = edgeBrush,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = Size(
                        width = size.width - strokeWidthPx,
                        height = size.height - strokeWidthPx,
                    ),
                    cornerRadius = CornerRadius(adjustedRadius, adjustedRadius),
                    style = Stroke(width = strokeWidthPx),
                )
            }
        }
    }
    val shape = RoundedCornerShape(cornerRadius)
    val clickModifier = when {
        onLongClick != null || onDoubleClick != null ->
            Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick, onDoubleClick = onDoubleClick)
        onClick != null -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    val hasImage = imagePath.isNotBlank() && File(imagePath).exists()
    if (materialMode == DisplayMaterialMode.GLASS) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .clip(shape)
                .then(clickModifier)
                .border(1.dp, glassBorderColor, shape),
        ) {
            if (finalLiveBubbleBlurEnabled) {
                // 背景图片片段层：仅模糊此子层，文字等前景内容保持清晰。
                // matchParentSize 只覆盖父 Box 已确定尺寸，不参与父 Box 测量，
                // 因此气泡宽度仍由文字内容 + padding + 宽度上限决定。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveFragmentModifier),
                )
            }
            if (hasImage) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (finalLiveBubbleBlurEnabled && isDarkTheme) {
                // 夜间深色识读遮罩：位于模糊背景之上、glass fill 之下，压暗亮背景保证白字可读
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleNightReadabilityModifier),
                )
            }
            if (!hasImage || overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(glassFillModifier),
                )
            }
            if (finalLiveBubbleBlurEnabled) {
                // 白色径向渐变高光遮罩：位于 glass fill 上方、玻璃高光下方，受气泡圆角裁剪
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleRadialHighlightModifier),
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(glassHighlightModifier),
            )
            if (finalLiveBubbleBlurEnabled) {
                // 沿真实圆角贴边的方向性硬高光：位于渲染层外、content 之下；仅左上/顶部可见，向右下透明
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .then(liveBubbleEdgeHighlightModifier),
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else if (hasImage) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .clip(shape)
                .then(clickModifier)
                .then(
                    if (materialMode == DisplayMaterialMode.TRANSLUCENT) {
                        Modifier.border(1.dp, translucentBorderColor, shape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            AsyncImage(
                model = imagePath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            if (overlayEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(color.copy(alpha = effectiveAlpha)),
                )
            }
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    } else {
        Surface(
            modifier = Modifier.animateContentSize().then(clickModifier),
            shape = shape,
            color = color.copy(alpha = effectiveAlpha),
            border = if (materialMode == DisplayMaterialMode.TRANSLUCENT) {
                BorderStroke(1.dp, translucentBorderColor)
            } else {
                null
            },
        ) {
            Column(modifier = Modifier.padding(8.dp)) { content() }
        }
    }
}
