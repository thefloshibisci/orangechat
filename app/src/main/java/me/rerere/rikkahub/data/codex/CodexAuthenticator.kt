package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Only retries a rejected HTTP handshake, never a partially consumed generation. */
internal class CodexAuthenticator(
    private val repository: CodexAccountRepository,
    private val accountId: String,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (!isCodexOfficialTarget(response.request.url) || response.priorResponse != null) return null
        val rejected = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        val account = try {
            runBlocking { repository.refreshAfterUnauthorized(accountId, rejected) }
        } catch (_: Exception) { return null }
        if (!repository.isSignedIn(account.id)) return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-Id", account.chatgptAccountId)
            .build()
    }
}
