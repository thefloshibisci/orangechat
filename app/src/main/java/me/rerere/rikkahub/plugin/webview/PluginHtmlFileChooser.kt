package me.rerere.rikkahub.plugin.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

internal data class PluginHtmlFileRequest(
    val acceptTypes: Array<String>,
    val mode: Int?,
)

/** Honor the HTML input's multiple attribute; return all selected ClipData entries. */
internal class PluginHtmlFileChooser : ActivityResultContract<PluginHtmlFileRequest, Array<Uri>?>() {
    override fun createIntent(context: Context, input: PluginHtmlFileRequest): Intent {
        val types = input.acceptTypes.flatMap { it.split(',') }.map { it.trim() }
            .filter { it.isNotEmpty() }.distinct().toTypedArray()
            .ifEmpty { arrayOf("*/*") }
        val multiple = input.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        // Use the system gallery for image inputs, with AndroidX's older-device fallback.
        if (types.size == 1 && types[0].startsWith("image/")) {
            val mediaType = if (types[0] == "image/*") {
                ActivityResultContracts.PickVisualMedia.ImageOnly
            } else {
                ActivityResultContracts.PickVisualMedia.SingleMimeType(types[0])
            }
            val request = PickVisualMediaRequest(mediaType)
            return if (multiple) {
                ActivityResultContracts.PickMultipleVisualMedia().createIntent(context, request)
            } else {
                ActivityResultContracts.PickVisualMedia().createIntent(context, request)
            }
        }
        return ActivityResultContracts.OpenDocument().createIntent(context, types).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Array<Uri>? =
        ActivityResultContracts.OpenMultipleDocuments().parseResult(resultCode, intent)
            .takeIf { it.isNotEmpty() }?.toTypedArray()
}
