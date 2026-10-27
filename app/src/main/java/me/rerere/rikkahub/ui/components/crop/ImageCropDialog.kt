/*
 * Adapted from RikkaHub (https://github.com/rikkahub/rikkahub), by RE.
 * GNU AGPL v3; see LICENSE. Native crop port for OrangeChat.
 */

package me.rerere.rikkahub.ui.components.crop

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import kotlinx.coroutines.CancellationException

/**
 * 全屏的图片裁剪：拖动、缩放图片，拖框的边角调整范围，可以旋转 90°。
 *
 * @param image 要裁剪的图片
 * @param onConfirm 裁好的图片，长边不超过 [maxResultSize]，由调用方负责存下来
 * @param aspectRatio 固定的宽高比（宽 / 高），比如头像传 1f；为空时可以自由裁剪，也可以在底部选比例
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageCropDialog(
    image: Uri,
    onDismiss: () -> Unit,
    onConfirm: suspend (Bitmap) -> Unit,
    aspectRatio: Float? = null,
    maxResultSize: Int = 4096,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var state by remember(image) { mutableStateOf<ImageCropState?>(null) }
    var cropping by remember { mutableStateOf(false) }

    val loadFailed = "无法读取图片，请重新选择"
    val cropFailed = "图片裁剪失败，请重试"
    LaunchedEffect(image, aspectRatio, maxResultSize) {
        val loaded = loadCropImage(context, image, maxResultSize)
        if (loaded != null) {
            state = ImageCropState(loaded.bitmap, loaded.preview, aspectRatio)
        } else {
            // 应用内的提示显示在对话框这个窗口的下面，看不到，这里用系统的
            Toast.makeText(context, loadFailed, Toast.LENGTH_SHORT).show()
            currentOnDismiss()
        }
    }

    fun confirm(state: ImageCropState) {
        if (cropping || !state.isReady) return
        cropping = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { state.crop(maxResultSize) }
                onConfirm(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, cropFailed, Toast.LENGTH_SHORT).show()
            } finally {
                cropping = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!cropping) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 对话框有自己的窗口，系统栏图标的深浅要另外跟着底色设置
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val containerColor = MaterialTheme.colorScheme.surfaceContainer
        val lightContainer = containerColor.luminance() > 0.5f
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightContainer
                    isAppearanceLightNavigationBars = lightContainer
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                val current = state
                CropTopBar(
                    state = current,
                    cropping = cropping,
                    onClose = onDismiss,
                    onConfirm = { current?.let(::confirm) },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (current != null) {
                        ImageCropper(
                            state = current,
                            enabled = !cropping,
                            refitSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                if (current != null && current.lockedRatio == null) {
                    CropAspectBar(state = current, enabled = !cropping)
                }
            }
        }
    }
}

// 关闭、旋转、重置和确认
@Composable
private fun CropTopBar(
    state: ImageCropState?,
    cropping: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = !cropping) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(android.R.string.cancel))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { state?.rotate() }, enabled = state != null && !cropping) {
            Icon(HugeIcons.Refresh03, contentDescription = "顺时针旋转 90°")
        }
        IconButton(onClick = { state?.reset() }, enabled = state?.modified == true && !cropping) {
            Icon(HugeIcons.Refresh01, contentDescription = "重置裁剪")
        }
        Spacer(Modifier.weight(1f))
        FilledIconButton(onClick = onConfirm, enabled = state?.isReady == true && !cropping) {
            Icon(HugeIcons.Tick01, contentDescription = stringResource(android.R.string.ok))
        }
    }
}

// 没有固定比例时，底部可以切换裁剪框的比例
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CropAspectBar(state: ImageCropState, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CropAspect.Options.forEach { aspect ->
            FilterChip(
                selected = state.aspect == aspect,
                enabled = enabled,
                onClick = { state.selectAspect(aspect) },
                label = {
                Text(
                    text = when (aspect) {
                        CropAspect.Free -> "自由"
                        CropAspect.Original -> "原图"
                        is CropAspect.Ratio -> "${aspect.width}:${aspect.height}"
                    },
                    maxLines = 1,
                    softWrap = false,
                )
                },
            )
        }
    }
}
