package me.rerere.rikkahub.plugin.loader

import okhttp3.HttpUrl

internal class PluginNetworkPolicy(allowedHosts: List<String>) {
    private val hosts = allowedHosts
        .map { it.trim().lowercase().trimEnd('.') }
        .filter { it.isNotBlank() }

    val allowsAllHosts: Boolean = hosts.isEmpty() || "*" in hosts

    fun allows(url: HttpUrl?): Boolean {
        val host = url?.host?.trimEnd('.') ?: return false
        return allowsAllHosts || hosts.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }
}
