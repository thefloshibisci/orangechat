package me.rerere.rikkahub.data.ai.transformers

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.service.RikkaAccessibilityService
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException

private data class ScreenWindow(val id: Int, val packageName: String)
private val ocrCompletionExecutor = Executor { it.run() }

internal suspend fun readCurrentScreenText(): ExtraInfoCollectionResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw ExtraInfoUnavailable(ExtraInfoIssue.OCR_UNSUPPORTED)
    val service = RikkaAccessibilityService.instance ?: throw ExtraInfoUnavailable(ExtraInfoIssue.ACCESSIBILITY)
    // Capture the window identity before OCR so a late fallback cannot read a different app/window.
    val window = withContext(Dispatchers.Main) {
        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
        root?.let {
            try { ScreenWindow(root.windowId, root.packageName?.toString().orEmpty()) }
            finally { @Suppress("DEPRECATION") root.recycle() }
        }
    }
    var screenshot = service.captureScreenshot(Display.DEFAULT_DISPLAY)
    if (screenshot is RikkaAccessibilityService.ScreenshotOutcome.Failure && screenshot.reason == "rate_limited") {
        delay(350)
        screenshot = service.captureScreenshot(Display.DEFAULT_DISPLAY)
    }
    return when (val capture = screenshot) {
        is RikkaAccessibilityService.ScreenshotOutcome.Failure -> throw ExtraInfoUnavailable(when (capture.reason) {
            "no_access" -> ExtraInfoIssue.ACCESSIBILITY
            "secure_window" -> ExtraInfoIssue.SCREEN_PROTECTED
            "rate_limited" -> ExtraInfoIssue.SCREEN_BUSY
            else -> ExtraInfoIssue.SCREEN_FAILED
        })
        is RikkaAccessibilityService.ScreenshotOutcome.Success -> {
            val screenBounds = Rect(0, 0, capture.bitmap.width, capture.bitmap.height)
            collectScreenTextWithFallback(
                recognize = { recognizeScreenBitmap(capture.bitmap) },
                visibleText = { withContext(Dispatchers.Main) {
                    if (window == null || RikkaAccessibilityService.instance !== service) return@withContext ""
                    val root = service.rootInActiveWindow ?: return@withContext ""
                    try {
                        if (root.windowId != window.id || root.packageName?.toString() != window.packageName) ""
                        else readVisibleWindowText(root, screenBounds)
                    } finally { @Suppress("DEPRECATION") root.recycle() }
                } },
            )
        }
    }
}

/** Takes ownership immediately; ML Kit retains the bitmap until its task completes, even on cancellation. */
internal suspend fun recognizeScreenBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
    if (!continuation.isActive) {
        bitmap.recycle()
        return@suspendCancellableCoroutine
    }
    var recognizer: TextRecognizer? = null
    fun release() {
        try { recognizer?.close() } catch (_: Exception) { /* Cleanup must not hide the result. */ }
        finally { bitmap.recycle() }
    }
    try {
        val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer = client
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnCompleteListener(ocrCompletionExecutor) { task ->
                val result = if (task.isSuccessful) Result.success(task.result.text.trim())
                else Result.failure(ocrFailure(task.exception))
                release()
                if (continuation.isActive) continuation.resumeWith(result)
            }
    } catch (error: Exception) {
        val issue = if (recognizer == null) ExtraInfoUnavailable(ExtraInfoIssue.OCR_INITIALIZATION)
            else ocrFailure(error)
        release()
        if (continuation.isActive) continuation.resumeWithException(issue)
    } catch (_: LinkageError) {
        release()
        if (continuation.isActive) continuation.resumeWithException(ExtraInfoUnavailable(ExtraInfoIssue.OCR_INITIALIZATION))
    }
}

private fun ocrFailure(error: Exception?): ExtraInfoUnavailable = ExtraInfoUnavailable(when {
    error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE -> ExtraInfoIssue.OCR_UNAVAILABLE
    error is MlKitException && error.errorCode == MlKitException.INVALID_ARGUMENT -> ExtraInfoIssue.OCR_IMAGE
    error is IllegalArgumentException -> ExtraInfoIssue.OCR_IMAGE
    else -> ExtraInfoIssue.OCR_RECOGNITION
}, diagnosticCode = (error as? MlKitException)?.errorCode)

/** Bounded visible text only: no password subtrees, hidden nodes or off-screen content. */
private fun readVisibleWindowText(root: AccessibilityNodeInfo, screenBounds: Rect): String {
    val lines = linkedSetOf<String>()
    var length = 0
    var visited = 0
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (visited >= 500 || depth > 40 || length >= 4_000) return
        visited++
        if (node.isPassword || !node.isVisibleToUser) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isVisibleToUser && Rect.intersects(screenBounds, bounds)) {
            val text = (node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()).orEmpty().trim().take(4_000 - length)
            if (text.isNotEmpty() && lines.add(text)) length += text.length + 1
        }
        for (index in 0 until node.childCount) {
            if (visited >= 500 || length >= 4_000) break
            val child = node.getChild(index) ?: continue
            try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
        }
    }
    visit(root, 0)
    return lines.joinToString("\n").take(4_000)
}
