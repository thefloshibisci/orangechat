package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchServiceOptionsTest {
    @Test
    fun `factory and service dispatch cover every selectable provider`() {
        SearchServiceOptions.TYPES.forEach { type ->
            val options = SearchServiceOptions.create(type)

            assertEquals(type.displayName, options.displayName)
            assertConcreteType(type, options)
            assertSame(expectedService(type), SearchService.getService(options))
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
        val json = storageJson()

        val encoded = json.encodeToString(ListSerializer(SearchServiceOptions.serializer()), providers)
        val decoded = json.decodeFromString<List<SearchServiceOptions>>(encoded)

        assertEquals(providers.map { it::class }, decoded.map { it::class })
    }

    @Test
    fun `provider selection survives the settings storage round trip`() {
        val selectedTypes = SearchServiceOptions.TYPES
        val settingsSearchServices = selectedTypes.map(SearchServiceOptions::create)

        // This is the same JSON configuration used by PreferencesStore for SEARCH_SERVICES.
        val storedValue = storageJson().encodeToString(
            ListSerializer(SearchServiceOptions.serializer()),
            settingsSearchServices,
        )
        val reloadedSearchServices = storageJson().decodeFromString<List<SearchServiceOptions>>(storedValue)

        assertEquals(selectedTypes.size, reloadedSearchServices.size)
        selectedTypes.zip(reloadedSearchServices).forEach { (type, options) ->
            assertConcreteType(type, options)
            assertEquals(type.displayName, options.displayName)
            assertSame(expectedService(type), SearchService.getService(options))
        }
    }

    private fun storageJson() = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun assertConcreteType(type: SearchServiceType, options: SearchServiceOptions) {
        when (type) {
            SearchServiceType.BING_LOCAL -> assertTrue(options is SearchServiceOptions.BingLocalOptions)
            SearchServiceType.RIKKAHUB -> assertTrue(options is SearchServiceOptions.RikkaHubOptions)
            SearchServiceType.ZHIPU -> assertTrue(options is SearchServiceOptions.ZhipuOptions)
            SearchServiceType.TAVILY -> assertTrue(options is SearchServiceOptions.TavilyOptions)
            SearchServiceType.EXA -> assertTrue(options is SearchServiceOptions.ExaOptions)
            SearchServiceType.SEARXNG -> assertTrue(options is SearchServiceOptions.SearXNGOptions)
            SearchServiceType.LINKUP -> assertTrue(options is SearchServiceOptions.LinkUpOptions)
            SearchServiceType.BRAVE -> assertTrue(options is SearchServiceOptions.BraveOptions)
            SearchServiceType.METASO -> assertTrue(options is SearchServiceOptions.MetasoOptions)
            SearchServiceType.OLLAMA -> assertTrue(options is SearchServiceOptions.OllamaOptions)
            SearchServiceType.PERPLEXITY -> assertTrue(options is SearchServiceOptions.PerplexityOptions)
            SearchServiceType.FIRECRAWL -> assertTrue(options is SearchServiceOptions.FirecrawlOptions)
            SearchServiceType.JINA -> assertTrue(options is SearchServiceOptions.JinaOptions)
            SearchServiceType.BOCHA -> assertTrue(options is SearchServiceOptions.BochaOptions)
            SearchServiceType.GROK -> assertTrue(options is SearchServiceOptions.GrokOptions)
            SearchServiceType.TINYFISH -> assertTrue(options is SearchServiceOptions.TinyfishOptions)
            SearchServiceType.CUSTOM_JS -> assertTrue(options is SearchServiceOptions.CustomJsOptions)
        }
    }

    private fun expectedService(type: SearchServiceType): SearchService<*> = when (type) {
        SearchServiceType.BING_LOCAL -> BingSearchService
        SearchServiceType.RIKKAHUB -> RikkaHubSearchService
        SearchServiceType.ZHIPU -> ZhipuSearchService
        SearchServiceType.TAVILY -> TavilySearchService
        SearchServiceType.EXA -> ExaSearchService
        SearchServiceType.SEARXNG -> SearXNGService
        SearchServiceType.LINKUP -> LinkUpService
        SearchServiceType.BRAVE -> BraveSearchService
        SearchServiceType.METASO -> MetasoSearchService
        SearchServiceType.OLLAMA -> OllamaSearchService
        SearchServiceType.PERPLEXITY -> PerplexitySearchService
        SearchServiceType.FIRECRAWL -> FirecrawlSearchService
        SearchServiceType.JINA -> JinaSearchService
        SearchServiceType.BOCHA -> BochaSearchService
        SearchServiceType.GROK -> GrokSearchService
        SearchServiceType.TINYFISH -> TinyfishSearchService
        SearchServiceType.CUSTOM_JS -> CustomJsSearchService
    }
}
