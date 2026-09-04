package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class SearchServiceOptionsTest {
    @Test
    fun `factory preserves every provider type`() {
        SearchServiceOptions.TYPES.keys.forEach { type ->
            assertEquals(type, SearchServiceOptions.create(type)::class)
        }
    }

    @Test
    fun `display names are based on provider type`() {
        assertEquals("Bing", SearchServiceOptions.BingLocalOptions().displayName)
        assertEquals("Tavily", SearchServiceOptions.TavilyOptions().displayName)
        assertEquals("秘塔", SearchServiceOptions.MetasoOptions().displayName)
        assertEquals("博查", SearchServiceOptions.BochaOptions().displayName)
        assertEquals("Custom JS", SearchServiceOptions.CustomJsOptions().displayName)
    }

    @Test
    fun `serialized providers keep their concrete types`() {
        val providers = listOf(
            SearchServiceOptions.BingLocalOptions(),
            SearchServiceOptions.TavilyOptions(apiKey = "tavily"),
            SearchServiceOptions.MetasoOptions(apiKey = "metaso"),
            SearchServiceOptions.BochaOptions(apiKey = "bocha"),
            SearchServiceOptions.CustomJsOptions(name = "custom"),
        )
        val json = Json { explicitNulls = false }

        val encoded = json.encodeToString(ListSerializer(SearchServiceOptions.serializer()), providers)
        val decoded = json.decodeFromString<List<SearchServiceOptions>>(encoded)

        assertEquals(providers.map { it::class }, decoded.map { it::class })
    }
}
