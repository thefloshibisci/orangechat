/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.localdata.AuthorKind
import me.rerere.rikkahub.plugin.localdata.LocalAuthorDto
import me.rerere.rikkahub.plugin.localdata.LocalChatSnapshotDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentDto
import me.rerere.rikkahub.plugin.localdata.LocalCommentPageDto
import me.rerere.rikkahub.plugin.localdata.LocalDataCursorCodec
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalMediaDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDetailDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDto
import me.rerere.rikkahub.plugin.localdata.LocalPostPageDto

data class LocalMomentsMediaViews(
    val items: List<JsonObject>,
    val omitted: Boolean,
    val omittedReason: String?,
)

data class LocalMomentsListData(
    val items: List<JsonObject>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsGetData(
    val data: JsonObject,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsCommentsData(
    val items: List<JsonObject>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val truncated: Boolean,
    val truncationReason: String?,
)

data class LocalMomentsChatExcerptPage(
    val data: JsonObject,
    val shownMessages: Int,
    val truncated: Boolean,
    val truncationReason: String?,
)

/** Pure JSON projection layer. It intentionally never exposes a filesystem path or an original URL. */
object LocalMomentsOutput {
    private const val POST_OVERHEAD_CHARS = 120
    private const val COMMENT_OVERHEAD_CHARS = 90
    private const val MEDIA_VIEW_COST_CHARS = 130
    private const val MESSAGE_OVERHEAD_CHARS = 60

    fun envelope(
        tool: String,
        data: JsonObject,
        budget: LocalMomentsBudget,
        truncated: Boolean,
        truncationReason: String?,
    ): String {
        val payload = buildJsonObject {
            put("ok", true)
            put("tool", tool)
            put("readOnly", true)
            put("budget", budget.status())
            if (truncated) {
                put("truncated", true)
                put("truncationReason", truncationReason ?: "output_budget")
            }
            put("data", data)
        }.toString()
        return hardLimit(payload, tool)
    }

    /** Last-resort bound. It stays valid JSON and says plainly that details were dropped. */
    fun hardLimit(payload: String, tool: String): String {
        if (payload.length <= LocalMomentsLimits.MAX_OUTPUT_CHARS) return payload
        return buildJsonObject {
            put("ok", true)
            put("tool", tool)
            put("readOnly", true)
            put("truncated", true)
            put("truncationReason", "output_budget")
            put("data", buildJsonObject {
                put("omitted", true)
                put("note", "本次结果超过硬上限，已丢弃明细；请减少 limit 或翻页后重试")
            })
        }.toString()
    }

    fun listData(
        page: LocalPostPageDto,
        bodyChars: Int,
        budget: LocalMomentsBudget,
        includeMedia: Boolean,
    ): LocalMomentsListData {
        val items = mutableListOf<JsonObject>()
        var lastShown: LocalPostDto? = null
        var stoppedByBudget = false
        var bodyTruncated = false
        var mediaTruncated = false

        for (post in page.items) {
            if (!budget.text.canAfford(POST_OVERHEAD_CHARS)) {
                stoppedByBudget = true
                break
            }
            budget.text.charge(POST_OVERHEAD_CHARS)
            val body = budget.text.take(post.body, bodyChars)
            bodyTruncated = bodyTruncated || body.truncated
            val media = mediaViews(post.media, budget.text, includeMedia, "media_not_granted")
            mediaTruncated = mediaTruncated ||
                (media.omitted && media.omittedReason != "media_not_granted")
            items += postView(post, body, media)
            lastShown = post
        }

        val truncated = page.truncated || bodyTruncated || stoppedByBudget || mediaTruncated
        val reason = when {
            stoppedByBudget -> "output_budget"
            bodyTruncated -> "body_limit"
            mediaTruncated -> "media_limit"
            page.truncated -> "store_budget"
            else -> null
        }
        return LocalMomentsListData(
            items = items,
            nextCursor = nextCursor(page, lastShown, stoppedByBudget),
            hasMore = page.hasMore || stoppedByBudget,
            truncated = truncated,
            truncationReason = reason,
        )
    }

    fun getData(
        detail: LocalPostDetailDto,
        bodyChars: Int,
        budget: LocalMomentsBudget,
        includeMedia: Boolean,
    ): LocalMomentsGetData {
        budget.text.charge(POST_OVERHEAD_CHARS)
        val body = budget.text.take(detail.post.body, bodyChars)
        val media = mediaViews(detail.post.media, budget.text, includeMedia, "media_not_granted")
        val data = buildJsonObject {
            put("post", postView(detail.post, body, media))
            put("commentCount", detail.post.commentCount)
            put("commentPreviewOmitted", true)
            put("commentPreviewHint", "moments_comments")
        }
        val reason = when {
            body.truncated -> "body_limit"
            media.omitted && media.omittedReason != "media_not_granted" -> "media_limit"
            else -> null
        }
        return LocalMomentsGetData(data = data, truncated = reason != null, truncationReason = reason)
    }

    fun commentsData(
        page: LocalCommentPageDto,
        budget: LocalMomentsBudget,
    ): LocalMomentsCommentsData {
        val items = mutableListOf<JsonObject>()
        var stopped = false
        var bodyTruncated = false
        var lastShown: LocalCommentDto? = null
        for (comment in page.items) {
            if (!budget.text.canAfford(COMMENT_OVERHEAD_CHARS)) {
                stopped = true
                break
            }
            budget.text.charge(COMMENT_OVERHEAD_CHARS)
            val body = budget.text.take(comment.body, LocalMomentsLimits.MAX_COMMENT_BODY_CHARS)
            bodyTruncated = bodyTruncated || body.truncated
            items += commentView(comment, body)
            lastShown = comment
        }
        val truncated = stopped || bodyTruncated
        return LocalMomentsCommentsData(
            items = items,
            nextCursor = if (stopped && lastShown != null) {
                LocalDataCursorCodec.encode(lastShown.createdAt, lastShown.id)
            } else {
                page.nextCursor
            },
            hasMore = page.hasMore || stopped,
            truncated = truncated,
            truncationReason = when {
                stopped -> "output_budget"
                bodyTruncated -> "comment_body_limit"
                else -> null
            },
        )
    }

    fun mediaViews(
        media: List<LocalMediaDto>,
        budget: LocalMomentsTextBudget,
        includeMedia: Boolean,
        notGrantedReason: String,
    ): LocalMomentsMediaViews {
        if (!includeMedia) {
            return LocalMomentsMediaViews(
                items = emptyList(),
                omitted = media.isNotEmpty(),
                omittedReason = notGrantedReason,
            )
        }
        val items = mutableListOf<JsonObject>()
        var reason: String? = null
        for ((index, item) in media.withIndex()) {
            if (index >= LocalMomentsLimits.MAX_MEDIA_METADATA_PER_PAGE) {
                reason = "metadata_limit"
                break
            }
            if (!budget.canAfford(MEDIA_VIEW_COST_CHARS)) {
                reason = "output_budget"
                break
            }
            budget.charge(MEDIA_VIEW_COST_CHARS)
            items += mediaView(item)
        }
        return LocalMomentsMediaViews(
            items = items,
            omitted = items.size < media.size,
            omittedReason = reason,
        )
    }

    fun mediaView(media: LocalMediaDto): JsonObject = buildJsonObject {
        put("resourceId", media.resourceId)
        put("mime", media.mime)
        put("width", media.width)
        put("height", media.height)
        put("byteSize", media.byteSize)
        // Controlled host URL only. The original URL is deliberately not exposed to the model.
        put("thumbUrl", media.thumbUrl)
        put("available", media.available)
    }

    fun mediaGetData(
        media: LocalMediaDto,
        budget: LocalMomentsBudget,
        imageAttached: Boolean,
        imageOmittedReason: String?,
    ): JsonObject {
        budget.text.charge(MEDIA_VIEW_COST_CHARS)
        return buildJsonObject {
            put("media", mediaView(media))
            put("image", buildJsonObject {
                put("attached", imageAttached)
                put("variant", "thumbnail")
                put("maxEdgePx", LocalMomentsLimits.THUMBNAIL_MAX_PX)
                put("maxAttached", LocalMomentsLimits.MAX_ATTACHED_IMAGES_PER_CALL)
                if (!imageAttached && imageOmittedReason != null) {
                    put("omittedReason", imageOmittedReason)
                }
            })
        }
    }

    fun chatExcerptData(
        postId: String,
        snapshot: LocalChatSnapshotDto,
        start: Int,
        limit: Int,
        budget: LocalMomentsBudget,
        includeMediaIds: Boolean,
    ): LocalMomentsChatExcerptPage {
        val all = snapshot.messages
        val slice = all.drop(start).take(limit)
        val messages = mutableListOf<JsonObject>()
        var stopped = false
        var textTruncated = false
        var excerptChars = 0

        for (message in slice) {
            if (excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS ||
                !budget.text.canAfford(MESSAGE_OVERHEAD_CHARS)
            ) {
                stopped = true
                break
            }
            val remainingTotal = LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS - excerptChars
            budget.text.charge(MESSAGE_OVERHEAD_CHARS)
            val text = budget.text.take(
                message.text,
                minOf(LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGE_CHARS, remainingTotal),
            )
            textTruncated = textTruncated || text.truncated
            excerptChars += text.text.length
            messages += buildJsonObject {
                put("role", message.role)
                put("name", message.name)
                put("text", text.text)
                put("textTruncated", text.truncated)
                put("mediaCount", message.media.size)
                if (includeMediaIds && message.media.isNotEmpty()) {
                    put("mediaIds", JsonArray(
                        message.media
                            .filter { LocalDataValidation.isSafeMediaId(it) }
                            .take(LocalMomentsLimits.MAX_MEDIA_METADATA_PER_PAGE)
                            .map { JsonPrimitive(it) },
                    ))
                } else if (message.media.isNotEmpty()) {
                    put("mediaOmittedReason", "media_not_granted")
                }
                message.avatarEmoji?.let { put("avatarEmoji", it) }
            }
        }

        val consumed = start + messages.size
        val hasMore = consumed < all.size
        val truncated = stopped || textTruncated || excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS
        val data = buildJsonObject {
            put("postId", postId)
            snapshot.title?.let { put("title", it) }
            put("totalMessages", all.size)
            put("start", start)
            put("count", messages.size)
            put("hasMore", hasMore)
            if (hasMore) put("nextStart", consumed)
            put("messages", JsonArray(messages))
            put("imagePolicy", "text-only; call moments_media_get for a specific thumbnail")
        }
        return LocalMomentsChatExcerptPage(
            data = data,
            shownMessages = messages.size,
            truncated = truncated,
            truncationReason = when {
                stopped -> "output_budget"
                textTruncated -> "message_text_limit"
                excerptChars >= LocalMomentsLimits.MAX_EXCERPT_TOTAL_CHARS -> "excerpt_total_limit"
                else -> null
            },
        )
    }

    fun postView(
        post: LocalPostDto,
        body: LocalMomentsLimitedText,
        media: LocalMomentsMediaViews,
    ): JsonObject = buildJsonObject {
        put("id", post.id)
        put("author", authorView(post.author))
        put("createdAt", post.createdAt)
        put("body", body.text)
        put("bodyTruncated", body.truncated)
        put("bodyOriginalChars", body.originalChars)
        put("commentCount", post.commentCount)
        put("likeCount", post.likeCount)
        put("likedByMe", post.likedByMe)
        put("media", buildJsonObject {
            put("count", post.media.size)
            put("countKnown", !post.mediaOmitted)
            put("omitted", media.omitted || post.mediaOmitted)
            if (media.omitted || post.mediaOmitted) {
                put(
                    "omittedReason",
                    media.omittedReason ?: if (post.mediaOmitted) "media_not_granted" else "unknown",
                )
            }
            put("items", JsonArray(media.items))
        })
        // Chat excerpts are never part of list/get. The model must opt in explicitly, and even then
        // only the controlled excerpt tool may read them.
        put("chatExcerpt", buildJsonObject {
            put("omitted", true)
            put("hint", LocalMomentsContract.TOOL_CHAT_EXCERPT_GET)
        })
    }

    fun commentView(comment: LocalCommentDto, body: LocalMomentsLimitedText): JsonObject = buildJsonObject {
        put("id", comment.id)
        put("author", authorView(comment.author))
        put("createdAt", comment.createdAt)
        put("body", body.text)
        put("bodyTruncated", body.truncated)
        put("bodyOriginalChars", body.originalChars)
        comment.replyToId?.let { put("replyToId", it) }
    }

    fun authorView(author: LocalAuthorDto): JsonObject = buildJsonObject {
        put("kind", when (author.kind) {
            AuthorKind.USER -> "user"
            AuthorKind.ASSISTANT -> "assistant"
        })
        put("id", author.id)
        put("name", author.name)
    }

    private fun nextCursor(
        page: LocalPostPageDto,
        lastShown: LocalPostDto?,
        stoppedByBudget: Boolean,
    ): String? {
        if (!stoppedByBudget) return page.nextCursor
        // The store cursor points past the whole fetched page. When output budget cut the page
        // short, resume right after the last shown row so nothing is silently skipped.
        return lastShown?.let { LocalDataCursorCodec.encode(it.createdAt, it.id) }
    }
}
