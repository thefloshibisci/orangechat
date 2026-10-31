package me.rerere.rikkahub.ui.pages.setting.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import me.rerere.rikkahub.data.datastore.BubbleStickerCorner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.BubbleFrameSetting
import me.rerere.rikkahub.ui.components.message.BubbleFrame
import me.rerere.rikkahub.ui.components.message.BubbleSurface
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CommitSlider
import me.rerere.rikkahub.ui.components.ui.toComposeColor
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import java.io.File
import java.util.UUID

@Composable
internal fun BubbleFrameSettingsCard(
    isUser: Boolean,
    setting: BubbleFrameSetting,
    onChange: (BubbleFrameSetting) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(setting) { mutableStateOf(setting.normalized()) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            try {
                val path = withContext(Dispatchers.IO) {
                    // Reuse the backed-up upload directory; never persist a temporary picker URI.
                    val directory = File(context.filesDir, "upload").apply { mkdirs() }
                    val file = File(directory, "bubble_frame_${UUID.randomUUID()}.png")
                    try {
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        require(bounds.outWidth >= 4 && bounds.outHeight >= 4)
                        file.absolutePath
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }
                draft = BubbleFrameSetting(path = path)
                onChange(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "图片读取失败，请重新选择"
            } finally {
                importing = false
            }
        }
    }
    CardGroup(modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(if (isUser) "用户气泡贴纸" else "AI 气泡贴纸") }) {
        item(
            headlineContent = { Text("装饰图片") },
            supportingContent = { Text(error ?: "选择透明图片，贴在气泡边角外侧") },
            trailingContent = {
                Row {
                    if (draft.path.isNotBlank()) TextButton(onClick = {
                        draft = BubbleFrameSetting()
                        onChange(draft)
                    }, enabled = !importing) { Text("清除") }
                    TextButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !importing) {
                        Text(if (importing) "读取中…" else "选择")
                    }
                }
            },
        )
        if (draft.path.isNotBlank()) {
            item(headlineContent = { Text("预览") }, supportingContent = {
                BubbleFramePreview(isUser, draft)
            })
            item(headlineContent = { Text("贴纸位置") }, supportingContent = {
                Column {
                    listOf(listOf(BubbleStickerCorner.TOP_LEFT to "左上", BubbleStickerCorner.TOP_RIGHT to "右上"),
                        listOf(BubbleStickerCorner.BOTTOM_LEFT to "左下", BubbleStickerCorner.BOTTOM_RIGHT to "右下")).forEach { corners ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            corners.forEach { (corner, label) ->
                                val current = if (draft.corner == BubbleStickerCorner.AUTO) {
                                    if (isUser) BubbleStickerCorner.TOP_LEFT else BubbleStickerCorner.BOTTOM_RIGHT
                                } else draft.corner
                                FilterChip(selected = current == corner, onClick = {
                                    draft = draft.copy(corner = corner); onChange(draft)
                                }, label = { Text(label) })
                            }
                        }
                    }
                }
            })
            item(headlineContent = { Text("水平位置") }, supportingContent = {
                FrameSlider(draft.offsetX, -24f..24f,
                    { draft = draft.copy(offsetX = it) },
                    { onChange(draft.copy(offsetX = it)) }) { "${it.toInt()}" }
            })
            item(headlineContent = { Text("垂直位置") }, supportingContent = {
                FrameSlider(draft.offsetY, -24f..24f,
                    { draft = draft.copy(offsetY = it) },
                    { onChange(draft.copy(offsetY = it)) }) { "${it.toInt()}" }
            })
            item(headlineContent = { Text("大小") }, supportingContent = {
                FrameSlider(draft.scale, 0.8f..1.5f,
                    { draft = draft.copy(scale = it) },
                    { onChange(draft.copy(scale = it)) }) { "${(it * 100).toInt()}%" }
            })
            item(headlineContent = { Text("恢复位置与大小") }, trailingContent = {
                TextButton(onClick = {
                    draft = BubbleFrameSetting(path = draft.path)
                    onChange(draft)
                }) { Text("重置") }
            })
        }
    }
}

@Composable
private fun FrameSlider(value: Float, range: ClosedFloatingPointRange<Float>,
    onPreview: (Float) -> Unit, onCommit: (Float) -> Unit, label: (Float) -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommitSlider(value, onCommit, Modifier.weight(1f), valueRange = range, onValuePreview = onPreview) { draft, slider ->
            slider()
            Text(label(draft))
        }
    }
}

@Composable
internal fun BubbleFramePreview(isUser: Boolean, frame: BubbleFrameSetting) {
    val display = LocalDisplaySettings.current
    val color = if (isUser) display.userBubbleColor?.toComposeColor() ?: MaterialTheme.colorScheme.secondaryContainer
        else display.assistantBubbleColor?.toComposeColor() ?: MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        listOf("一小句", "长一点的消息也能用，\n贴纸会跟随气泡边角。 ").forEach { text ->
            BubbleFrame(frame, isUser = isUser) {
                BubbleSurface(
                    imagePath = if (isUser) display.userBubbleImagePath else display.assistantBubbleImagePath,
                    cornerRadius = display.bubbleCornerRadius.dp,
                    color = color,
                    overlayEnabled = display.bubbleImageOverlayEnabled,
                    bubbleAlpha = 1f - display.chatBubbleTransparency / 100f,
                ) { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
