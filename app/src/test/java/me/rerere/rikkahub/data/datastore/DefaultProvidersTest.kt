/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `OpenCode presets use separate current Go and Zen catalogs and start disabled`() {
        val providers = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
        val go = providers.single { it.name == "OpenCode Go" }
        val zen = providers.single { it.name == "OpenCode Zen" }
        assertEquals("https://opencode.ai/inference/go/v1", go.baseUrl)
        assertEquals("https://opencode.ai/inference/v1", zen.baseUrl)
        for (provider in listOf(go, zen)) {
            assertFalse(provider.enabled)
            assertTrue(provider.builtIn)
            assertEquals("", provider.apiKey)
        }
        assertEquals(DEFAULT_PROVIDERS.size, DEFAULT_PROVIDERS.map { it.id }.distinct().size)
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }
}
