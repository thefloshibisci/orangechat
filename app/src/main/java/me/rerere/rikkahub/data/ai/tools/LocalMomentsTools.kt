/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.plugin.localdata.LocalAssistantDirectory
import me.rerere.rikkahub.plugin.localdata.LocalCommentOrder
import me.rerere.rikkahub.plugin.localdata.LocalCommentPageDto
import me.rerere.rikkahub.plugin.localdata.LocalDataActor
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataAssistantRef
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataEnvelope
import me.rerere.rikkahub.plugin.localdata.LocalDataException
import me.rerere.rikkahub.plugin.localdata.LocalDataGrant
import me.rerere.rikkahub.plugin.localdata.LocalDataJson
import me.rerere.rikkahub.plugin.localdata.LocalDataMethods
import me.rerere.rikkahub.plugin.localdata.LocalDataSession
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localdata.LocalMediaDto
import me.rerere.rikkahub.plugin.localdata.LocalPostCreateResultDto
import me.rerere.rikkahub.plugin.localdata.LocalPostDetailDto
import me.rerere.rikkahub.plugin.localdata.LocalPostPageDto
import me.rerere.rikkahub.plugin.localtasks.LocalTaskIds
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettingsStore
import me.rerere.rikkahub.plugin.localtasks.SharedPreferencesLocalTaskStore
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo

private data class LocalMomentsGrantSnapshot(
    val pluginInstalled: Boolean,
    val pluginEnabled: Boolean,
    val pluginLoadError: String?,
    val declaredCapabilities: Set<String>,
    val approvedCapabilities: Set<String>,
)

private data class LocalMomentsRuntime(
    val access: LocalMomentsAccess,
    val snapshot: LocalMomentsAccessSnapshot,
    val assistantId: String,
    val assistantName: String,
    val directory: LocalAssistantDirectory,
    val grant: LocalDataGrant,
    val userDisplayName: String,
)

