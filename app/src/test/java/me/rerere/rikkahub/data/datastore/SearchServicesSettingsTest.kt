/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchServicesSettingsTest {
    @Test
    fun `selected provider survives Settings write and reread`() {
        val existingServices = listOf(SearchServiceOptions.BingLocalOptions())
        val selectedTypes = listOf(
            SearchServiceOptions.TavilyOptions::class,
            SearchServiceOptions.MetasoOptions::class,
            SearchServiceOptions.ExaOptions::class,
        )
        val selectedServices = selectedTypes.map(SearchServiceOptions::create)
        val editedSettings = Settings(searchServices = selectedServices + existingServices)

        // Mirrors SettingSearchPage's copy and PreferencesStore's SEARCH_SERVICES write/read.
        val storedValue = JsonInstant.encodeToString(editedSettings.searchServices)
        val rereadSettings = editedSettings.copy(
            searchServices = JsonInstant.decodeFromString(storedValue)
        )

        assertEquals(selectedTypes.size + existingServices.size, rereadSettings.searchServices.size)
        selectedTypes.zip(selectedServices).zip(rereadSettings.searchServices).forEach { (selected, options) ->
            val (type, selectedOptions) = selected
            assertEquals(type.displayName, options.displayName)
            assertEquals(selectedOptions.id, options.id)
            assertEquals(selectedOptions::class, options::class)
        }
        assertTrue(rereadSettings.searchServices.last() is SearchServiceOptions.BingLocalOptions)
    }
}
