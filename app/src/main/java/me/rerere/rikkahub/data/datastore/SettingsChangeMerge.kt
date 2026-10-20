package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Apply only fields changed by the editor to the latest settings snapshot. */
internal fun Settings.withSettingsEdit(previous: Settings, desired: Settings): Settings {
    if (init || previous.init || desired.init || previous == desired) return this
    val merged = JsonInstant.decodeFromJsonElement<Settings>(
        mergeSettingChanges(
            JsonInstant.encodeToJsonElement(previous),
            JsonInstant.encodeToJsonElement(desired),
            JsonInstant.encodeToJsonElement(this),
        )
    )
    // Providers contain transient descriptions/flags, including inside model overrides.
    // Search services have no stable IDs and may use identity equality: retain atomic semantics.
    return merged.copy(
        providers = mergeIdentifiedEdits(previous.providers, desired.providers, providers,
            { it.id }, ::mergeProviderEdit),
        mcpServers = mergeIdentifiedEdits(previous.mcpServers, desired.mcpServers, mcpServers,
            { it.id }, ::mergeMcpServerEdit),
        searchServices = if (previous.searchServices == desired.searchServices) searchServices else desired.searchServices,
    )
}

/** Objects and ID-bearing lists merge recursively; other lists and scalars are atomic. */
internal fun mergeSettingChanges(
    previous: JsonElement,
    desired: JsonElement,
    current: JsonElement,
): JsonElement {
    if (previous == desired) return current
    if (previous is JsonArray && desired is JsonArray && current is JsonArray) {
        fun JsonElement.id() = (this as? JsonObject)?.get("id")
        if ((previous + desired + current).all { it.id() != null } &&
            listOf(previous, desired, current).all { list -> list.map { it.id() }.distinct().size == list.size }
        ) {
            return JsonArray(mergeIdentifiedEdits(previous, desired, current, { it.id()!! }, ::mergeSettingChanges))
        }
    }
    if (previous !is JsonObject || desired !is JsonObject || current !is JsonObject) return desired
    val merged = current.toMutableMap()
    (previous.keys + desired.keys).forEach { key ->
        val before = previous[key]
        val after = desired[key]
        if (before != after) {
            val latest = current[key]
            when {
                after == null -> merged.remove(key)
                before != null && latest != null -> merged[key] = mergeSettingChanges(before, after, latest)
                else -> merged[key] = after
            }
        }
    }
    return JsonObject(merged)
}

/**
 * Explicit removals win; a missing current item is never resurrected by a stale edit.
 * New IDs from both writers survive (an already committed addition wins an ID collision).
 * Unchanged relative order preserves current order; explicit reorder wins for requested IDs,
 * appending concurrent additions. New items without reorder are inserted beside their desired anchors.
 */
internal fun <T, ID> mergeIdentifiedEdits(
    previous: List<T>,
    desired: List<T>,
    current: List<T>,
    id: (T) -> ID,
    mergeItem: (T, T, T) -> T,
): List<T> {
    if (previous == desired) return current
    if (current == previous) return desired
    val before = previous.associateBy(id)
    val after = desired.associateBy(id)
    val latest = current.associateBy(id)
    // Ambiguous identities cannot safely describe an edit. Keep the committed collection.
    if (before.size != previous.size || after.size != desired.size || latest.size != current.size) return current
    val removed = before.keys - after.keys
    val values = current.filter { id(it) !in removed }.associateBy(id).toMutableMap()
    desired.forEach { item ->
        val key = id(item)
        val old = before[key]
        val now = latest[key]
        when {
            old != null && now != null -> values[key] = mergeItem(old, item, now)
            old == null && now == null -> values[key] = item
        }
    }
    val requestedIds = desired.map(id)
    val retainedBefore = previous.map(id).filter { it in after }
    val retainedAfter = requestedIds.filter { it in before }
    val orderedIds = if (retainedBefore != retainedAfter) {
        requestedIds.filter { it in values } + current.map(id).filter { it in values && it !in after }
    } else {
        current.map(id).filter { it in values }.toMutableList().apply {
            requestedIds.forEachIndexed { index, key ->
                if (key !in this && key in values) {
                    val next = requestedIds.drop(index + 1).firstOrNull { it in this }
                    val preceding = requestedIds.take(index).lastOrNull { it in this }
                    add(when {
                        next != null -> indexOf(next)
                        preceding != null -> indexOf(preceding) + 1
                        else -> size
                    }, key)
                }
            }
        }
    }
    return orderedIds.map { values.getValue(it) }
}

