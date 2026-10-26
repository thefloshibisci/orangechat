package me.rerere.rikkahub.data.codex

import android.app.Application
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CodexAuthBrowserTest {
    @Test fun browserTabKeepsExactPkceStateAndLoopbackCallbackWithoutLoggingThem() {
        assertEquals("http://127.0.0.1:1455/auth/callback", CodexOAuthManager.BROWSER_REDIRECT_URI)
        val url = "https://auth.openai.com/oauth/authorize?response_type=code&state=synthetic-state&code_challenge=synthetic-challenge&code_challenge_method=S256&redirect_uri=" +
            android.net.Uri.encode(CodexOAuthManager.BROWSER_REDIRECT_URI)
        val app = RuntimeEnvironment.getApplication()
        app.openCodexAuthBrowser(url)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(url, launched.dataString)
        assertEquals(CodexOAuthManager.BROWSER_REDIRECT_URI,
            launched.data!!.getQueryParameter("redirect_uri"))
        assertTrue(launched.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(CustomTabsIntent.SHOW_PAGE_TITLE,
            launched.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, -1))
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("synthetic-state") || it.msg.contains("synthetic-challenge") })
    }

    @Test fun deviceCodePageUsesTheSameBrowserTabBoundary() {
        val intent = codexAuthBrowserIntent("https://auth.openai.com/codex/device")
        assertTrue(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals("https://auth.openai.com/codex/device", intent.dataString)
    }

    @Test fun unrelatedOrNonTlsAuthorizationPagesCannotReachTheLauncher() {
        listOf("http://auth.openai.com/oauth/authorize", "https://auth.openai.com.evil.test/oauth/authorize",
            "https://auth.openai.com:8443/oauth/authorize", "https://user@auth.openai.com/oauth/authorize",
            "https://auth.openai.com/other", "https://auth.openai.com/oauth/authorize#fragment").forEach {
            assertTrue(it, runCatching { codexAuthBrowserIntent(it) }.isFailure)
        }
    }
}
