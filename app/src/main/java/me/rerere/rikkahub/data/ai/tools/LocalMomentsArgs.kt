/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.plugin.localdata.LocalCommentOrder
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataCursorCodec
import me.rerere.rikkahub.plugin.localdata.LocalDataException
import me.rerere.rikkahub.plugin.localdata.LocalDataRequestParser
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation

class LocalMomentsArgumentException(
    val code: String,
    override val message: String,
) : Exception(message)

data class LocalMomentsFilterArgs(
    val authorId: String? = null,
    val authorKind: String? = null,
    val type: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
)

data class LocalMomentsListArgs(
    val cursor: String?,
    val limit: Int,
    val bodyChars: Int,
    val filter: LocalMomentsFilterArgs,
)

data class LocalMomentsSearchArgs(
    val query: String,
    val cursor: String?,
    val limit: Int,
    val bodyChars: Int,
    val filter: LocalMomentsFilterArgs,
)

data class LocalMomentsGetArgs(
    val postId: String,
    val bodyChars: Int,
)

data class LocalMomentsCommentsArgs(
    val postId: String,
    val cursor: String?,
    val limit: Int,
    val order: LocalCommentOrder,
)

data class LocalMomentsMediaArgs(val resourceId: String)

data class LocalMomentsChatExcerptArgs(
    val postId: String,
    val start: Int,
    val limit: Int,
)

/** Pure parameter parser. Identity keys are rejected; unknown keys are rejected (no silent drops). */
object LocalMomentsArgs {
    private val LIST_KEYS = setOf(
        "cursor", "limit", "bodyChars", "authorId", "authorKind", "type", "dateFrom", "dateTo",
    )
    private val SEARCH_KEYS = LIST_KEYS + "query"
    private val GET_KEYS = setOf("postId", "bodyChars")
    private val COMMENTS_KEYS = setOf("postId", "cursor", "limit", "order")
    private val MEDIA_KEYS = setOf("resourceId")
    private val CHAT_EXCERPT_KEYS = setOf("postId", "start", "limit")

