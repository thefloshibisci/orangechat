package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.pages.setting.components.convertTo
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CodexSecurityTest {
    @Test fun `only fixed official TLS destinations are allowed`() {
        listOf("https://auth.openai.com/oauth/token", "https://chatgpt.com/backend-api/codex/responses",
            "https://chatgpt.com/backend-api/codex/models?client_version=1", "https://chatgpt.com/backend-api/wham/usage")
            .forEach { assertTrue(isCodexOfficialTarget(it.toHttpUrl())) }
        listOf("http://chatgpt.com/backend-api/codex/responses", "https://chatgpt.com.evil.test/backend-api/codex/responses",
            "https://api.openai.com/v1/responses", "https://chatgpt.com:8443/backend-api/codex/responses",
            "https://chatgpt.com/redirect", "https://user:password@chatgpt.com/backend-api/codex/responses")
            .forEach { assertFalse(isCodexOfficialTarget(it.toHttpUrl())) }
        val client = codexHttpClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertTrue(client.networkInterceptors.isEmpty())
    }

    @Test fun `switching provider types cannot carry credentials or Codex endpoint`() {
        val codex = ProviderSetting.OpenAI(apiKey = "api-key", baseUrl = "https://evil.test/v1")
            .convertTo(ProviderSetting.Codex::class) as ProviderSetting.Codex
        val openai = codex.convertTo(ProviderSetting.OpenAI::class) as ProviderSetting.OpenAI
        assertEquals("", openai.apiKey)
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
    }

    @Test fun `safe boundary strips decoder input and nested exceptions`() = runBlocking {
        val failure = runCatching { codexSafe<Unit> { throw IllegalArgumentException("refresh-token-secret") } }.exceptionOrNull()!!
        assertFalse(failure.toString().contains("refresh-token-secret"))
        generateSequence(failure) { it.cause }.forEach { assertFalse(it.toString().contains("refresh-token-secret")) }
    }

    @Test fun `account string representation redacts secrets`() {
        val account = CodexAccount("id", name = "name", chatgptAccountId = "account", accessToken = "secret-access", refreshToken = "secret-refresh", expiresAt = 0)
        assertFalse(account.toString().contains("secret-"))
        assertFalse(CodexAccountState(listOf(account)).toString().contains("secret-"))
    }
}
