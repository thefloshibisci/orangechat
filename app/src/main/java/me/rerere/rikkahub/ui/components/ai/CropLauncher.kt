/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.components.crop.ImageCropDialog
import java.io.File

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit, onCleanup: (() -> Unit)? = null
): (Uri) -> Unit {
    val context = LocalContext.current
    val currentOnImage by rememberUpdatedState(onCroppedImageReady)
    val currentCleanup by rememberUpdatedState(onCleanup)
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    fun finish() {
        source = null
        currentCleanup?.invoke()
    }
    source?.let { value ->
        ImageCropDialog(
            image = value.toUri(),
            onDismiss = ::finish,
            onConfirm = { bitmap ->
                val encoding = cropEncoding(bitmap.hasAlpha())
                val output = File.createTempFile("crop_output_", encoding.extension, context.appTempFolder)
                try {
                    withContext(Dispatchers.IO) {
                        output.outputStream().use {
                            check(bitmap.compress(encoding.format, encoding.quality, it)) { "无法保存裁剪图片" }
                        }
                    }
                    // Await the import, including asynchronous consumers, before deleting the temporary image.
                    currentOnImage(output.toUri())
                    finish()
                } finally {
                    output.delete()
                }
            },
        )
    }
    return { uri -> source = uri.toString() }
}

internal data class CropEncoding(val extension: String, val format: Bitmap.CompressFormat, val quality: Int)
internal fun cropEncoding(hasAlpha: Boolean) = if (hasAlpha) {
    CropEncoding(".png", Bitmap.CompressFormat.PNG, 100)
} else {
    CropEncoding(".jpg", Bitmap.CompressFormat.JPEG, 95)
}