    fun publish(element: JsonElement, maxChars: Int): String {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, setOf("body"))
        if ((obj["body"] as? JsonPrimitive)?.isString != true) invalid("body 必须是字符串")
        val body = requiredString(obj, "body", maxChars).trim()
        if (body.isEmpty()) invalid("body 不能为空")
        return body
    }

    fun list(element: JsonElement): LocalMomentsListArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, LIST_KEYS)
        return LocalMomentsListArgs(
            cursor = cursor(obj),
            limit = int(obj, "limit", LocalMomentsLimits.DEFAULT_PAGE_SIZE, 1, LocalMomentsLimits.MAX_PAGE_SIZE),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS,
                80,
                LocalMomentsLimits.MAX_LIST_BODY_CHARS,
            ),
            filter = filter(obj),
        )
    }

    fun search(element: JsonElement): LocalMomentsSearchArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, SEARCH_KEYS)
        val query = requiredString(obj, "query", LocalDataApi.MAX_SEARCH_CHARS)
        return LocalMomentsSearchArgs(
            query = query,
            cursor = cursor(obj),
            limit = int(obj, "limit", LocalMomentsLimits.DEFAULT_PAGE_SIZE, 1, LocalMomentsLimits.MAX_PAGE_SIZE),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS,
                80,
                LocalMomentsLimits.MAX_LIST_BODY_CHARS,
            ),
            filter = filter(obj),
        )
    }

    fun get(element: JsonElement): LocalMomentsGetArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, GET_KEYS)
        return LocalMomentsGetArgs(
            postId = requiredRecordId(obj, "postId"),
            bodyChars = int(
                obj,
                "bodyChars",
                LocalMomentsLimits.DEFAULT_GET_BODY_CHARS,
                120,
                LocalMomentsLimits.MAX_GET_BODY_CHARS,
            ),
        )
    }

    fun comments(element: JsonElement): LocalMomentsCommentsArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, COMMENTS_KEYS)
        val order = when (val raw = optionalString(obj, "order", 16)) {
            null -> LocalCommentOrder.NEWEST
            "newest" -> LocalCommentOrder.NEWEST
            "oldest" -> LocalCommentOrder.OLDEST
            else -> invalid("order 只能是 newest 或 oldest")
        }
        return LocalMomentsCommentsArgs(
            postId = requiredRecordId(obj, "postId"),
            cursor = cursor(obj),
            limit = int(
                obj,
                "limit",
                LocalMomentsLimits.DEFAULT_COMMENT_LIMIT,
                1,
                LocalMomentsLimits.MAX_COMMENT_LIMIT,
            ),
            order = order,
        )
    }

    fun media(element: JsonElement): LocalMomentsMediaArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, MEDIA_KEYS)
        val resourceId = requiredString(obj, "resourceId", 80)
        if (!LocalDataValidation.isSafeMediaId(resourceId)) invalid("resourceId 不是受控媒体 id")
        return LocalMomentsMediaArgs(resourceId)
    }

    fun chatExcerpt(element: JsonElement): LocalMomentsChatExcerptArgs {
        val obj = requireObject(element)
        rejectIdentity(obj)
        rejectUnknown(obj, CHAT_EXCERPT_KEYS)
        return LocalMomentsChatExcerptArgs(
            postId = requiredRecordId(obj, "postId"),
            start = int(obj, "start", 0, 0, LocalDataApi.MAX_CHAT_MESSAGES - 1),
            limit = int(
                obj,
                "limit",
                LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGES,
                1,
                LocalMomentsLimits.MAX_EXCERPT_MESSAGES,
            ),
        )
    }

    private fun requireObject(element: JsonElement): JsonObject =
        element as? JsonObject ?: invalid("参数必须是 JSON 对象")

    private fun rejectIdentity(obj: JsonObject) {
        try {
            LocalDataRequestParser.requireNoIdentityFields(obj)
        } catch (_: LocalDataException) {
            throw LocalMomentsArgumentException(
                "identity_not_allowed",
                "助手身份由宿主决定，参数里不能出现 assistantId/actor/viewer 等身份字段",
            )
        }
    }

    private fun rejectUnknown(obj: JsonObject, allowed: Set<String>) {
        val unknown = obj.keys - allowed
        if (unknown.isNotEmpty()) {
            throw LocalMomentsArgumentException(
                "unknown_parameter",
                "不支持的参数：${unknown.sorted().joinToString(",")}",
            )
        }
    }

    private fun filter(obj: JsonObject): LocalMomentsFilterArgs {
        val authorKind = optionalString(obj, "authorKind", 16)?.also {
            if (it !in setOf("user", "assistant")) invalid("authorKind 只能是 user 或 assistant")
        }
        val type = optionalString(obj, "type", 24)?.also {
            if (it !in setOf("text", "image", "chat_snapshot")) {
                invalid("type 只能是 text、image 或 chat_snapshot")
            }
        }
        return LocalMomentsFilterArgs(
            authorId = optionalString(obj, "authorId", 80)?.also {
                if (!LocalDataValidation.isSafeRecordId(it)) invalid("authorId 格式不合法")
            },
            authorKind = authorKind,
            type = type,
            dateFrom = optionalEpochMillis(obj, "dateFrom"),
            dateTo = optionalEpochMillis(obj, "dateTo"),
        )
    }

    private fun cursor(obj: JsonObject): String? {
        val raw = optionalString(obj, "cursor", 160) ?: return null
        if (LocalDataCursorCodec.decode(raw) == null) {
            throw LocalMomentsArgumentException("invalid_cursor", "cursor 无效或已过期，请重新从第一页读取")
        }
        return raw
    }

    private fun requiredRecordId(obj: JsonObject, name: String): String {
        val value = requiredString(obj, name, 80)
        if (!LocalDataValidation.isSafeRecordId(value)) invalid("$name 格式不合法")
        return value
    }

    private fun requiredString(obj: JsonObject, name: String, maxChars: Int): String {
        val value = optionalString(obj, name, maxChars) ?: invalid("缺少参数 $name")
        if (value.isBlank()) invalid("$name 不能为空")
        return value
    }

    private fun optionalString(obj: JsonObject, name: String, maxChars: Int): String? {
        val element = obj[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是字符串")
        val value = primitive.content
        if (value.length > maxChars) invalid("$name 超过 $maxChars 字符上限")
        return value
    }

    private fun int(obj: JsonObject, name: String, default: Int, min: Int, max: Int): Int {
        val element = obj[name] ?: return default
        if (element is JsonNull) return default
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是整数")
        val value = primitive.intOrNull ?: invalid("$name 必须是整数")
        if (value !in min..max) invalid("$name 必须在 $min..$max 之间")
        return value
    }

    private fun optionalEpochMillis(obj: JsonObject, name: String): Long? {
        val element = obj[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: invalid("$name 必须是毫秒时间戳")
        val value = primitive.longOrNull ?: invalid("$name 必须是毫秒时间戳")
        if (value < 0) invalid("$name 不能为负数")
        return value
    }

    private fun invalid(message: String): Nothing =
        throw LocalMomentsArgumentException("invalid_argument", message)
}
