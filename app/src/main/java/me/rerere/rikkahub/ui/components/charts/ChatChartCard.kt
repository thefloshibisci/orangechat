package me.rerere.rikkahub.ui.components.charts

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.LocalMaterialMode
import me.rerere.rikkahub.utils.getActivity
import java.io.File
import kotlin.uuid.Uuid

/** A message chart remains visible even when its tool trace is collapsed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatChartCard(spec: ChartSpec, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val materialMode = LocalMaterialMode.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    fun export(mode: ChartDisplayMode) {
        if (exporting) return
        val activity = context.getActivity() ?: return
        if (!ensureChartExportPermission(activity)) return
        exporting = true
        scope.launch {
            var bitmap: Bitmap? = null
            var encodedFile: File? = null
            var shared = false
            var saved = false
            try {
                bitmap = BitmapComposer(scope).composableToBitmap(
                    activity = activity,
                    width = 540.dp,
                    screenDensity = density,
                ) {
                    CompositionLocalProvider(
                        LocalSettings provides settings,
                        LocalDisplaySettings provides settings.displaySetting,
                        LocalMaterialMode provides materialMode,
                    ) {
                        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
                            ExportedChartCard(spec, mode)
                        }
                    }
                }
                val captured = checkNotNull(bitmap)
                val filename = "OrangeChat-${if (mode == ChartDisplayMode.Table) "table" else "chart"}-${Uuid.random()}.png"
                val file = File(context.appTempFolder, filename)
                encodedFile = file
                withContext(Dispatchers.IO) {
                    file.outputStream().use { stream ->
                        check(captured.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "图片编码失败" }
                    }
                    currentCoroutineContext().ensureActive()
                }
                saveChartPngToGallery(context, file, filename)
                saved = true
                Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享图片"))
                shared = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (saved) "图片已保存，但无法打开分享：" else "图片导出失败："
                Toast.makeText(context, message + error.message.orEmpty(), Toast.LENGTH_LONG).show()
            } finally {
                bitmap?.recycle()
                if (!shared) withContext(NonCancellable + Dispatchers.IO) { encodedFile?.delete() }
                exporting = false
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChartCard(spec)
        if (!LocalExportContext.current) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { export(ChartDisplayMode.Chart) }, enabled = !exporting) { Text("导出图表") }
                TextButton(onClick = { export(ChartDisplayMode.Table) }, enabled = !exporting) { Text("导出表格截图") }
            }
            Text(
                "表格图片仅包含首屏，完整数据可在表格中滚动查看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared by standalone PNG export and UI verification, without export controls. */
@Composable
internal fun ExportedChartCard(spec: ChartSpec, mode: ChartDisplayMode) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartCard(spec, initialMode = mode)
        if (mode == ChartDisplayMode.Table) {
            Text("数据表首屏截图", style = MaterialTheme.typography.labelSmall)
        }
    }
}