class LocalMomentsTools(
    private val context: Context,
    private val settingsFlow: StateFlow<Settings>,
    private val getPlugin: (String) -> PluginInfo?,
    private val imagesDir: () -> File,
) {
    /**
     * Build the tool set for one assistant turn. Returns an empty list when any native
     * gate fails, so the model never even sees a schema it cannot use.
     *
     * [budget] is the user-turn scoped budget supplied by ChatService when this surface is
     * rebuilt during one turn (tool loop, approval resume). Callers that cannot scope a turn may
     * omit it; a fresh one is then created for this build only.
     */
    fun build(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget = LocalMomentsBudget(),
        anchorUserId: String? = null,
    ): List<Tool> {
        if (invocationContext.isHeadless || invocationContext.callerAssistantId.isNullOrBlank()) {
            return emptyList()
        }
        val snapshot = currentSnapshot(invocationContext, settingsFlow.value)
        val access = LocalMomentsAccessPolicy.evaluate(snapshot)
        if (!access.allowed) return emptyList()

        val tools = mutableListOf<Tool>()
        tools += listTool(invocationContext, budget)
        tools += getTool(invocationContext, budget)
        tools += searchTool(invocationContext, budget)
        tools += commentsTool(invocationContext, budget)
        if (access.canReadMedia) {
            tools += mediaTool(invocationContext, budget)
        }
        if (access.canReadChatSnapshot) {
            tools += chatExcerptTool(invocationContext, budget)
        }
        val conversationId = invocationContext.callerConversationId
        if (access.canPublish && !conversationId.isNullOrBlank() && !anchorUserId.isNullOrBlank()) {
            tools += publishTool(invocationContext, budget, "$conversationId|$anchorUserId")
        }
        return tools
    }

    private fun publishTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
        turnKey: String,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_PUBLISH,
        description = "应用户要求，以当前助手身份发布一条本机朋友圈文字动态。用户和所有参与助手可见。成功后返回动态 ID。",
        parameters = { InputSchema.Obj(
            properties = buildJsonObject { put("body", stringParam("要发布的完整正文")) },
            required = listOf("body"),
        ) },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_PUBLISH) {
                val runtime = requireRuntime(invocationContext, needsPublish = true)
                val body = LocalMomentsArgs.publish(element, runtime.snapshot.taskSettings.maxOutputChars)
                budget.beginCall(LocalMomentsCallKind.PUBLISH)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val result = call(session, runtime, LocalDataMethods.CREATE, buildJsonObject {
                        put("body", body)
                        put("clientKey", LocalTaskIds.publishClientKey("$turnKey|$body"))
                    }).decode(LocalPostCreateResultDto.serializer())
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("ok", true)
                        put("postId", result.post.id)
                    }.toString()))
                }
            }
        },
    )

    private fun listTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_LIST,
        description = """
            读取本机朋友圈的动态（只读，不会发帖/评论/点赞）。
            默认返回最新 3 条可见动态的摘要；用 nextCursor + cursor 翻页读取旧动态。
            每条正文默认最多 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS} 字，截断会标记 bodyTruncated。
            不返回聊天记录卡片正文；需要时用 ${LocalMomentsContract.TOOL_CHAT_EXCERPT_GET}。
            图片只返回受控元数据（不返回原图）；需要看图用 ${LocalMomentsContract.TOOL_MEDIA_GET}。
            每轮最多 ${LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN} 次调用、${LocalMomentsLimits.MAX_PAGES_PER_TURN} 页。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_PAGE_SIZE}", 1, LocalMomentsLimits.MAX_PAGE_SIZE))
                    put("bodyChars", intParam("每条正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS}", 80, LocalMomentsLimits.MAX_LIST_BODY_CHARS))
                    put("authorId", stringParam("可选：只看某个作者（用户或助手 id）"))
                    put("authorKind", enumParam("可选：authorKind", listOf("user", "assistant")))
                    put("type", enumParam("可选：动态类型", listOf("text", "image", "chat_snapshot")))
                    put("dateFrom", longParam("可选：起始时间，Unix 毫秒"))
                    put("dateTo", longParam("可选：结束时间，Unix 毫秒"))
                },
                required = emptyList(),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_LIST) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.list(element)
                budget.beginCall(LocalMomentsCallKind.LIST)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.LIST,
                        params = buildJsonObject {
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("bodyChars", args.bodyChars)
                            put("commentPreview", 0)
                            putFilter(args.filter)
                        },
                    )
                    val page = envelope.decode(LocalPostPageDto.serializer())
                    val view = LocalMomentsOutput.listData(
                        page = page,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    val data = buildJsonObject {
                        put("items", JsonArray(view.items))
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_LIST,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun getTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_GET,
        description = """
            读取本机朋友圈中的一条动态（只读）。不返回评论列表，也不返回聊天记录卡片正文。
            正文默认最多 ${LocalMomentsLimits.DEFAULT_GET_BODY_CHARS} 字，超出会标记 bodyTruncated。
            需要评论用 ${LocalMomentsContract.TOOL_COMMENTS}；需要聊天卡片用 ${LocalMomentsContract.TOOL_CHAT_EXCERPT_GET}。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("动态 id（moments_list/search 返回的 id）"))
                    put("bodyChars", intParam("正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_GET_BODY_CHARS}", 120, LocalMomentsLimits.MAX_GET_BODY_CHARS))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_GET) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.get(element)
                budget.beginCall(LocalMomentsCallKind.GET)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.GET,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            put("bodyChars", args.bodyChars)
                            put("commentLimit", 0)
                            put("includeMedia", true)
                            put("includeChatSnapshot", false)
                        },
                    )
                    val detail = envelope.decode(LocalPostDetailDto.serializer())
                    val view = LocalMomentsOutput.getData(
                        detail = detail,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_GET,
                            data = view.data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun searchTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_SEARCH,
        description = """
            搜索本机朋友圈的旧动态（只读）。query 必填，最长 ${LocalDataApi.MAX_SEARCH_CHARS} 字。
            可按作者、日期、内容类型过滤；用 nextCursor + cursor 翻页。
            默认返回最新 3 条命中摘要，正文截断会明确标记。不会搜索私聊内容。
            每轮最多 ${LocalMomentsLimits.MAX_SEARCHES_PER_TURN} 次搜索、${LocalMomentsLimits.MAX_PAGES_PER_TURN} 页。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringParam("搜索关键词，最长 ${LocalDataApi.MAX_SEARCH_CHARS} 字"))
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_PAGE_SIZE}", 1, LocalMomentsLimits.MAX_PAGE_SIZE))
                    put("bodyChars", intParam("每条正文最多字符数，默认 ${LocalMomentsLimits.DEFAULT_LIST_BODY_CHARS}", 80, LocalMomentsLimits.MAX_LIST_BODY_CHARS))
                    put("authorId", stringParam("可选：只看某个作者（用户或助手 id）"))
                    put("authorKind", enumParam("可选：authorKind", listOf("user", "assistant")))
                    put("type", enumParam("可选：动态类型", listOf("text", "image", "chat_snapshot")))
                    put("dateFrom", longParam("可选：起始时间，Unix 毫秒"))
                    put("dateTo", longParam("可选：结束时间，Unix 毫秒"))
                },
                required = listOf("query"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_SEARCH) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.search(element)
                budget.beginCall(LocalMomentsCallKind.SEARCH)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.SEARCH,
                        params = buildJsonObject {
                            put("query", args.query)
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("bodyChars", args.bodyChars)
                            put("commentPreview", 0)
                            putFilter(args.filter)
                        },
                    )
                    val page = envelope.decode(LocalPostPageDto.serializer())
                    val view = LocalMomentsOutput.listData(
                        page = page,
                        bodyChars = args.bodyChars,
                        budget = budget,
                        includeMedia = runtime.access.canReadMedia,
                    )
                    val data = buildJsonObject {
                        put("query", args.query)
                        put("items", JsonArray(view.items))
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_SEARCH,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun commentsTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_COMMENTS,
        description = """
            读取一条本机朋友圈动态的评论（只读）。默认按最新优先返回 3 条，最多 ${LocalMomentsLimits.MAX_COMMENT_LIMIT} 条；
            用 nextCursor + cursor 继续翻旧评论。每条评论正文最多 ${LocalMomentsLimits.MAX_COMMENT_BODY_CHARS} 字，截断会标记。
            不返回未授权动态的评论。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("动态 id"))
                    put("cursor", stringParam("上一页返回的 nextCursor；省略表示第一页"))
                    put("limit", intParam("本页条数，默认 3，最大 ${LocalMomentsLimits.MAX_COMMENT_LIMIT}", 1, LocalMomentsLimits.MAX_COMMENT_LIMIT))
                    put("order", enumParam("排序：newest（默认）或 oldest", listOf("newest", "oldest")))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_COMMENTS) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.comments(element)
                budget.beginCall(LocalMomentsCallKind.COMMENTS)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.COMMENTS_LIST,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            args.cursor?.let { put("cursor", it) }
                            put("limit", args.limit)
                            put("order", if (args.order == LocalCommentOrder.NEWEST) "newest" else "oldest")
                        },
                    )
                    val page = envelope.decode(LocalCommentPageDto.serializer())
                    val view = LocalMomentsOutput.commentsData(page, budget)
                    val data = buildJsonObject {
                        put("postId", args.postId)
                        put("items", JsonArray(view.items))
                        put("commentCount", page.commentCount)
                        put("nextCursor", view.nextCursor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("hasMore", view.hasMore)
                        put("count", view.items.size)
                    }
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_COMMENTS,
                            data = data,
                            budget = budget,
                            truncated = view.truncated,
                            truncationReason = view.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun mediaTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_MEDIA_GET,
        description = """
            读取一条动态图片的受控元数据（只读）。默认只返回缩略图元数据与受控 thumbUrl，永不返回原图路径。
            只有宿主已授权 localdata.media、朋友圈图片聊天开关打开、且当前模型能看图时，才附带一张
            ${LocalMomentsLimits.THUMBNAIL_MAX_PX}px 以内的缩略图；否则 image.attached=false 并说明原因。
            不要声称看过未附带图像的内容。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("resourceId", stringParam("受控媒体 id（ldm_...，来自列表/详情的 media.items）"))
                },
                required = listOf("resourceId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_MEDIA_GET) {
                val runtime = requireRuntime(invocationContext)
                val args = LocalMomentsArgs.media(element)
                budget.beginCall(LocalMomentsCallKind.MEDIA)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.MEDIA_DESCRIBE,
                        params = buildJsonObject { put("resourceId", args.resourceId) },
                    )
                    val media = envelope.decode(LocalMediaDto.serializer())
                    val attachDenial = when {
                        !runtime.access.canReadMedia -> "media_not_granted"
                        !runtime.snapshot.taskSettings.normalized().allowImages -> "image_chat_not_allowed"
                        !runtime.snapshot.modelCanSeeImages -> "model_cannot_see_images"
                        else -> null
                    }
                    var attachedFile: File? = null
                    var attachReason = attachDenial
                    if (attachDenial == null && LocalMomentsLimits.MAX_ATTACHED_IMAGES_PER_CALL > 0) {
                        val thumbnail = session.media.openThumbnail(media.resourceId)
                        if (thumbnail == null || thumbnail.length <= 0L ||
                            thumbnail.length > LocalMomentsLimits.MAX_ATTACHED_IMAGE_BYTES
                        ) {
                            attachReason = "thumbnail_unavailable"
                        } else {
                            attachedFile = persistThumbnail(thumbnail.file, media.resourceId)
                            if (attachedFile == null) attachReason = "thumbnail_unavailable"
                        }
                    }
                    val data = LocalMomentsOutput.mediaGetData(
                        media = media,
                        budget = budget,
                        imageAttached = attachedFile != null,
                        imageOmittedReason = attachReason,
                    )
                    val text = LocalMomentsOutput.envelope(
                        tool = LocalMomentsContract.TOOL_MEDIA_GET,
                        data = data,
                        budget = budget,
                        truncated = false,
                        truncationReason = null,
                    )
                    buildList<UIMessagePart> {
                        add(UIMessagePart.Text(text))
                        attachedFile?.let { add(UIMessagePart.Image(it.toUri().toString())) }
                    }
                }
            }
        },
    )

    private fun chatExcerptTool(
        invocationContext: ToolInvocationContext,
        budget: LocalMomentsBudget,
    ): Tool = Tool(
        name = LocalMomentsContract.TOOL_CHAT_EXCERPT_GET,
        description = """
            显式读取一条动态内嵌的聊天记录卡片（只读）。这不是默认能力：moments_list/get 不会返回卡片正文。
            默认从第 0 条开始返回最多 10 条消息，每条最多 ${LocalMomentsLimits.DEFAULT_EXCERPT_MESSAGE_CHARS} 字；
            用 start + limit 翻页。返回的 imagePolicy 只是文字说明，图片需另调 ${LocalMomentsContract.TOOL_MEDIA_GET}。
            不返回系统提示词、工具结果、思考内容或未选中消息。
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("postId", stringParam("聊天记录卡片动态的 id"))
                    put("start", intParam("从第几条消息开始，默认 0", 0, LocalDataApi.MAX_CHAT_MESSAGES - 1))
                    put("limit", intParam("本页最多消息数，默认 10，最大 ${LocalMomentsLimits.MAX_EXCERPT_MESSAGES}", 1, LocalMomentsLimits.MAX_EXCERPT_MESSAGES))
                },
                required = listOf("postId"),
            )
        },
        execute = { element ->
            runTool(LocalMomentsContract.TOOL_CHAT_EXCERPT_GET) {
                val runtime = requireRuntime(invocationContext, needsChatSnapshot = true)
                val args = LocalMomentsArgs.chatExcerpt(element)
                budget.beginCall(LocalMomentsCallKind.CHAT_EXCERPT)?.let { throw toolDenied(it) }
                withSession(runtime) { session ->
                    val envelope = call(
                        session = session,
                        runtime = runtime,
                        method = LocalDataMethods.GET,
                        params = buildJsonObject {
                            put("postId", args.postId)
                            put("bodyChars", 0)
                            put("commentLimit", 0)
                            put("includeMedia", false)
                            put("includeChatSnapshot", true)
                        },
                    )
                    val detail = envelope.decode(LocalPostDetailDto.serializer())
                    val snapshot = detail.post.chatSnapshot
                    if (snapshot == null) {
                        throw LocalMomentsToolException(
                            "not_a_chat_snapshot",
                            "这条动态不是聊天记录卡片，或卡片正文已被隐藏",
                        )
                    }
                    val page = LocalMomentsOutput.chatExcerptData(
                        postId = args.postId,
                        snapshot = snapshot,
                        start = args.start,
                        limit = args.limit,
                        budget = budget,
                        includeMediaIds = runtime.access.canReadMedia,
                    )
                    listOf(UIMessagePart.Text(
                        LocalMomentsOutput.envelope(
                            tool = LocalMomentsContract.TOOL_CHAT_EXCERPT_GET,
                            data = page.data,
                            budget = budget,
                            truncated = page.truncated,
                            truncationReason = page.truncationReason,
                        ),
                    ))
                }
            }
        },
    )

    private fun requireRuntime(
        invocationContext: ToolInvocationContext,
        needsChatSnapshot: Boolean = false,
        needsPublish: Boolean = false,
    ): LocalMomentsRuntime {
        val settings = settingsFlow.value
        val snapshot = currentSnapshot(invocationContext, settings)
        val access = LocalMomentsAccessPolicy.evaluate(snapshot)
        access.denial?.let { throw toolDenied(it) }
        if (needsPublish && !access.canPublish) {
            throw LocalMomentsToolException("publish_not_granted", "当前朋友圈设置不允许助手直接发帖")
        }
        if (needsChatSnapshot && !access.canReadChatSnapshot) {
            throw LocalMomentsToolException(
                "chat_snapshot_not_granted",
                "宿主未授权 localdata.chat_snapshot，不能读取聊天记录卡片",
            )
        }
        val assistantId = snapshot.assistantId
            ?: throw LocalMomentsToolException("assistant_identity_missing", "缺少助手身份")
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: throw LocalMomentsToolException("assistant_unknown", "当前助手不在宿主助手列表中")
        val capabilities = if (needsPublish) {
            setOf(LocalDataCapability.WRITE, LocalDataCapability.PUBLISH)
        } else buildSet {
            add(LocalDataCapability.READ)
            if (access.canReadMedia) add(LocalDataCapability.MEDIA)
            if (access.canReadChatSnapshot) add(LocalDataCapability.CHAT_SNAPSHOT)
        }
        return LocalMomentsRuntime(
            access = access,
            snapshot = snapshot,
            assistantId = assistantId,
            assistantName = assistant.name.ifBlank { "助手" },
            directory = directoryOf(settings),
            grant = LocalDataGrant(capabilities),
            userDisplayName = settings.displaySetting.userNickname.ifBlank { "我" },
        )
    }

    private fun currentSnapshot(invocationContext: ToolInvocationContext, settings: Settings): LocalMomentsAccessSnapshot {
        val grants = grants()
        val assistantId = invocationContext.callerAssistantId
        val assistant = assistantId?.let { id -> settings.assistants.firstOrNull { it.id.toString() == id } }
        return localMomentsAccessSnapshot(
            invocationContext = invocationContext,
            pluginInstalled = grants.pluginInstalled,
            pluginEnabled = grants.pluginEnabled,
            pluginLoadError = grants.pluginLoadError,
            grantedCapabilities = grants.declaredCapabilities intersect grants.approvedCapabilities,
            taskSettings = taskSettings(),
            assistantId = assistantId,
            assistantKnown = assistant != null,
        )
    }

    private fun grants(): LocalMomentsGrantSnapshot {
        val plugin = getPlugin(LocalMomentsContract.PLUGIN_ID)
        val approved = context.getSharedPreferences(HOST_GRANT_PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(LocalMomentsContract.PLUGIN_ID, emptySet())
            .orEmpty()
        return LocalMomentsGrantSnapshot(
            pluginInstalled = plugin != null,
            pluginEnabled = plugin?.isEnabled == true,
            pluginLoadError = plugin?.loadError,
            declaredCapabilities = plugin?.manifest?.permissions?.toSet().orEmpty(),
            approvedCapabilities = approved,
        )
    }

    private fun taskSettings(): LocalTaskSettings = LocalTaskSettingsStore(
        SharedPreferencesLocalTaskStore(
            context,
            LocalTaskIds.storeName(LocalMomentsContract.PLUGIN_ID),
        ),
    ).get()

    private fun directoryOf(settings: Settings): LocalAssistantDirectory =
        LocalAssistantDirectory.of(
            settings.assistants.map {
                LocalDataAssistantRef(
                    id = it.id.toString(),
                    name = it.name.ifBlank { "助手" },
                    avatar = avatarOf(it.avatar),
                )
            },
        )

    private fun avatarOf(avatar: Avatar): String? = when (avatar) {
        Avatar.Dummy -> null
        is Avatar.Emoji -> avatar.content
        is Avatar.Image -> avatar.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private suspend fun <T> withSession(
        runtime: LocalMomentsRuntime,
        block: (LocalDataSession) -> T,
    ): T = withContext(Dispatchers.IO) {
        LocalDataSession(context.applicationContext, LocalMomentsContract.PLUGIN_ID, runtime.grant)
            .use { session -> block(session) }
    }

    private fun call(
        session: LocalDataSession,
        runtime: LocalMomentsRuntime,
        method: String,
        params: JsonObject,
    ): LocalDataEnvelope {
        val request = buildJsonObject {
            put("version", LocalDataApi.VERSION)
            put("method", method)
            put("params", params)
        }.toString()
        val raw = session.call(
            requestJson = request,
            actor = LocalDataActor.Assistant(runtime.assistantId, runtime.assistantName),
            assistantDirectory = runtime.directory,
            assistantAutoPublish = runtime.grant.allows(LocalDataCapability.PUBLISH),
            userDisplayName = runtime.userDisplayName,
        )
        val envelope = LocalDataJson.json.decodeFromString(LocalDataEnvelope.serializer(), raw)
        if (!envelope.ok) {
            throw LocalMomentsToolException(
                envelope.error?.code ?: "localdata_error",
                envelope.error?.message ?: "本机朋友圈操作失败",
            )
        }
        return envelope
    }

    private fun <T> LocalDataEnvelope.decode(deserializer: DeserializationStrategy<T>): T {
        val payload = data ?: throw LocalMomentsToolException("unavailable", "本地朋友圈没有返回数据")
        return LocalDataJson.json.decodeFromJsonElement(deserializer, payload)
    }

    private fun persistThumbnail(source: File, resourceId: String): File? = runCatching {
        if (!LocalDataValidation.isSafeMediaId(resourceId)) return@runCatching null
        if (!source.isFile || source.length() <= 0L ||
            source.length() > LocalMomentsLimits.MAX_ATTACHED_IMAGE_BYTES
        ) {
            return@runCatching null
        }
        val target = File(imagesDir(), "moments_$resourceId.jpg")
        if (target.isFile && target.length() == source.length()) return@runCatching target
        source.copyTo(target, overwrite = true)
        target.takeIf { it.isFile && it.length() == source.length() }
    }.getOrNull()

    private suspend fun runTool(
        tool: String,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: LocalMomentsToolException) {
        toolError(tool, error.code, error.message)
    } catch (error: LocalMomentsArgumentException) {
        toolError(tool, error.code, error.message)
    } catch (error: LocalDataException) {
        toolError(tool, error.code.wire, error.message)
    } catch (error: Exception) {
        toolError(
            tool,
            "unavailable",
            "本机朋友圈操作失败：${error.message?.take(120) ?: "未知错误"}",
        )
    }

    private fun toolDenied(denial: LocalMomentsDenial): LocalMomentsToolException =
        LocalMomentsToolException(denial.code, denial.message)

    private fun toolError(tool: String, code: String, message: String): List<UIMessagePart> {
        val payload = buildJsonObject {
            put("ok", false)
            put("tool", tool)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message.take(300))
                put("retryable", false)
            })
        }.toString()
        return listOf(UIMessagePart.Text(payload))
    }

    companion object {
        /** Must stay in sync with [me.rerere.rikkahub.ui.pages.chat.LOCAL_MOMENTS_PLUGIN_ID]. */
        const val HOST_GRANT_PREFERENCES = "localdata_host_grants"
    }
}

