package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TavilySearchServiceTest {
    @Test fun searchDisablesGeneratedAnswerAndKeepsSourceResults() = runBlocking {
        val previous = SearchService.httpClient
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            assertEquals(JsonPrimitive(false), body["include_answer"])
            assertEquals(JsonPrimitive("test query"), body["query"])
            assertEquals(JsonPrimitive(3), body["max_results"])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"query":"test query","results":[{"title":"Source","url":"https://example.com","content":"Original source content","score":0.9}]}""".toResponseBody())
                .build()
        }.build()
        try {
            SearchService.httpClient = client
            val result = TavilySearchService.search(
                buildJsonObject { put("query", "test query") },
                SearchCommonOptions(resultSize = 3),
                SearchServiceOptions.TavilyOptions(apiKey = "test-key"),
            ).getOrThrow()
            assertNull(result.answer)
            assertEquals("Original source content", result.items.single().text)
            assertEquals("https://example.com", result.items.single().url)
        } finally {
            SearchService.httpClient = previous
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
