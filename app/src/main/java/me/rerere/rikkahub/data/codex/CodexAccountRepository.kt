// Adapted from ExTV/rikkahub-agent, 51cb7e1 (AGPL-3.0).
package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class CodexAccountRepository internal constructor(
    private val store: CodexAccountStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val _storageUnavailable = MutableStateFlow(false)
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()
    private var state = try { readStoredState() } catch (_: Exception) {
        _storageUnavailable.value = true
        CodexAccountState()
    }
    private fun readStoredState() = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != CodexTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = CodexTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<CodexAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokenJson: String): CodexAccount = codexSafe {
        mutex.withLock {
            ensureLoadedLocked()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val token = json.parseToJsonElement(tokenJson).jsonObject
            val identity = parseCodexIdentity(
                idToken = token["id_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("Missing ID token"),
                json = json,
            )
            val now = System.currentTimeMillis()
            val existing = state.accounts.firstOrNull {
                it.chatgptAccountId == identity.accountId &&
                    if (it.userId.isNotBlank() && identity.userId.isNotBlank()) {
                        it.userId == identity.userId
                    } else {
                        it.email == identity.email
                    }
            }
            val account = CodexAccount(
                id = existing?.id ?: "${identity.userId.ifBlank { identity.email }}:${identity.accountId}",
                userId = identity.userId,
                name = identity.name,
                email = identity.email,
                chatgptAccountId = identity.accountId,
                accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("Missing access token"),
                refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?: existing?.refreshToken
                    ?: error("Missing refresh token"),
                expiresAt = now + (
                    token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                    ) * 1000,
                enabled = existing?.enabled ?: true,
                tokenStatus = CodexTokenStatus.AVAILABLE,
                usage = existing?.usage,
            )
            updateState(
                state.copy(
                    accounts = state.accounts.filterNot { it.id == account.id } + account
                )
            )
            account
        }
    }

    suspend fun acquireAccount(affinityKey: String? = null): CodexAccount = codexSafe {
        mutex.withLock {
            ensureLoadedLocked()
            if (state.accounts.isEmpty()) error("No Codex account is signed in")
            val candidates = if (!affinityKey.isNullOrBlank()) {
                // Keep a conversation on one account; rotate only when it becomes unavailable.
                state.accounts.indices.sortedBy { index ->
                    java.util.UUID.nameUUIDFromBytes("$affinityKey:${state.accounts[index].id}".toByteArray()).toString()
                }
            } else state.accounts.indices.map { (it + state.nextAccountIndex).mod(state.accounts.size) }
            for (index in candidates) {
                val candidate = state.accounts[index]
                if (!candidate.isAvailable()) continue
                val fresh = try { ensureFreshLocked(candidate) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
                // Rotation position is ephemeral; do not rewrite encrypted credentials on every request.
                state = state.copy(nextAccountIndex = (index + 1) % state.accounts.size)
                return@withLock fresh
            }
            error("No available Codex account")
        }
    }

    /** A stale in-flight 401 must never invalidate a freshly rotated token or a new login. */
    internal suspend fun refreshAfterUnauthorized(accountId: String, rejectedToken: String): CodexAccount = codexSafe {
        mutex.withLock {
            ensureLoadedLocked()
            val current = state.accounts.firstOrNull { it.id == accountId }
                ?: error("Codex account signed out")
            check(current.enabled) { "Codex account disabled" }
            if (current.accessToken != rejectedToken) return@withLock ensureFreshLocked(current)
            ensureFreshLocked(current, force = true)
        }
    }

    suspend fun updateUsage(accountId: String, usage: CodexUsageSnapshot) = codexSafe {
        mutex.withLock {
            replaceAccount(accountId) { it.copy(usage = usage) }
        }
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = codexSafe {
        mutex.withLock {
            replaceAccount(accountId) { it.copy(enabled = enabled) }
        }
    }

    suspend fun delete(accountId: String) = codexSafe {
        mutex.withLock {
            ensureLoadedLocked()
            val removedAccount = state.accounts.firstOrNull { it.id == accountId }
            updateState(
                state.copy(
                    accounts = state.accounts.filterNot { it.id == accountId },
                    nextAccountIndex = 0,
                )
            )
            client.dispatcher.queuedCalls().plus(client.dispatcher.runningCalls()).forEach { call ->
                if (call.request().header("ChatGPT-Account-Id") == removedAccount?.chatgptAccountId) call.cancel()
            }
        }
    }

    suspend fun refreshAccount(accountId: String): CodexAccount = codexSafe {
        mutex.withLock {
            ensureLoadedLocked()
            val account = state.accounts.firstOrNull { it.id == accountId }
                ?: error("Codex account not found")
            val fresh = ensureFreshLocked(account, force = account.tokenStatus == CodexTokenStatus.INVALID)
            fetchUsageLocked(fresh)
        }
    }

    suspend fun refreshAll() {
        reloadCredentials()
        accounts.value.forEach { account ->
            try { refreshAccount(account.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    suspend fun reloadCredentials() = codexSafe { mutex.withLock { ensureLoadedLocked() } }

    private fun ensureLoadedLocked() {
        if (!_storageUnavailable.value) return
        val recovered = readStoredState()
        state = recovered
        _accounts.value = recovered.accounts
        _storageUnavailable.value = false
    }

    private suspend fun ensureFreshLocked(
        account: CodexAccount,
        force: Boolean = false,
    ): CodexAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        // Refresh tokens can rotate server-side before cancellation arrives. Finish consuming
        // and persisting the replacement under the mutex, with a bounded timeout.
        return withContext(NonCancellable + Dispatchers.IO) { withTimeout(60_000) {
        val response = withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CodexOAuthManager.CLIENT_ID)
                .add("refresh_token", account.refreshToken)
                .add("scope", CodexOAuthManager.REFRESH_SCOPES)
                .build()
            client.newCall(
                Request.Builder()
                    .url(CodexOAuthManager.TOKEN_URL)
                    .post(body)
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isCodexRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = CodexTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = CodexTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        updated
        } }
    }

    private suspend fun fetchUsageLocked(account: CodexAccount, retryUnauthorized: Boolean = true): CodexAccount {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$CODEX_BASE_URL/wham/usage")
                    .codexHeaders(account)
                    .get()
                    .build()
            ).await()
        }
        if (!response.isSuccessful) {
            response.close()
            if (response.code == 401 && retryUnauthorized) {
                return fetchUsageLocked(ensureFreshLocked(account, force = true), retryUnauthorized = false)
            }
            error("Failed to fetch Codex usage: ${response.code}")
        }
        val usage = parseCodexUsage(json.parseToJsonElement(response.body.string()).jsonObject)
        val updated = account.copy(
            tokenStatus = CodexTokenStatus.AVAILABLE,
            usage = usage,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        return addHeader("Authorization", "Bearer ${account.accessToken}")
            .addHeader("ChatGPT-Account-Id", account.chatgptAccountId)
            .addHeader("originator", "codex_cli_rs")
            .addHeader("Accept", "application/json")
    }

    fun isSignedIn(accountId: String): Boolean = accounts.value.any { it.id == accountId }

    private fun replaceAccount(
        accountId: String,
        transform: (CodexAccount) -> CodexAccount,
    ) {
        ensureLoadedLocked()
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: CodexAccountState) {
        store.write(newState)
        state = newState
        _accounts.value = newState.accounts
    }

    companion object {
        const val CODEX_BASE_URL = "https://chatgpt.com/backend-api"
        private const val REFRESH_MARGIN_MS = 30_000L
    }
}

internal fun isCodexRefreshAuthenticationFailure(
    statusCode: Int,
    responseBody: String,
    json: Json,
): Boolean {
    if (statusCode == 401) return true
    if (statusCode != 400) return false
    val errorCode = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return errorCode == "invalid_grant" || errorCode == "invalid_token"
}

internal fun selectCodexAccountIndex(
    accounts: List<CodexAccount>,
    startIndex: Int,
    nowMillis: Long = System.currentTimeMillis(),
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable(nowMillis)) return index
    }
    return null
}
