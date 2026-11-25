/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.plugin.data.PluginDataStore
import org.json.JSONObject
import java.util.UUID

private const val OVERLAY_TAG = "PluginOverlayController"

internal class PluginOverlayController(
    private val context: Context,
    private val dataStore: PluginDataStore,
    private val aiCall: PluginAiCall,
    private val scope: CoroutineScope,
) {
    private var overlayWebView: WebView? = null

    fun showOverlay(hostWebView: WebView, html: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            hostWebView.post {
                hostWebView.evaluateJavascript(
                    pluginBridgeResultJavascript(
                        null,
                        JSONObject().put("success", false).put("error", "Overlay permission not granted"),
                    ),
                    null,
                )
            }
            return
        }

        val activity = context as? Activity
        val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            hostWebView.post {
                hostWebView.evaluateJavascript(
                    pluginBridgeResultJavascript(
                        null,
                        JSONObject().put("success", false).put("error", "Cannot access WindowManager"),
                    ),
                    null,
                )
            }
            return
        }

        overlayWebView?.let { existingOverlay ->
            try {
                windowManager.removeView(existingOverlay)
            } catch (e: Exception) {
                Log.w(OVERLAY_TAG, "Error removing existing overlay", e)
            }
            existingOverlay.destroy()
        }

        val overlayDocumentUrl = OVERLAY_ORIGIN + UUID.randomUUID().toString() + "/index.html"
        var overlayToken = UUID.randomUUID().toString()
        val overlayWv = WebView(context).apply {
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        overlayWv.requestFocus()

        overlayWv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                overlayToken = UUID.randomUUID().toString()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isTrustedOverlayPage(url, overlayDocumentUrl) ||
                    !isTrustedOverlayPage(view?.url, overlayDocumentUrl)
                ) return
                view?.evaluateJavascript(
                    overlayBridgeJavascript.replace("__OVERLAY_PAGE_TOKEN__", overlayToken),
                    null,
                )
                view?.evaluateJavascript(
                    "document.addEventListener('click', function(e){ if(e.target.tagName==='INPUT'||e.target.tagName==='TEXTAREA'){ e.target.focus(); } });",
                    null,
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("bridge://")) {
                    val uri = Uri.parse(url)
                    if (view == null || !allowsOverlayBridge(
                            request.isForMainFrame,
                            view.url,
                            overlayDocumentUrl,
                            uri.getQueryParameter("pageToken"),
                            overlayToken,
                        )
                    ) return true
                    val acceptedToken = overlayToken
                    val method = uri.host ?: return true
                    val params = uri.queryParameterNames.associateWith {
                        uri.getQueryParameter(it) ?: ""
                    }
                    when (method) {
                        "hideOverlay" -> {
                            try {
                                windowManager.removeView(overlayWv)
                            } catch (e: Exception) {
                                Log.w(OVERLAY_TAG, "Error removing overlay", e)
                            }
                            overlayToken = ""
                            overlayWv.destroy()
                            overlayWebView = null
                        }

                        "getTimerState" -> {
                            val running = PomodoroTimerService.isRunning()
                            val remaining = PomodoroTimerService.getRemainingSeconds()
                            val cbId = params["callbackId"] ?: ""
                            overlayWv.post {
                                overlayWv.evaluateJavascript(
                                    pluginBridgeResultJavascript(
                                        cbId,
                                        JSONObject().put("running", running).put("remaining", remaining),
                                    ),
                                    null,
                                )
                            }
                        }

                        "getData" -> {
                            val key = params["key"] ?: ""
                            val value = dataStore.getData(key)
                            val result = if (value != null) {
                                "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                            } else "null"
                            val cbId = params["callbackId"] ?: ""
                            overlayWv.post {
                                overlayWv.evaluateJavascript(
                                    pluginBridgeJsonJavascript(cbId, result),
                                    null,
                                )
                            }
                        }

                        "setData" -> {
                            val key = params["key"] ?: ""
                            val value = params["value"] ?: ""
                            dataStore.setData(key, value)
                            val cbId = params["callbackId"] ?: ""
                            overlayWv.post {
                                overlayWv.evaluateJavascript(
                                    pluginBridgeResultJavascript(cbId, true),
                                    null,
                                )
                            }
                        }

                        "callAI" -> {
                            val cbId = params["callbackId"] ?: ""
                            val prompt = params["prompt"] ?: ""
                            val contextJson = params["context"] ?: "{}"
                            scope.launch(Dispatchers.IO) {
                                val aiResult = aiCall.call(prompt) {
                                    "你是一个番茄钟陪伴助手。用户正在使用番茄钟专注。请用简短温暖的话回应，鼓励用户保持专注。当前上下文：$contextJson"
                                }
                                overlayWv.post {
                                    if (overlayToken == acceptedToken &&
                                        isTrustedOverlayPage(overlayWv.url, overlayDocumentUrl)
                                    ) {
                                        overlayWv.evaluateJavascript(
                                            pluginAiResultJavascript(cbId, aiResult),
                                            null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (method != "hideOverlay") {
                        view.evaluateJavascript(
                            "window.__overlayBridgeAccepted && window.__overlayBridgeAccepted();",
                            null,
                        )
                    }
                    return true
                }
                if (request.isForMainFrame && isTrustedOverlayPage(url, overlayDocumentUrl)) return false
                if (request.isForMainFrame && (request.url.scheme == "https" || request.url.scheme == "http")) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                // No remote or child-frame navigation may acquire the overlay origin/bridge.
                return true
            }
        }

        overlayWv.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        overlayWv.loadDataWithBaseURL(overlayDocumentUrl, html, "text/html", "UTF-8", null)
        windowManager.addView(overlayWv, params)
        overlayWebView = overlayWv
    }

    fun notifyTimerEnded() {
        overlayWebView?.post {
            overlayWebView?.evaluateJavascript(
                "if(typeof window.onTimerEnd === 'function') { window.onTimerEnd(); }",
                null,
            )
        }
    }

    fun hideOverlay() {
        val activity = context as? Activity
        val windowManager = activity?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        overlayWebView?.let { existingOverlay ->
            try {
                windowManager?.removeView(existingOverlay)
            } catch (e: Exception) {
                Log.w(OVERLAY_TAG, "Error removing overlay", e)
            }
            existingOverlay.destroy()
            overlayWebView = null
        }
    }

    fun dispose() = hideOverlay()
}
