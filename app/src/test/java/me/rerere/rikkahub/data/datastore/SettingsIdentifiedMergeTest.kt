package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsIdentifiedMergeTest {
    private fun provider(name: String) = ProviderSetting.OpenAI(name = name)

    @Test
    fun `different providers edited from the same snapshot both survive`() {
        val a = provider("a")
        val b = provider("b")
        val before = Settings(providers = listOf(a, b))
        val latest = before.copy(providers = listOf(a.copy(name = "renamed"), b))
        val desired = before.copy(providers = listOf(a, b.copy(enabled = false)))

        assertEquals(listOf(a.copy(name = "renamed"), b.copy(enabled = false)),
            latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `independent fields on one provider merge including a refreshed credential`() {
        val a = provider("a").copy(apiKey = "before-test-key")
        val before = Settings(providers = listOf(a))
        val latest = before.copy(providers = listOf(a.copy(apiKey = "new-test-key")))
        val desired = before.copy(providers = listOf(a.copy(baseUrl = "https://edited.example/v1")))

        assertEquals(a.copy(apiKey = "new-test-key", baseUrl = "https://edited.example/v1"),
            latest.withSettingsEdit(before, desired).providers.single())
    }

    @Test
    fun `explicit conflict on the same field uses the last editor change`() {
        val a = provider("a")
        assertEquals("desired", mergeProviderEdit(a, a.copy(name = "desired"),
            a.copy(name = "committed")).name)
    }

    @Test
    fun `reorder preserves latest item edits and concurrent additions`() {
        val a = provider("a")
        val b = provider("b")
        val c = provider("c")
        val before = Settings(providers = listOf(a, b))
        val latest = before.copy(providers = listOf(a.copy(enabled = false), b, c))
        val desired = before.copy(providers = listOf(b, a))

        assertEquals(listOf(b, a.copy(enabled = false), c), latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `stale field edit preserves concurrent reorder and anchors its new item`() {
        val a = provider("a")
        val b = provider("b")
        val added = provider("new")
        val before = Settings(providers = listOf(a, b))
        val latest = before.copy(providers = listOf(b, a))
        val desired = before.copy(providers = listOf(added, a.copy(enabled = false), b))

        assertEquals(listOf(b, added, a.copy(enabled = false)), latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `explicit removal wins while unrelated new providers survive`() {
        val a = provider("a")
        val b = provider("b")
        val added = provider("new")
        val before = Settings(providers = listOf(a, b))
        val latest = before.copy(providers = listOf(a.copy(name = "edited"), b, added))
        val desired = before.copy(providers = listOf(b))

        assertEquals(listOf(b, added), latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `stale provider edit cannot recreate an already deleted provider`() {
        val a = provider("a")
        val b = provider("b")
        val before = Settings(providers = listOf(a, b))
        val latest = before.copy(providers = listOf(b))
        val desired = before.copy(providers = listOf(a.copy(name = "edited"), b))

        assertEquals(listOf(b), latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `simultaneous additions keep both IDs and a committed collision`() {
        val a = provider("a")
        val b = provider("b")
        val before = Settings(providers = emptyList())
        val latest = before.copy(providers = listOf(a))
        val desired = before.copy(providers = listOf(a.copy(name = "collision"), b))

        assertEquals(listOf(a, b), latest.withSettingsEdit(before, desired).providers)
    }

    @Test
    fun `models merge by identity without undoing a sibling edit or resurrecting a removed model`() {
        val m1 = Model(modelId = "one")
        val m2 = Model(modelId = "two")
        val added = Model(modelId = "new")
        val a = provider("a").copy(models = listOf(m1, m2))
        val before = Settings(providers = listOf(a))
        val latest = before.copy(providers = listOf(a.copy(models = listOf(m1.copy(displayName = "latest"), added))))
        val desired = before.copy(providers = listOf(a.copy(models = listOf(
            m1.copy(codexReasoningEfforts = listOf("high")), m2.copy(displayName = "stale"),
        ))))
        val models = latest.withSettingsEdit(before, desired).providers.single().models

        assertEquals(listOf(m1.copy(displayName = "latest", codexReasoningEfforts = listOf("high")), added), models)
    }

    @Test
    fun `merged provider and nested override retain current runtime descriptions`() {
        val nested = DEFAULT_PROVIDERS[1].copyProvider()
        val model = Model(providerOverwrite = nested)
        val a = DEFAULT_PROVIDERS.first().copyProvider(models = listOf(model))
        val desired = a.copyProvider(name = "edited", models = listOf(model.copy(displayName = "edited")))
        val latest = a.copyProvider(enabled = false,
            models = listOf(model.copy(providerOverwrite = nested.copyProvider(enabled = false))))
        val actual = mergeProviderEdit(a, desired, latest)
        val override = actual.models.single().providerOverwrite!!

        assertEquals("edited", actual.name)
        assertEquals(false, actual.enabled)
        assertEquals(a.builtIn, actual.builtIn)
        assertSame(latest.description, actual.description)
        assertSame(latest.shortDescription, actual.shortDescription)
        assertEquals("edited", actual.models.single().displayName)
        assertEquals(false, override.enabled)
        assertSame(nested.description, override.description)
        assertSame(nested.shortDescription, override.shortDescription)
    }

    @Test
    fun `stale nested override edit cannot restore its concurrent removal`() {
        val override = provider("override")
        val before = Model(providerOverwrite = override)
        val desired = before.copy(providerOverwrite = override.copy(name = "edited"))
        assertNull(mergeModelEdit(before, desired, before.copy(providerOverwrite = null)).providerOverwrite)
    }

    @Test
    fun `model modal baseline only changes edited fields and never recreates a deleted model`() {
        val m = Model(modelId = "test")
        val a = provider("a").copy(models = listOf(m))
        val latest = Settings(providers = listOf(a.copy(
            name = "latest", models = listOf(m.copy(displayName = "latest")),
        )))
        val actual = latest.withProviderModelEdit(a.id, m, m.copy(codexReasoningEfforts = listOf("high")))
        assertEquals("latest", actual.providers.single().name)
        assertEquals(m.copy(displayName = "latest", codexReasoningEfforts = listOf("high")),
            actual.providers.single().models.single())
        val deleted = latest.copy(providers = listOf(a.copy(models = emptyList())))
        assertEquals(emptyList<Model>(), deleted.withProviderModelEdit(a.id, m,
            m.copy(displayName = "stale")).providers.single().models)
    }

    @Test
    fun `MCP user edit preserves background OAuth refresh and discovery`() {
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "before",
            oauth = McpOAuthState(enabled = true, accessToken = "before-test-token")), url = "https://before.example")
        val before = Settings(mcpServers = listOf(config))
        val updatedOptions = config.commonOptions.copy(oauth = config.commonOptions.oauth!!.copy(
            accessToken = "refreshed-test-token"), tools = listOf(McpTool(name = "discovered")))
        val latest = before.copy(mcpServers = listOf(config.copy(commonOptions = updatedOptions)))
        val desired = before.copy(mcpServers = listOf(config.copy(commonOptions = config.commonOptions.copy(name = "renamed"))))

        assertEquals(listOf(config.copy(commonOptions = updatedOptions.copy(name = "renamed"))),
            latest.withSettingsEdit(before, desired).mcpServers)
    }

    @Test
    fun `MCP sync uses its connection baseline and cannot revert a changed URL or newer OAuth`() {
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(name = "before",
            oauth = McpOAuthState(enabled = true, accessToken = "before-test-token")), url = "https://before.example")
        val discovered = config.copy(commonOptions = config.commonOptions.copy(tools = listOf(McpTool(name = "new"))))
        val latest = Settings(mcpServers = listOf(config.copy(url = "https://edited.example",
            commonOptions = config.commonOptions.copy(oauth = config.commonOptions.oauth!!.copy(
                accessToken = "refreshed-test-token")))))
        val updated = latest.withMcpServerEdit(config, discovered).mcpServers.single() as McpServerConfig.StreamableHTTPServer

        assertEquals("https://edited.example", updated.url)
        assertEquals("refreshed-test-token", updated.commonOptions.oauth!!.accessToken)
        assertEquals(discovered.commonOptions.tools, updated.commonOptions.tools)
        assertEquals(emptyList<McpServerConfig>(), latest.copy(mcpServers = emptyList())
            .withMcpServerEdit(config, discovered).mcpServers)
    }

    @Test
    fun `MCP discovery preserves tool approval and enable flags changed during sync`() {
        val tool = McpTool(name = "test", description = "before")
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(tools = listOf(tool)))
        val discovered = config.copy(commonOptions = config.commonOptions.copy(
            tools = listOf(tool.copy(description = "discovered"), McpTool(name = "added")),
        ))
        val latest = Settings(mcpServers = listOf(config.copy(commonOptions = config.commonOptions.copy(
            tools = listOf(tool.copy(enable = false, needsApproval = true)),
        ))))
        val actual = latest.withMcpServerEdit(config, discovered).mcpServers.single().commonOptions.tools

        assertEquals(listOf(tool.copy(description = "discovered", enable = false, needsApproval = true),
            McpTool(name = "added")), actual)
    }

    @Test
    fun `MCP modal tool edit retains sibling edits discovery and removed tools`() {
        val a = McpTool(name = "a", description = "before")
        val b = McpTool(name = "b")
        val c = McpTool(name = "c")
        val config = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(tools = listOf(a, b)))
        val before = Settings(mcpServers = listOf(config))
        val desired = before.copy(mcpServers = listOf(config.copy(commonOptions = config.commonOptions.copy(
            tools = listOf(a.copy(needsApproval = true), b.copy(enable = false)),
        ))))
        val latest = before.copy(mcpServers = listOf(config.copy(commonOptions = config.commonOptions.copy(
            tools = listOf(a.copy(description = "discovered", enable = false), c),
        ))))

        assertEquals(listOf(a.copy(description = "discovered", enable = false, needsApproval = true), c),
            latest.withSettingsEdit(before, desired).mcpServers.single().commonOptions.tools)
    }
}
