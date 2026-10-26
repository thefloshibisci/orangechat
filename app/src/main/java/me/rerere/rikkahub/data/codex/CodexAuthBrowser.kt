package me.rerere.rikkahub.data.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Uses the same browser-backed tab style as ordinary links, without logging OAuth query parameters. */
internal fun codexAuthBrowserIntent(url: String): Intent {
    val uri = Uri.parse(url)
    require(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.port == -1 &&
        uri.userInfo == null && uri.fragment == null && uri.path in setOf("/oauth/authorize", "/codex/device")) {
        "无效的 ChatGPT 授权页面"
    }
    return CustomTabsIntent.Builder().setShowTitle(true).build().intent.apply {
        data = uri
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun Context.openCodexAuthBrowser(url: String) {
    startActivity(codexAuthBrowserIntent(url))
}
