/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

private const val MAX_GEMINI_SCHEMA_DEPTH = 16
private const val MAX_GEMINI_DESCRIPTION_LENGTH = 1_024

/**
 * Converts a JSON Schema (as commonly exposed by MCP servers) into the conservative OpenAPI
 * subset accepted by Gemini function declarations.
 *
 * Gemini's native `parameters` field is stricter than JSON Schema 2020-12. In particular, some
 * endpoints reject metadata such as `title`, nullable unions frequently arrive without a direct
 * `type`, and unresolved `$ref` values fail the whole request. Resolve references while `$defs`
 * are still available, collapse unions to a concrete typed schema, and emit only known-safe keys.
 */
internal fun JsonElement.toGeminiFunctionParameters(): JsonObject {
    val root = this as? JsonObject ?: JsonObject(emptyMap())
    val normalized = normalizeGeminiSchema(
        source = root,
        definitions = root,
        resolvingRefs = emptySet(),
        depth = 0,
    )
    val properties = normalized["properties"] as? JsonObject ?: JsonObject(emptyMap())

    return buildJsonObject {
        put("type", "object")
        put("properties", properties)
        normalized["description"]?.let { put("description", it) }
        normalized["required"]?.let { put("required", it) }
    }
}

private fun normalizeGeminiSchema(
    source: JsonObject,
    definitions: JsonObject,
    resolvingRefs: Set<String>,
    depth: Int,
): JsonObject {
    if (depth >= MAX_GEMINI_SCHEMA_DEPTH) return minimalSchema(inferSchemaType(source))
    val expanded = expandSchema(source, definitions, resolvingRefs, depth)
    val type = inferSchemaType(expanded).takeUnless { it == "null" } ?: "string"

    if (depth >= MAX_GEMINI_SCHEMA_DEPTH) {
        return minimalSchema(type)
    }

    return buildJsonObject {
        put("type", type)

        (expanded["description"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_GEMINI_DESCRIPTION_LENGTH)
            ?.let { put("description", it) }

        when (type) {
            "string" -> {
                // Enums are functional constraints, not disposable schema metadata. In particular,
                // message_reaction uses them to expose the supported kaomoji to the model.
                val constant = (expanded["const"] as? JsonPrimitive)?.takeIf { it.isString }
                val choices = (constant?.let { JsonArray(listOf(it)) } ?: expanded["enum"] as? JsonArray)
                    ?.filterIsInstance<JsonPrimitive>()
                    ?.filter { it.isString }
                    ?.distinct()
                    .orEmpty()
                if (choices.isNotEmpty()) put("enum", JsonArray(choices))
            }

            "object" -> {
                val sourceProperties = expanded["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val normalizedProperties = buildJsonObject {
                    sourceProperties.forEach { (name, propertySchema) ->
                        val propertyObject = propertySchema as? JsonObject ?: JsonObject(emptyMap())
                        put(
                            name,
                            normalizeGeminiSchema(
                                source = propertyObject,
                                definitions = definitions,
                                resolvingRefs = resolvingRefs,
                                depth = depth + 1,
                            )
                        )
                    }
                }
                put("properties", normalizedProperties)

                val requiredNames = (expanded["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it in normalizedProperties }
                    ?.distinct()
                    .orEmpty()
                if (requiredNames.isNotEmpty()) {
                    put("required", buildJsonArray {
                        requiredNames.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }

            "array" -> {
                val items = expanded["items"] as? JsonObject ?: JsonObject(emptyMap())
                put(
                    "items",
                    normalizeGeminiSchema(
                        source = items,
                        definitions = definitions,
                        resolvingRefs = resolvingRefs,
                        depth = depth + 1,
                    )
                )
                copyNumericKeyword(expanded, "minItems")?.let { put("minItems", it) }
                copyNumericKeyword(expanded, "maxItems")?.let { put("maxItems", it) }
            }

            "integer", "number" -> {
                copyNumericKeyword(expanded, "minimum")?.let { put("minimum", it) }
                copyNumericKeyword(expanded, "maximum")?.let { put("maximum", it) }
            }
        }
    }
}

private fun expandSchema(
    source: JsonObject,
    definitions: JsonObject,
    resolvingRefs: Set<String>,
    depth: Int,
): JsonObject {
    if (depth >= MAX_GEMINI_SCHEMA_DEPTH) return minimalSchema(inferSchemaType(source))
    val reference = (source["\$ref"] as? JsonPrimitive)?.contentOrNull
        ?: (source["ref"] as? JsonPrimitive)?.contentOrNull
    var expanded = if (reference != null && reference !in resolvingRefs) {
        resolveLocalReference(reference, definitions)?.let { target ->
            mergeSchemaObjects(
                normalizeGeminiSchema(
                    source = target,
                    definitions = definitions,
                    resolvingRefs = resolvingRefs + reference,
                    depth = depth + 1,
                ),
                JsonObject(source.filterKeys { it != "\$ref" && it != "ref" })
            )
        } ?: JsonObject(source.filterKeys { it != "\$ref" && it != "ref" })
    } else {
        JsonObject(source.filterKeys { it != "\$ref" && it != "ref" })
    }

    val allOf = expanded["allOf"] as? JsonArray
    if (!allOf.isNullOrEmpty()) {
        expanded = allOf
            .mapNotNull { it as? JsonObject }
            .fold(JsonObject(expanded.filterKeys { it != "allOf" })) { result, item ->
                mergeSchemaObjects(result, expandSchema(item, definitions, resolvingRefs, depth + 1))
            }
    }

    val alternatives = (expanded["anyOf"] as? JsonArray)
        ?: (expanded["oneOf"] as? JsonArray)
    val concreteAlternative = alternatives
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { inferSchemaType(it) != "null" }
    if (concreteAlternative != null) {
        expanded = mergeSchemaObjects(
            expandSchema(concreteAlternative, definitions, resolvingRefs, depth + 1),
            JsonObject(expanded.filterKeys { it != "anyOf" && it != "oneOf" })
        )
    }

    return expanded
}

private fun resolveLocalReference(reference: String, definitions: JsonObject): JsonObject? {
    if (reference == "#") return definitions
    if (!reference.startsWith("#/")) return null
    var node: JsonElement = definitions
    for (token in reference.removePrefix("#/").split('/')) {
        val key = token.replace("~1", "/").replace("~0", "~")
        node = when (val current = node) {
            is JsonObject -> current[key]
            is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        } ?: return null
    }
    return node as? JsonObject
}

private fun mergeSchemaObjects(base: JsonObject, overlay: JsonObject): JsonObject {
    val merged = base.toMutableMap()
    overlay.forEach { (key, value) ->
        when (key) {
            "properties" -> {
                val baseProperties = merged[key] as? JsonObject ?: JsonObject(emptyMap())
                val overlayProperties = value as? JsonObject ?: JsonObject(emptyMap())
                merged[key] = JsonObject(baseProperties + overlayProperties)
            }

            "required" -> {
                val required = ((merged[key] as? JsonArray).orEmpty() + (value as? JsonArray).orEmpty())
                    .distinct()
                merged[key] = JsonArray(required)
            }

            else -> merged[key] = value
        }
    }
    return JsonObject(merged)
}

private fun inferSchemaType(schema: JsonObject): String {
    val rawType = schema["type"]
    val declaredType = when (rawType) {
        is JsonPrimitive -> rawType.contentOrNull
        is JsonArray -> rawType
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.lowercase() != "null" }

        else -> null
    }?.lowercase()

    if (declaredType in setOf("string", "number", "integer", "boolean", "object", "array", "null")) {
        return declaredType!!
    }
    if (schema["properties"] is JsonObject) return "object"
    if (schema["items"] != null) return "array"

    val value = schema["const"] ?: schema["default"]
    if (value is JsonPrimitive) {
        if (value.isString) return "string"
        value.content.toLongOrNull()?.let { return "integer" }
        value.content.toDoubleOrNull()?.let { return "number" }
        if (value.content == "true" || value.content == "false") return "boolean"
    }

    val enumValue = (schema["enum"] as? JsonArray)?.firstOrNull()
    if (enumValue is JsonPrimitive) {
        if (enumValue.isString) return "string"
        enumValue.content.toLongOrNull()?.let { return "integer" }
        enumValue.content.toDoubleOrNull()?.let { return "number" }
        if (enumValue.content == "true" || enumValue.content == "false") return "boolean"
    }

    return "string"
}

private fun minimalSchema(type: String): JsonObject = buildJsonObject {
    val safeType = type.takeUnless { it == "null" } ?: "string"
    put("type", safeType)
    when (safeType) {
        "object" -> put("properties", JsonObject(emptyMap()))
        "array" -> put("items", buildJsonObject { put("type", "string") })
    }
}

private fun copyNumericKeyword(schema: JsonObject, key: String): JsonPrimitive? {
    val value = schema[key] as? JsonPrimitive ?: return null
    val number = value.doubleOrNull?.takeIf { it.isFinite() } ?: return null
    if (value.isString) return null
    if (key.endsWith("Items") && (number < 0 || number % 1.0 != 0.0)) return null
    return value
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
