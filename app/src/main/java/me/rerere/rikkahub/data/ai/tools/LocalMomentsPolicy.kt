/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.plugin.localdata.LocalDataApi
import me.rerere.rikkahub.plugin.localdata.LocalDataCapability
import me.rerere.rikkahub.plugin.localdata.LocalDataValidation
import me.rerere.rikkahub.plugin.localtasks.LocalAssistantPolicy
import me.rerere.rikkahub.plugin.localtasks.LocalTaskSettings

object LocalMomentsContract {
    const val PLUGIN_ID = "com.orangechat.localmoments"
    const val TOOL_LIST = "moments_list"
    const val TOOL_GET = "moments_get"
    const val TOOL_SEARCH = "moments_search"
    const val TOOL_COMMENTS = "moments_comments"
    const val TOOL_MEDIA_GET = "moments_media_get"
    const val TOOL_CHAT_EXCERPT_GET = "moments_chat_excerpt_get"
    const val TOOL_PUBLISH = "moments_publish"
}

/** Hard limits. They are deliberately lower than the plugin UI's own page size of 3..20. */
object LocalMomentsLimits {
    const val DEFAULT_PAGE_SIZE = 3
    const val MAX_PAGE_SIZE = 5
    const val DEFAULT_LIST_BODY_CHARS = 220
    const val MAX_LIST_BODY_CHARS = 260
    const val DEFAULT_GET_BODY_CHARS = 600
    const val MAX_GET_BODY_CHARS = 1_000
    const val DEFAULT_COMMENT_LIMIT = 3
    const val MAX_COMMENT_LIMIT = 5
    const val MAX_COMMENT_BODY_CHARS = 160
    const val DEFAULT_EXCERPT_MESSAGES = 10
    const val MAX_EXCERPT_MESSAGES = 20
    const val DEFAULT_EXCERPT_MESSAGE_CHARS = 180
    const val MAX_EXCERPT_MESSAGE_CHARS = 260
    const val MAX_EXCERPT_TOTAL_CHARS = 1_600
    const val MAX_OUTPUT_CHARS = 2_600
    const val OUTPUT_JSON_RESERVE_CHARS = 400
    const val MAX_TOOL_CALLS_PER_TURN = 6
    const val MAX_PAGES_PER_TURN = 4
    const val MAX_SEARCHES_PER_TURN = 3
    const val MAX_MEDIA_METADATA_PER_PAGE = 4
    const val MAX_ATTACHED_IMAGES_PER_CALL = 1
    const val MAX_ATTACHED_IMAGE_BYTES = 1_500_000L
    const val THUMBNAIL_MAX_PX = LocalDataApi.THUMBNAIL_MAX_PX
}

data class LocalMomentsDenial(val code: String, val message: String)

/** Host facts used by the pure ACL helper. */
data class LocalMomentsAccessSnapshot(
    val pluginInstalled: Boolean,
    val pluginEnabled: Boolean,
    val pluginLoadError: String? = null,
    val grantedCapabilities: Set<String> = emptySet(),
    val taskSettings: LocalTaskSettings = LocalTaskSettings(),
    val assistantId: String? = null,
    val assistantKnown: Boolean = false,
    val headless: Boolean = false,
    val modelCanSeeImages: Boolean = false,
)

data class LocalMomentsAccess(
    val denial: LocalMomentsDenial? = null,
    val canReadMedia: Boolean = false,
    val canReadChatSnapshot: Boolean = false,
    val canAttachImages: Boolean = false,
    val canPublish: Boolean = false,
) {
    val allowed: Boolean get() = denial == null
}

/**
 * Pure ACL evaluation. No Android types here so JVM tests can pin every deny path.
 *
 * The native participant list is the read ACL: `LocalTaskSettings.enabled` only controls automatic
 * interaction, so turning it off must not quietly revoke a participant's read tools. A participant
 * with a `disabled` policy, a global stop, or no participant entry is denied.
 */
