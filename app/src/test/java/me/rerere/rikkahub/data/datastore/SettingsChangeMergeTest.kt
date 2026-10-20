package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsChangeMergeTest {
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private fun verify(before: String, desired: String, current: String, expected: String) {
        assertEquals(json(expected), mergeSettingChanges(json(before), json(desired), json(current)))
    }

    @Test
    fun `queued switch edits preserve the switch committed first`() {
        verify(
            """{"tools":{"time":false,"battery":false}}""",
            """{"tools":{"time":false,"battery":true}}""",
            """{"tools":{"time":true,"battery":false}}""",
            """{"tools":{"time":true,"battery":true}}""",
        )
    }

    @Test
    fun `explicit disable keeps concurrent changes to other switches`() {
        verify(
            """{"tools":{"time":true,"battery":false}}""",
            """{"tools":{"time":false,"battery":false}}""",
            """{"tools":{"time":true,"battery":true}}""",
            """{"tools":{"time":false,"battery":true}}""",
        )
    }

    @Test
    fun `background list update survives an unrelated switch save`() {
        verify(
            """{"tools":{"time":false},"mcp":["old"]}""",
            """{"tools":{"time":true},"mcp":["old"]}""",
            """{"tools":{"time":false},"mcp":["new"],"other":1}""",
            """{"tools":{"time":true},"mcp":["new"],"other":1}""",
        )
    }

    @Test
    fun `unchanged stale snapshot cannot undo committed state`() {
        verify("""{"enabled":false}""", """{"enabled":false}""",
            """{"enabled":true}""", """{"enabled":true}""")
    }

    @Test
    fun `explicit null and removed keys remain edits`() {
        verify("""{"a":1,"b":2}""", """{"a":null}""",
            """{"a":1,"b":2,"c":3}""", """{"a":null,"c":3}""")
    }

    @Test
    fun `an explicitly edited list replaces only that field`() {
        verify("""{"list":[1],"enabled":false}""", """{"list":[],"enabled":false}""",
            """{"list":[1],"enabled":true}""", """{"list":[],"enabled":true}""")
    }

    @Test
    fun `new nested key preserves concurrent nested keys`() {
        verify("""{"tools":{}}""", """{"tools":{"a":true}}""",
            """{"tools":{"b":true}}""", """{"tools":{"a":true,"b":true}}""")
    }

    @Test
    fun `queued display edits preserve latest assistant and local proactive state`() {
        val before = Settings(launchCount = 8)
        val latest = before.copy(
            launchCount = 9,
            proactiveMessageSetting = before.proactiveMessageSetting.copy(includeAppTimeline = true),
            displaySetting = before.displaySetting.copy(userAvatar = Avatar.Image("file:///latest-avatar")),
        )
        val desired = before.copy(dynamicColor = false)

        assertEquals(latest.copy(dynamicColor = false), latest.withSettingsEdit(before, desired))
    }

    @Test
    fun `local proactive options merge independent queued edits within the same object`() {
        val before = Settings()
        val latest = before.copy(proactiveMessageSetting = before.proactiveMessageSetting.copy(
            includeAppTimeline = true,
            maxFollowUpMessages = 5,
        ))
        val desired = before.copy(proactiveMessageSetting = before.proactiveMessageSetting.copy(
            enabled = true,
        ))

        assertEquals(
            latest.copy(proactiveMessageSetting = latest.proactiveMessageSetting.copy(enabled = true)),
            latest.withSettingsEdit(before, desired),
        )
    }

    @Test
    fun `independent display edits from the same snapshot both survive`() {
        val before = Settings()
        val first = before.copy(displaySetting = before.displaySetting.copy(userAvatar = Avatar.Image("file:///avatar")))
        val second = before.copy(displaySetting = before.displaySetting.copy(userAvatar = Avatar.Dummy),
            dynamicColor = false)
        val committed = before.withSettingsEdit(before, first).withSettingsEdit(before, second)

        assertEquals(first.copy(dynamicColor = false), committed)
    }

    @Test
    fun `unrelated edits retain provider runtime descriptions nested overrides and search identities`() {
        val nestedOverride = DEFAULT_PROVIDERS[1].copyProvider()
        val modelOverride = DEFAULT_PROVIDERS.first().copyProvider(
            models = listOf(Model(providerOverwrite = nestedOverride)),
        )
        val provider = DEFAULT_PROVIDERS.first().copyProvider(
            models = listOf(Model(providerOverwrite = modelOverride)),
        )
        val before = Settings(providers = listOf(provider))
        val latest = before.copy(launchCount = 9)
        val updated = latest.withSettingsEdit(before, before.copy(dynamicColor = false))

        assertEquals(latest.copy(dynamicColor = false), updated)
        assertSame(latest.providers, updated.providers)
        assertSame(provider, updated.providers.single())
        assertEquals(true, updated.providers.single().builtIn)
        assertSame(provider.description, updated.providers.single().description)
        assertSame(provider.shortDescription, updated.providers.single().shortDescription)
        assertSame(modelOverride, updated.providers.single().models.single().providerOverwrite)
        assertSame(nestedOverride, updated.providers.single().models.single()
            .providerOverwrite!!.models.single().providerOverwrite)
        assertSame(latest.searchServices, updated.searchServices)
        assertSame(latest.searchServices.single(), updated.searchServices.single())
    }

    @Test
    fun `an explicitly edited provider list retains its runtime data and nested overrides`() {
        val modelOverride = DEFAULT_PROVIDERS[1].copyProvider()
        val provider = DEFAULT_PROVIDERS.first().copyProvider(models = listOf(Model(providerOverwrite = modelOverride)))
        val before = Settings(providers = listOf(provider))
        val latest = before.copy(enableWebSearch = true)
        val desired = before.copy(providers = listOf(provider.copyProvider(name = "edited")))
        val updated = latest.withSettingsEdit(before, desired)

        assertEquals(latest.copy(providers = desired.providers), updated)
        assertSame(desired.providers, updated.providers)
        assertSame(provider.description, updated.providers.single().description)
        assertSame(provider.shortDescription, updated.providers.single().shortDescription)
        assertSame(modelOverride, updated.providers.single().models.single().providerOverwrite)
        assertEquals(true, updated.providers.single().builtIn)
    }

    @Test
    fun `an explicitly edited search list keeps its option instances`() {
        val before = Settings()
        val latest = before.copy(launchCount = 6)
        val desired = before.copy(searchServices = listOf(SearchServiceOptions.BingLocalOptions()))
        val updated = latest.withSettingsEdit(before, desired)

        assertEquals(latest.copy(searchServices = desired.searchServices), updated)
        assertSame(desired.searchServices, updated.searchServices)
        assertSame(desired.searchServices.single(), updated.searchServices.single())
    }

    @Test
    fun `an unchanged stale editor returns the current instance without rebuilding runtime values`() {
        val before = Settings()
        val current = before.copy(launchCount = 10)
        assertSame(current, current.withSettingsEdit(before, before.copy()))
    }

    @Test
    fun `dummy editor or desired state cannot overwrite committed settings`() {
        val current = Settings(launchCount = 4)
        assertSame(current, current.withSettingsEdit(Settings.dummy(), Settings()))
        assertSame(current, current.withSettingsEdit(current, Settings.dummy()))
    }
}
