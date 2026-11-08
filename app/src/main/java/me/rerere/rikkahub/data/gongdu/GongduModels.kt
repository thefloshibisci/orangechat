package me.rerere.rikkahub.data.gongdu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

@Serializable
data class GongduSetting(
    val serverId: Uuid? = null,
    val targetConversationId: Uuid? = null,
    val doorbellEnabled: Boolean = false,
    val inbox: GongduInbox = GongduInbox(),
)

@Serializable
data class GongduEvent(val id: String, val summary: String, val acknowledged: Boolean = false)

@Serializable
data class GongduInbox(
    val initialized: Boolean = false,
    val events: List<GongduEvent> = emptyList(),
    val unreadCount: Int? = null,
    val supported: Boolean = false,
    val refreshedAt: Long = 0,
)

data class GongduFeed(
    val id: String,
    val title: String,
    val token: String,
    val author: String = "",
    val cover: String = "",
)

data class GongduNote(val feed: GongduFeed, val content: String, val raw: JsonObject) {
    val body: JsonObject get() = raw.obj("note") ?: raw
    val images: List<GongduImage> get() = GongduCodec.images(body).ifEmpty {
        listOfNotNull(GongduToolResultPolicy.normalizedImageUrl(feed.cover)?.let { GongduImage(1, it) })
    }
}

data class GongduImage(val index: Int, val url: String, val width: Int = 0, val height: Int = 0)

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.str(vararg keys: String): String = keys.firstNotNullOfOrNull {
    (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}.orEmpty()

/** Upstream versions wrap payloads differently. Never interpret plain success text as a note. */
object GongduCodec {
    fun imageList(note: JsonObject): JsonArray =
        (note["imageList"] ?: note["image_list"] ?: note["images"]) as? JsonArray ?: JsonArray(emptyList())

    /** Same ordered CDN images for the reader, chat sharing, and model paging. */
    fun images(note: JsonObject): List<GongduImage> = imageList(note).mapIndexedNotNull { index, item ->
        val obj = item as? JsonObject
        val candidates = if (obj != null) sequenceOf(obj.str("urlDefault", "url_default"), obj.str("url"), obj.str("urlPre", "url_pre"))
            .plus(((obj["infoList"] ?: obj["info_list"]) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("url") })
        else sequenceOf((item as? JsonPrimitive)?.contentOrNull.orEmpty())
        val url = candidates.mapNotNull(GongduToolResultPolicy::normalizedImageUrl).firstOrNull()
            ?: return@mapIndexedNotNull null
        GongduImage(index + 1, url, (obj?.get("width") as? JsonPrimitive)?.intOrNull ?: 0,
            (obj?.get("height") as? JsonPrimitive)?.intOrNull ?: 0)
    }

    fun payload(text: String): JsonElement {
        runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let {
            if (it is JsonObject || it is JsonArray) return it
        }
        val start = text.indexOfFirst { it == '{' || it == '[' }
        require(start >= 0) { "小红书服务未返回可识别的数据" }
        val end = text.indexOfLast { it == '}' || it == ']' }
        require(end > start) { "小红书服务数据不完整" }
        return Json.parseToJsonElement(text.substring(start, end + 1))
    }

    fun unwrap(value: JsonElement): JsonElement {
        val objectValue = value as? JsonObject ?: return value
        val data = objectValue["data"]
        return when (data) {
            is JsonObject, is JsonArray -> data
            is JsonPrimitive -> runCatching { payload(data.content) }.getOrDefault(value)
            else -> value
        }
    }

    fun feed(item: JsonObject): GongduFeed {
        val card = item.obj("noteCard") ?: item.obj("note_card") ?: item
        val cover = card.obj("cover")
        val firstImage = (card["imageList"] as? JsonArray)?.firstOrNull() as? JsonObject
        return GongduFeed(
            id = item.str("id", "feed_id", "noteId", "note_id").ifBlank { card.str("noteId", "id") },
            title = card.str("displayTitle", "title", "display_title").ifBlank { "未命名笔记" },
            token = item.str("xsecToken", "xsec_token", "feed_xsec_token").ifBlank { card.str("xsecToken", "xsec_token") },
            author = card.obj("user")?.str("nickname", "nickName").orEmpty(),
            cover = cover?.str("urlDefault", "url", "urlPre").orEmpty()
                .ifBlank { firstImage?.str("urlDefault", "url").orEmpty() },
        )
    }

    fun feeds(value: JsonElement): List<GongduFeed> {
        val unwrapped = unwrap(value)
        val list = unwrapped as? JsonArray ?: (unwrapped as? JsonObject)?.let {
            (it["feeds"] ?: it["items"] ?: it["list"]) as? JsonArray
        } ?: error("服务未返回笔记列表")
        return list.mapNotNull { it as? JsonObject }.map(::feed).filter { it.id.isNotBlank() }
    }

    fun note(value: JsonElement, fallback: GongduFeed): GongduNote {
        var root = unwrap(value) as? JsonObject ?: error("服务未返回笔记详情")
        repeat(3) { if (root.obj("note") == null && root.obj("data") != null) root = root.obj("data")!! }
        val note = root.obj("note") ?: root
        val parsed = feed(note)
        return GongduNote(parsed.copy(id = parsed.id.ifBlank { fallback.id },
            title = parsed.title.takeUnless { it == "未命名笔记" } ?: fallback.title,
            author = parsed.author.ifBlank { fallback.author },
            token = parsed.token.ifBlank { fallback.token }, cover = parsed.cover.ifBlank { fallback.cover }),
            note.str("desc", "content", "description"), root)
    }

    fun events(value: JsonElement): List<GongduEvent> {
        val body = unwrap(value)
        val list = body as? JsonArray ?: (body as? JsonObject)?.let {
            (it["notifications"] ?: it["items"] ?: it["list"]) as? JsonArray
        } ?: error("服务未返回通知列表")
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("notification_id", "id", "comment_id")
            if (id.isBlank()) return@mapNotNull null
            val from = obj.obj("from")?.str("nickname", "nickName").orEmpty()
            val content = obj.str("comment_text", "content", "comment", "title", "desc", "feed_title")
            GongduEvent(id, listOf(from, content).filter(String::isNotBlank).joinToString("：").take(2000).ifBlank { "收到新的小红书互动" })
        }
    }

    /** Merge by event ID, not unread-count deltas. Seed old notifications without waking the chat. */
    fun mergeInbox(old: GongduInbox, incoming: List<GongduEvent>, now: Long): GongduInbox {
        val known = old.events.associateBy { it.id }
        val combined = old.events + incoming.distinctBy { it.id }.filter { it.id !in known }
            .map { it.copy(acknowledged = !old.initialized) }
        val pending = combined.filterNot { it.acknowledged }
        require(pending.size <= 256) { "待处理消息较多，请先处理消息再刷新" }
        val retained = combined.filter { it.acknowledged }.takeLast(256 - pending.size) + pending
        return old.copy(initialized = true, events = retained, supported = true, refreshedAt = now)
    }
}