object LocalMomentsAccessPolicy {
    fun evaluate(snapshot: LocalMomentsAccessSnapshot): LocalMomentsAccess {
        fun deny(code: String, message: String) = LocalMomentsAccess(
            denial = LocalMomentsDenial(code, message),
        )

        if (!snapshot.pluginInstalled) {
            return deny("plugin_not_installed", "本机朋友圈插件未安装，不能读取动态")
        }
        if (!snapshot.pluginEnabled || snapshot.pluginLoadError != null) {
            return deny("plugin_disabled", "本机朋友圈插件未启用，不能读取动态")
        }
        if (LocalDataCapability.READ.wire !in snapshot.grantedCapabilities) {
            return deny("read_not_granted", "宿主未授权本机朋友圈的 localdata.read，不能读取动态")
        }
        if (snapshot.headless) {
            return deny("headless_call", "朋友圈读取工具只允许在用户正常发起的聊天中调用")
        }
        val assistantId = snapshot.assistantId
        if (assistantId.isNullOrBlank() || !LocalDataValidation.isSafeAssistantId(assistantId)) {
            return deny("assistant_identity_missing", "缺少宿主确认的助手身份，不能读取动态")
        }
        if (!snapshot.assistantKnown) {
            return deny("assistant_unknown", "当前助手不在宿主助手列表中，不能读取动态")
        }

        val settings = snapshot.taskSettings.normalized()
        if (settings.globalStop) {
            return deny("assistant_tasks_stopped", "朋友圈助手互动已被全局停止，不能读取动态")
        }
        if (assistantId !in settings.participants) {
            return deny("assistant_not_participant", "当前助手不是朋友圈参与助手，不能读取动态")
        }
        if (settings.effectivePolicy(assistantId) == LocalAssistantPolicy.DISABLED) {
            return deny("assistant_policy_disabled", "当前助手的朋友圈策略为 disabled，不能读取动态")
        }

        val canReadMedia = LocalDataCapability.MEDIA.wire in snapshot.grantedCapabilities
        val canReadChatSnapshot = LocalDataCapability.CHAT_SNAPSHOT.wire in snapshot.grantedCapabilities
        return LocalMomentsAccess(
            denial = null,
            canReadMedia = canReadMedia,
            canReadChatSnapshot = canReadChatSnapshot,
            // Image bytes are a separate native permission and require an actually vision-capable
            // model. Metadata is still available when either condition is false.
            canAttachImages = canReadMedia && settings.allowImages && snapshot.modelCanSeeImages,
            canPublish = settings.effectivePolicy(assistantId) == LocalAssistantPolicy.AUTO_PUBLISH &&
                !settings.requireReview &&
                LocalDataCapability.WRITE.wire in snapshot.grantedCapabilities &&
                LocalDataCapability.PUBLISH.wire in snapshot.grantedCapabilities,
        )
    }
}

/**
 * R2-3 regression seam: host ACL facts + the per-turn model capability, nothing from the
 * assistant's default model. ChatService has already resolved the provider and the conversation
 * override into [ToolInvocationContext.modelCanSeeImages]; consulting
 * `assistant.chatModelId ?: settings.chatModelId` here would reject a conversation explicitly
 * switched to a vision model. Android-free so the override regression is JVM-testable.
 */
internal fun localMomentsAccessSnapshot(
    invocationContext: ToolInvocationContext,
    pluginInstalled: Boolean,
    pluginEnabled: Boolean,
    pluginLoadError: String?,
    grantedCapabilities: Set<String>,
    taskSettings: LocalTaskSettings,
    assistantId: String?,
    assistantKnown: Boolean,
): LocalMomentsAccessSnapshot = LocalMomentsAccessSnapshot(
    pluginInstalled = pluginInstalled,
    pluginEnabled = pluginEnabled,
    pluginLoadError = pluginLoadError,
    grantedCapabilities = grantedCapabilities,
    taskSettings = taskSettings,
    assistantId = assistantId,
    assistantKnown = assistantKnown,
    headless = invocationContext.isHeadless,
    modelCanSeeImages = invocationContext.modelCanSeeImages,
)

data class LocalMomentsLimitedText(
    val text: String,
    val truncated: Boolean,
    val originalChars: Int,
)

/** Character based, worst-case token accounting: one output character is charged as one token. */
class LocalMomentsTextBudget(
    val maxChars: Int = LocalMomentsLimits.MAX_OUTPUT_CHARS,
    val reservedChars: Int = LocalMomentsLimits.OUTPUT_JSON_RESERVE_CHARS,
) {
    val usableChars: Int = (maxChars - reservedChars).coerceAtLeast(0)

    var usedChars: Int = 0
        private set

    val remainingChars: Int get() = (usableChars - usedChars).coerceAtLeast(0)
    val truncated: Boolean get() = usedChars >= usableChars

    fun canAfford(chars: Int): Boolean = remainingChars >= chars.coerceAtLeast(0)

    fun charge(chars: Int) {
        usedChars = (usedChars + chars.coerceAtLeast(0)).coerceAtMost(usableChars)
    }

    fun take(value: String, requestedMax: Int): LocalMomentsLimitedText {
        val allowed = minOf(requestedMax.coerceAtLeast(0), remainingChars)
        val text = if (value.length <= allowed) value else value.take(allowed)
        usedChars += text.length
        return LocalMomentsLimitedText(
            text = text,
            truncated = text.length < value.length,
            originalChars = value.length,
        )
    }
}

enum class LocalMomentsCallKind {
    LIST,
    GET,
    SEARCH,
    COMMENTS,
    MEDIA,
    CHAT_EXCERPT,
    PUBLISH,
}

