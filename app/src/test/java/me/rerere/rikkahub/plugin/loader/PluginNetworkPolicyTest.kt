package me.rerere.rikkahub.plugin.loader

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.plugin.model.PluginManifest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetworkPolicyTest {
    @Test fun legacyManifestWithoutHostsAllowsSupabase() {
        val manifest = Json.decodeFromString<PluginManifest>("""
            {"id":"test.sticker","name":"Stickers","description":"","version":"1",
             "author":"Test","icon":"","entry":"main.js"}
        """.trimIndent())
        val policy = PluginNetworkPolicy(manifest.allowedHosts)
        assertTrue(policy.allows("https://example-project.supabase.co/rest/v1/stickers".toHttpUrlOrNull()))
        assertTrue(policy.allowsAllHosts)
    }

    @Test fun emptyHostsAllowCustomWebsitesAndLocalHttp() {
        val policy = PluginNetworkPolicy(emptyList())
        assertTrue(policy.allows("https://custom.example/page".toHttpUrlOrNull()))
        assertTrue(policy.allows("http://127.0.0.1:8080/api".toHttpUrlOrNull()))
    }

    @Test fun explicitHostsKeepDomainBoundaries() {
        val policy = PluginNetworkPolicy(listOf("example.com"))
        assertTrue(policy.allows("https://example.com/api".toHttpUrlOrNull()))
        assertTrue(policy.allows("https://api.example.com/api".toHttpUrlOrNull()))
        assertFalse(policy.allows("https://notexample.com/api".toHttpUrlOrNull()))
        assertFalse(policy.allows("https://example.com.evil.test/api".toHttpUrlOrNull()))
        assertFalse(policy.allows("https://example.com@evil.test/api".toHttpUrlOrNull()))
        assertFalse(policy.allowsAllHosts)
    }

    @Test fun explicitHostsAreNormalized() {
        val policy = PluginNetworkPolicy(listOf("  EXAMPLE.COM.  "))
        assertTrue(policy.allows("https://API.EXAMPLE.COM./api".toHttpUrlOrNull()))
        assertFalse(policy.allows("https://other.test/api".toHttpUrlOrNull()))
    }

    @Test fun wildcardAllowsAllHttpHostsAndRedirects() {
        val policy = PluginNetworkPolicy(listOf("example.com", " * "))
        assertTrue(policy.allows("https://other.test/api".toHttpUrlOrNull()))
        assertTrue(policy.allowsAllHosts)
    }

    @Test fun blankEntriesHaveTheSameDefaultAsAnEmptyList() {
        val policy = PluginNetworkPolicy(listOf("", "   "))
        assertTrue(policy.allows("https://custom.example/page".toHttpUrlOrNull()))
        assertTrue(policy.allowsAllHosts)
    }

    @Test fun unrestrictedPolicyStillRequiresAnHttpUrl() {
        val policy = PluginNetworkPolicy(emptyList())
        listOf("", "not a URL", "file:///private/data", "javascript:alert(1)", "ftp://example.com/file")
            .forEach { url -> assertFalse(url, policy.allows(url.toHttpUrlOrNull())) }
    }
}