class LocalMomentsToolException(
    val code: String,
    override val message: String,
) : Exception(message)

/**
 * Lead-facing wiring helper. Callers must pass the host's own [SettingsStore], [PluginManager] and
 * [FilesManager]; [invocationContext] must carry the real assistant id and must be non-headless for
 * a normal user-triggered chat. The returned list is empty when any native gate denies access.
 *
 * [budgetRegistry] is the user-turn scoped registry supplied by ChatService. [budgetAnchorUserId]
 * identifies the same real user message across tool-surface rebuilds and approval resumes; the
 * helper resolves the actual shared budget itself rather than accepting an unrelated prebuilt one.
 */
fun buildLocalMomentsTools(
    context: Context,
    settingsStore: SettingsStore,
    pluginManager: PluginManager,
    filesManager: FilesManager,
    invocationContext: ToolInvocationContext,
    budgetRegistry: LocalMomentsBudgetRegistry? = null,
    budgetAnchorUserId: String? = null,
): List<Tool> {
    val budget = localMomentsTurnBudget(budgetRegistry, invocationContext, budgetAnchorUserId)
        ?: LocalMomentsBudget()
    return LocalMomentsTools(
        context = context,
        settingsFlow = settingsStore.settingsFlow,
        getPlugin = pluginManager::getPlugin,
        imagesDir = filesManager::getImagesDir,
    ).build(invocationContext, budget, budgetAnchorUserId)
}

private fun JsonObjectBuilder.putFilter(filter: LocalMomentsFilterArgs): JsonObjectBuilder = apply {
    filter.authorId?.let { put("authorId", it) }
    filter.authorKind?.let { put("authorKind", it) }
    filter.type?.let { put("type", it) }
    filter.dateFrom?.let { put("dateFrom", it) }
    filter.dateTo?.let { put("dateTo", it) }
}

private fun stringParam(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intParam(description: String, minimum: Int, maximum: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun longParam(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", 0)
}

private fun enumParam(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(it) } })
}
