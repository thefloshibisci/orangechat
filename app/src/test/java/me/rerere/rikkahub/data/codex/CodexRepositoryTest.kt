package me.rerere.rikkahub.data.codex

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CodexRepositoryTest {
    @Test fun `authenticator retries official unauthorized handshake only once`() = runBlocking {
        val count = AtomicInteger()
        val store = MemoryStore(CodexAccountState(listOf(account().copy(expiresAt = Long.MAX_VALUE))))
        val repo = CodexAccountRepository(store, client(body = """{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""", count = count), Json)
        val auth = CodexAuthenticator(repo, "id")
        val request = Request.Builder().url("https://chatgpt.com/backend-api/codex/responses")
            .header("Authorization", "Bearer old-access").build()
        val rejected = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("unauthorized").build()
        val retry = auth.authenticate(null, rejected)!!
        assertEquals("Bearer new-access", retry.header("Authorization"))
        assertEquals(1, count.get())
        assertNull(auth.authenticate(null, rejected.newBuilder().request(retry).priorResponse(rejected).build()))
        assertNull(auth.authenticate(null, rejected.newBuilder().request(request.newBuilder().url("https://example.com/responses").build()).build()))
        assertEquals(1, count.get())
        assertEquals(CodexTokenStatus.AVAILABLE, repo.accounts.value.single().tokenStatus)
    }

    @Test fun `cancelling caller still persists rotated refresh token`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("""{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val store = MemoryStore(CodexAccountState(listOf(account())))
        val repo = CodexAccountRepository(store, client, Json)
        val job = launch(Dispatchers.Default) { repo.acquireAccount() }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        job.cancel()
        release.countDown()
        job.join()
        assertEquals("rotated", store.state.accounts.single().refreshToken)
        assertTrue(job.isCancelled)
    }

    @Test fun `concurrent stale unauthorized responses refresh once and cannot invalidate new token`() = runBlocking {
        val count = AtomicInteger()
        val store = MemoryStore(CodexAccountState(listOf(account().copy(expiresAt = Long.MAX_VALUE))))
        val repo = CodexAccountRepository(store, client(body = """{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""", count = count), Json)
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) {
            repo.refreshAfterUnauthorized("id", "old-access")
        } }.awaitAll() }
        assertEquals(1, count.get())
        assertTrue(results.all { it.accessToken == "new-access" })
        assertEquals(CodexTokenStatus.AVAILABLE, repo.accounts.value.single().tokenStatus)
        repo.delete("id")
        assertTrue(runCatching { repo.refreshAfterUnauthorized("id", "old-access") }.isFailure)
        assertTrue(store.state.accounts.isEmpty())
    }

    @Test fun `conversation stays on same account and falls back when disabled`() = runBlocking {
        val store = MemoryStore(CodexAccountState(listOf(account().copy(expiresAt = Long.MAX_VALUE),
            account().copy(id = "second", expiresAt = Long.MAX_VALUE))))
        val repo = CodexAccountRepository(store, client(body = "{}"), Json)
        val first = repo.acquireAccount("chat-1")
        repeat(5) { assertEquals(first.id, repo.acquireAccount("chat-1").id) }
        repo.setEnabled(first.id, false)
        assertNotEquals(first.id, repo.acquireAccount("chat-1").id)
    }

    @Test fun `temporary credential read failure is recoverable and never overwrites accounts`() = runBlocking {
        var readable = false
        var writes = 0
        val store = object : CodexAccountStore {
            override fun read(): CodexAccountState {
                check(readable)
                return CodexAccountState(listOf(account().copy(expiresAt = Long.MAX_VALUE)))
            }
            override fun write(state: CodexAccountState) { writes++ }
        }
        val repo = CodexAccountRepository(store, client(body = "{}"), Json)
        assertTrue(repo.storageUnavailable.value)
        assertTrue(runCatching { repo.acquireAccount() }.isFailure)
        assertTrue(runCatching { repo.saveLogin("{}") }.isFailure)
        assertEquals(0, writes)
        readable = true
        repo.reloadCredentials()
        assertFalse(repo.storageUnavailable.value)
        assertEquals("old-access", repo.acquireAccount().accessToken)
        assertEquals(0, writes)
    }

    @Test fun `usage unauthorized refreshes once without dropping login`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths.add(request.url.encodedPath)
            val token = request.url.encodedPath == "/oauth/token"
            val code = if (!token && request.header("Authorization") == "Bearer old-access") 401 else 200
            val body = if (token) """{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}""" else "{}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = CodexAccountRepository(MemoryStore(CodexAccountState(listOf(account().copy(expiresAt = Long.MAX_VALUE)))), client, Json)
        assertEquals("new-access", repo.refreshAccount("id").accessToken)
        assertEquals(listOf("/backend-api/wham/usage", "/oauth/token", "/backend-api/wham/usage"), paths)
        assertEquals(CodexTokenStatus.AVAILABLE, repo.accounts.value.single().tokenStatus)
    }

    private class MemoryStore(var state: CodexAccountState) : CodexAccountStore {
        override fun read() = state
        override fun write(state: CodexAccountState) { this.state = state }
    }
    private fun account() = CodexAccount("id", name = "Test", chatgptAccountId = "account",
        accessToken = "old-access", refreshToken = "old-refresh", expiresAt = 1)
    private fun client(code: Int = 200, body: String, count: AtomicInteger = AtomicInteger()) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            count.incrementAndGet()
            assertEquals("https://auth.openai.com/oauth/token", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun `concurrent acquisition refreshes once and persists rotated token`() = runBlocking {
        val count = AtomicInteger()
        val store = MemoryStore(CodexAccountState(listOf(account())))
        val repo = CodexAccountRepository(store, client(body = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""", count = count), Json)
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { repo.acquireAccount() } }.awaitAll() }
        assertEquals(1, count.get())
        assertTrue(results.all { it.accessToken == "new-access" })
        assertEquals("new-refresh", store.state.accounts.single().refreshToken)
        repo.delete("id")
        repo.updateUsage("id", CodexUsageSnapshot()) // A late request cannot recreate a logged-out account.
        assertTrue(store.state.accounts.isEmpty())
        assertTrue(repo.accounts.value.isEmpty())
        assertTrue(runCatching { repo.acquireAccount() }.isFailure)
    }

    @Test fun `invalid grant requires login but transient server failure keeps refresh token`() = runBlocking {
        for (code in listOf(400, 500)) {
            val store = MemoryStore(CodexAccountState(listOf(account())))
            val repo = CodexAccountRepository(store, client(code, """{"error":"invalid_grant"}"""), Json)
            assertTrue(runCatching { repo.acquireAccount() }.isFailure)
            assertEquals(code == 400, repo.accounts.value.single().tokenStatus == CodexTokenStatus.INVALID)
            assertEquals("old-refresh", store.state.accounts.single().refreshToken)
        }
    }

    @Test fun `malformed token response never exposes body in exception chain`() = runBlocking {
        val store = MemoryStore(CodexAccountState(listOf(account())))
        val repo = CodexAccountRepository(store, client(body = "secret-refresh-token-invalid-json"), Json)
        val error = runCatching { repo.refreshAccount("id") }.exceptionOrNull()
        assertNotNull(error)
        generateSequence(error) { it.cause }.forEach { assertFalse(it.toString().contains("secret-refresh-token")) }
        assertEquals("old-refresh", store.state.accounts.single().refreshToken)
    }
}
