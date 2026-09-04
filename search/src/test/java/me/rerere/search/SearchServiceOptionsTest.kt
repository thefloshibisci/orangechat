package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SearchServiceOptionsTest {
    @Test
    fun `factory and service dispatch cover every selectable provider`() {
        SearchServiceOptions.TYPES.forEach { (type, name) ->
            val options = SearchServiceOptions.create(type)

            assertEquals(type, options::class)
            assertEquals(name, options.displayName)
            assertSame(expectedService(options), SearchService.getService(options))
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
        val settingsSearchServices = SearchServiceOptions.TYPES.keys.map(SearchServiceOptions::create)

        // This is the same JSON configuration used by PreferencesStore for SEARCH_SERVICES.
        val storedValue = storageJson().encodeToString(
            ListSerializer(SearchServiceOptions.serializer()),
            settingsSearchServices,
        )
        val reloadedSearchServices = storageJson().decodeFromString<List<SearchServiceOptions>>(storedValue)

        assertEquals(settingsSearchServices.size, reloadedSearchServices.size)
        settingsSearchServices.zip(reloadedSearchServices).forEach { (selected, options) ->
            assertEquals(selected::class, options::class)
            assertEquals(selected.id, options.id)
            assertEquals(selected.displayName, options.displayName)
            assertSame(expectedService(options), SearchService.getService(options))
        }
    }

    private fun storageJson() = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun expectedService(options: SearchServiceOptions): SearchService<*> = when (options) {
        is SearchServiceOptions.BingLocalOptions -> BingSearchService
        is SearchServiceOptions.RikkaHubOptions -> RikkaHubSearchService
        is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
        is SearchServiceOptions.TavilyOptions -> TavilySearchService
        is SearchServiceOptions.ExaOptions -> ExaSearchService
        is SearchServiceOptions.SearXNGOptions -> SearXNGService
        is SearchServiceOptions.LinkUpOptions -> LinkUpService
        is SearchServiceOptions.BraveOptions -> BraveSearchService
        is SearchServiceOptions.MetasoOptions -> MetasoSearchService
        is SearchServiceOptions.OllamaOptions -> OllamaSearchService
        is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
        is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
        is SearchServiceOptions.JinaOptions -> JinaSearchService
        is SearchServiceOptions.BochaOptions -> BochaSearchService
        is SearchServiceOptions.GrokOptions -> GrokSearchService
        is SearchServiceOptions.TinyfishOptions -> TinyfishSearchService
        is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
    }
}