internal fun mergeProviderEdit(
    previous: ProviderSetting,
    desired: ProviderSetting,
    current: ProviderSetting,
): ProviderSetting {
    if (previous == desired) return current
    if (current == previous) return desired
    if (previous.id != desired.id) return desired
    if (previous.id != current.id) return current
    if (previous::class != desired::class) return desired
    if (previous::class != current::class) return current
    val merged = JsonInstant.decodeFromJsonElement<ProviderSetting>(mergeSettingChanges(
        JsonInstant.encodeToJsonElement(previous), JsonInstant.encodeToJsonElement(desired),
        JsonInstant.encodeToJsonElement(current),
    ))
    return merged.copyProvider(
        models = mergeIdentifiedEdits(previous.models, desired.models, current.models, { it.id }, ::mergeModelEdit),
        builtIn = if (previous.builtIn != desired.builtIn) desired.builtIn else current.builtIn,
        description = if (previous.description != desired.description) desired.description else current.description,
        shortDescription = if (previous.shortDescription != desired.shortDescription) {
            desired.shortDescription
        } else current.shortDescription,
    )
}

internal fun mergeModelEdit(previous: Model, desired: Model, current: Model): Model {
    if (previous == desired) return current
    if (current == previous) return desired
    val merged = JsonInstant.decodeFromJsonElement<Model>(mergeSettingChanges(
        JsonInstant.encodeToJsonElement(previous), JsonInstant.encodeToJsonElement(desired),
        JsonInstant.encodeToJsonElement(current),
    ))
    val before = previous.providerOverwrite
    val after = desired.providerOverwrite
    val latest = current.providerOverwrite
    val override = when {
        before == after -> latest
        after == null -> null
        before == null -> latest ?: after
        latest == null -> null
        else -> mergeProviderEdit(before, after, latest)
    }
    return merged.copy(providerOverwrite = override)
}

internal fun Settings.withProviderModelEdit(providerId: Uuid, previous: Model, desired: Model): Settings =
    if (previous.id != desired.id) this else copy(providers = providers.map { provider ->
        if (provider.id != providerId) provider else provider.copyProvider(models = provider.models.map { model ->
            if (model.id == desired.id) mergeModelEdit(previous, desired, model) else model
        })
    })

internal fun Settings.withMcpServerEdit(previous: McpServerConfig, desired: McpServerConfig): Settings {
    if (previous.id != desired.id) return this
    return copy(mcpServers = mcpServers.map { current ->
        if (current.id != desired.id) current else mergeMcpServerEdit(previous, desired, current)
    })
}

private fun mergeMcpServerEdit(
    previous: McpServerConfig,
    desired: McpServerConfig,
    current: McpServerConfig,
): McpServerConfig {
    if (previous == desired) return current
    if (current == previous) return desired
    if (previous::class != desired::class) return desired
    if (previous::class != current::class) return current
    val merged = JsonInstant.decodeFromJsonElement<McpServerConfig>(mergeSettingChanges(
        JsonInstant.encodeToJsonElement(previous), JsonInstant.encodeToJsonElement(desired),
        JsonInstant.encodeToJsonElement(current),
    ))
    // MCP tool names are stable identities within one server; discovery must retain user flags.
    val tools = mergeIdentifiedEdits(previous.commonOptions.tools, desired.commonOptions.tools,
        current.commonOptions.tools, { it.name }) { before, after, latest ->
        if (before == after) latest else JsonInstant.decodeFromJsonElement<McpTool>(mergeSettingChanges(
            JsonInstant.encodeToJsonElement(before), JsonInstant.encodeToJsonElement(after),
            JsonInstant.encodeToJsonElement(latest),
        ))
    }
    return merged.clone(commonOptions = merged.commonOptions.copy(tools = tools))
}