class LocalMomentsBudget(
    private val maxCalls: Int = LocalMomentsLimits.MAX_TOOL_CALLS_PER_TURN,
    private val maxPages: Int = LocalMomentsLimits.MAX_PAGES_PER_TURN,
    private val maxSearches: Int = LocalMomentsLimits.MAX_SEARCHES_PER_TURN,
    val text: LocalMomentsTextBudget = LocalMomentsTextBudget(),
) {
    var calls: Int = 0
        private set
    var pages: Int = 0
        private set
    var searches: Int = 0
        private set

    /** Returns a denial when this call would exceed a hard limit; otherwise consumes the budget. */
    fun beginCall(kind: LocalMomentsCallKind): LocalMomentsDenial? {
        if (calls >= maxCalls) {
            return LocalMomentsDenial(
                "tool_call_budget_exhausted",
                "本轮朋友圈工具调用已达上限（$maxCalls 次），请基于已有结果回答",
            )
        }
        val pageKind = kind == LocalMomentsCallKind.LIST ||
            kind == LocalMomentsCallKind.SEARCH ||
            kind == LocalMomentsCallKind.COMMENTS ||
            kind == LocalMomentsCallKind.CHAT_EXCERPT
        if (pageKind && pages >= maxPages) {
            return LocalMomentsDenial(
                "page_budget_exhausted",
                "本轮朋友圈翻页已达上限（$maxPages 页），请基于已有结果回答",
            )
        }
        if (kind == LocalMomentsCallKind.SEARCH && searches >= maxSearches) {
            return LocalMomentsDenial(
                "search_budget_exhausted",
                "本轮朋友圈搜索已达上限（$maxSearches 次），请基于已有结果回答",
            )
        }
        calls++
        if (pageKind) pages++
        if (kind == LocalMomentsCallKind.SEARCH) searches++
        return null
    }

    fun status(): JsonObject = buildJsonObject {
        put("toolCallsUsed", calls)
        put("toolCallsLimit", maxCalls)
        put("pagesUsed", pages)
        put("pagesLimit", maxPages)
        put("searchesUsed", searches)
        put("searchesLimit", maxSearches)
        put("outputCharsUsed", text.usedChars)
        put("outputCharsLimit", text.usableChars)
        // Worst-case estimator: one CJK/output character is charged as one token.
        put("estimatedOutputTokens", text.usedChars)
        put("outputTokenLimit", text.usableChars)
        put("outputTruncated", text.truncated)
    }
}

/**
 * R2-4: one read budget per **user turn**, not per tool-surface build.
 *
 * A tool approval pauses the assistant turn, and resuming it rebuilds the whole tool surface via
 * ChatService.buildAvailableTools. A budget created inside `build()` would therefore be handed a
 * fresh allowance after every approval, letting the same user task spend 6 reads before the
 * approval and another 6 after it. The registry keeps the same [LocalMomentsBudget] instance for
 * as long as the conversation's newest user message is unchanged, so generation, tool-loop
 * rebuilds and approval resumes all charge the same counters.
 */
class LocalMomentsBudgetRegistry(
    private val maxConversations: Int = 32,
) {
    private data class Entry(val anchorUserId: String, val budget: LocalMomentsBudget)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Returns the budget for [conversationId]'s current user turn, creating/replacing as needed. */
    fun budgetFor(conversationId: String, anchorUserId: String): LocalMomentsBudget =
        synchronized(entries) {
            val existing = entries[conversationId]
            if (existing != null && existing.anchorUserId == anchorUserId) {
                existing.budget
            } else {
                LocalMomentsBudget().also { fresh ->
                    entries[conversationId] = Entry(anchorUserId, fresh)
                    while (entries.size > maxConversations) {
                        val eldest = entries.keys.firstOrNull() ?: break
                        entries.remove(eldest)
                    }
                }
            }
        }

    /** Visible for tests: number of conversations currently holding a budget. */
    internal fun trackedConversations(): Int = synchronized(entries) { entries.size }

    /** Drop a deleted conversation's budget immediately instead of waiting for LRU eviction. */
    fun remove(conversationId: String) {
        synchronized(entries) { entries.remove(conversationId) }
    }

    /** Drop every budget. Used when the owning service shuts down. */
    fun clear() = synchronized(entries) { entries.clear() }
}

/** The same resolver used by buildLocalMomentsTools, kept Android-free for wiring regression tests. */
internal fun localMomentsTurnBudget(
    registry: LocalMomentsBudgetRegistry?,
    invocationContext: ToolInvocationContext,
    anchorUserId: String?,
): LocalMomentsBudget? {
    val conversationId = invocationContext.callerConversationId?.takeIf(String::isNotBlank) ?: return null
    val anchor = anchorUserId?.takeIf(String::isNotBlank) ?: return null
    return registry?.budgetFor(conversationId, anchor)
}
