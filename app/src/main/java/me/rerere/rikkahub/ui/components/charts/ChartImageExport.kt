package me.rerere.rikkahub.ui.components.charts

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Called by the export button on Main before capture or file work begins. */
internal fun ensureChartExportPermission(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED) return true
    ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4003)
    Toast.makeText(activity, "保存图片需要存储权限，请授权后再次点击导出。", Toast.LENGTH_LONG).show()
    return false
}

/** Reuses the encoded PNG; no bitmap encoding or gallery I/O occurs on Main. */
internal suspend fun saveChartPngToGallery(context: Context, encoded: File, filename: String) =
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishChartPng(context.contentResolver, encoded, filename)
        } else {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建图片目录" }
            val target = File(directory, filename)
            check(target.createNewFile()) { "图片文件已存在" }
            try {
                encoded.inputStream().use { input ->
                    target.outputStream().use { output -> copyChartPng(input, output) }
                }
                currentCoroutineContext().ensureActive()
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/png"), null)
            } catch (error: Throwable) {
                if (!target.delete() && target.exists()) {
                    error.addSuppressed(IllegalStateException("未能清理未完成的图片"))
                }
                throw error
            }
        }
    }

/** MediaStore keeps the row private until all PNG bytes have been written successfully. */
internal suspend fun publishChartPng(resolver: ContentResolver, encoded: File, filename: String) {
    currentCoroutineContext().ensureActive()
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
        "无法创建相册图片"
    }
    try {
        currentCoroutineContext().ensureActive()
        encoded.inputStream().use { input ->
            checkNotNull(resolver.openOutputStream(uri)) { "无法写入相册图片" }.use { output ->
                copyChartPng(input, output)
            }
        }
        currentCoroutineContext().ensureActive()
        val published = resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
        check(published == 1) { "相册图片保存未完成" }
    } catch (error: Throwable) {
        try {
            check(resolver.delete(uri, null, null) > 0) { "未能清理未完成的相册图片" }
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
}

private suspend fun copyChartPng(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val size = input.read(buffer)
        if (size < 0) break
        output.write(buffer, 0, size)
    }
    currentCoroutineContext().ensureActive()
}
