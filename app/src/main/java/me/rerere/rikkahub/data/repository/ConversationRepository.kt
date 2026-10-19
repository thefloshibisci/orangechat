/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import androidx.core.net.toUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentUrls
import me.rerere.rikkahub.data.files.localAttachmentFile
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ConversationRepository"

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    // Tests that exercise storage alone may omit settings; cleanup then conservatively keeps files.
    private val settingsStore: SettingsStore? = null,
) {
    private val undoAttachmentNames = java.util.concurrent.ConcurrentHashMap<Uuid, Set<String>>()

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    /** Lightweight lookup for background jobs; avoids decoding every node just to obtain an id. */
    suspend fun getMostRecentConversationId(assistantId: Uuid): Uuid? {
        return conversationDAO.getMostRecentConversationIdOfAssistant(assistantId.toString())
            ?.let { Uuid.parse(it) }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? = database.withTransaction {
        val entity = conversationDAO.getConversationById(uuid.toString())
        if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    // Auxiliary generation never rewrites message nodes or recreates a deleted conversation.
    suspend fun updateGeneratedTitle(id: Uuid, title: String): Boolean {
        return conversationDAO.updateTitle(id.toString(), title) > 0
    }

    suspend fun updateGeneratedSuggestions(id: Uuid, suggestions: List<String>): Boolean {
        return conversationDAO.updateSuggestions(id.toString(), JsonInstant.encodeToString(suggestions)) > 0
    }

    suspend fun insertConversation(conversation: Conversation): Conversation = withContext(Dispatchers.IO) {
        conversation.requireMessageNodesLoaded()
        try {
            val saved = database.withTransaction {
                val conversation = withoutDeletedAttachments(conversation)
                conversationDAO.insert(
                    conversationToConversationEntity(conversation)
                )
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                conversation
            }
            messageFtsManager.indexConversation(saved)
            saved
        } catch (e: Exception) {
            Log.e(TAG, "insertConversation failed, conversationId=${conversation.id}", e)
            throw e
        }
    }

    /**
     * 更新对话。
     *
     * 之前的实现是"删掉这个对话下全部消息节点，再把全部节点重新插入"，不管这一轮实际改了几条消息。
     * 对话历史越长，这个开销越大，而且每次保存都要付出跟历史总长度成正比的代价。
     *
     * 现在改成按 MessageNode.id 做精确 diff：
     * - 新出现的 id -> 插入
     * - 消失的 id（比如重新生成时截断历史）-> 删除
     * - 两边都有但内容不同（用 MessageNodeEntity 的 data class 结构相等判断）-> 更新
     * - 完全没变的 -> 跳过，不碰数据库
     *
     * FTS 索引也同步降级为只处理这批发生变化的 node，而不是整个对话重建。
     */
    suspend fun updateConversation(conversation: Conversation, previous: Conversation? = null): Conversation = withContext(Dispatchers.IO) {
        conversation.requireMessageNodesLoaded()
        previous?.requireMessageNodesLoaded()
        require(previous == null || previous.id == conversation.id)
        val discardedFiles = mutableSetOf<String>()
        try {
            val saved = database.withTransaction {
                val conversation = withoutDeletedAttachments(conversation)
                conversationDAO.update(
                    conversationToConversationEntity(conversation)
                )

                if (previous != null) {
                    // A session supplies its last committed snapshot: serialize only edited
                    // nodes and express deletions explicitly, without reading old message bodies.
                    val changes = conversationNodeChanges(previous, conversation)
                    val oldById = previous.messageNodes.associateBy { it.id.toString() }
                    val retained = attachmentUrls(changes.upserts.flatMap { it.second.messages })
                    (changes.changedIds.mapNotNull { oldById[it] }).forEach { old ->
                        discardedFiles += attachmentUrls(old.messages) - retained
                    }
                    val entities = changes.upserts.map { (index, node) ->
                        MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                            JsonInstant.encodeToString(node.messages), node.selectIndex)
                    }
                    changes.deletions.toList().chunked(200).forEach { messageNodeDAO.deleteByIds(it) }
                    if (entities.isNotEmpty()) messageNodeDAO.insertAll(entities)
                    messageFtsManager.reindexNodes(
                        conversation.id.toString(), conversation.title, conversation.updateAt,
                        changes.changedIds, changes.upserts.map { it.second },
                    )
                    return@withTransaction conversation
                }

                // Full snapshots from import/edit paths retain the conservative DB comparison.
                val existingIds = messageNodeDAO.getNodeIdsOfConversation(conversation.id.toString()).toSet()

                val newEntities = conversation.messageNodes.mapIndexed { index, node ->
                    MessageNodeEntity(
                        id = node.id.toString(),
                        conversationId = conversation.id.toString(),
                        nodeIndex = index,
                        messages = JsonInstant.encodeToString(node.messages),
                        selectIndex = node.selectIndex
                    )
                }
                val newById = newEntities.associateBy { it.id }

                // 只对本轮涉及、且之前已存在的 id 去查内容做比较，缩小命中超大行的范围
                val idsNeedCompare = newEntities.map { it.id }.filter { it in existingIds }
                val existingById = if (idsNeedCompare.isEmpty()) {
                    emptyMap()
                } else {
                    try {
                        idsNeedCompare.chunked(200).flatMap { messageNodeDAO.getNodesByIds(it) }.associateBy { it.id }
                    } catch (e: SQLiteBlobTooBigException) {
                        Log.w(
                            TAG,
                            "updateConversation: blob too big while comparing existing nodes, " +
                                "conversationId=${conversation.id}, will treat all as changed and overwrite",
                            e
                        )
                        // 读不出旧内容就没法比较，保守地当作全部有变化，直接覆盖写入（REPLACE，安全）
                        emptyMap()
                    }
                }

                // 只处理真正发生变化的 node：内容/顺序(nodeIndex)/selectIndex 有任何不同才写库；
                // 读不到旧内容的（existingById 里没有对应 id）也会被判定为"变化"，直接覆盖写入
                val toUpsert = newEntities.filter { newEntity ->
                    existingById[newEntity.id] != newEntity
                }
                val toDeleteIds = existingIds - newById.keys

                // This cold path may need old rows to collect attachments. Use the complete
                // chunk-capable reader; never skip an oversized row and infer it was empty.
                if (toDeleteIds.isNotEmpty() || toUpsert.isNotEmpty()) {
                    val changed = toUpsert.mapTo(mutableSetOf()) { it.id } + toDeleteIds
                    messageNodeDAO.loadCompleteNodes(conversation.id.toString())
                        .filter { it.id in changed }.forEach { old ->
                            discardedFiles += attachmentUrls(JsonInstant.decodeFromString<List<UIMessage>>(old.messages))
                        }
                }

                if (toDeleteIds.isNotEmpty()) {
                    toDeleteIds.toList().chunked(200).forEach { messageNodeDAO.deleteByIds(it) }
                }
                if (toUpsert.isNotEmpty()) {
                    messageNodeDAO.insertAll(toUpsert)
                }

                val changedNodeIds = toUpsert.map { it.id }.toSet() + toDeleteIds
                if (changedNodeIds.isNotEmpty()) {
                    messageFtsManager.reindexNodes(
                        conversationId = conversation.id.toString(),
                        conversationTitle = conversation.title,
                        updateAt = conversation.updateAt,
                        changedNodeIds = changedNodeIds,
                        currentNodes = conversation.messageNodes,
                    )
                }
                conversation
            }
            deleteUnreferencedChatFiles(discardedFiles)
            saved
        } catch (e: Exception) {
            Log.e(TAG, "updateConversation failed, conversationId=${conversation.id}", e)
            throw e
        }
    }

    /** Persist a session-owned append/replace journal without comparing untouched message bodies. */
    internal suspend fun updateConversationWithDelta(
        conversation: Conversation,
        previous: Conversation,
        delta: ConversationNodeDelta,
    ): Conversation = withContext(Dispatchers.IO) {
        require(delta.appliesTo(previous, conversation)) { "Node delta does not match the committed history" }
        val changes = delta.changes
        require(changes.upserts.none { (_, node) -> node.messages.any { it.hasBase64Part() } })
        val discardedFiles = mutableSetOf<String>()
        try {
            val saved = database.withTransaction {
                // A late checkpoint must never create nodes after the parent conversation was deleted.
                check(conversationDAO.existsById(conversation.id.toString())) { "Conversation no longer exists" }
                var cleanedNodes: MutableList<MessageNode>? = null
                val upserts = changes.upserts.map { (index, node) ->
                    if (index < previous.messageNodes.size) {
                        discardedFiles += attachmentUrls(previous.messageNodes[index].messages) - attachmentUrls(node.messages)
                    }
                    val messages = filesManager.withoutDeletedAttachments(node.messages)
                    val cleaned = if (messages === node.messages) node else node.copy(messages = messages)
                    if (cleaned !== node) {
                        if (cleanedNodes == null) cleanedNodes = conversation.messageNodes.toMutableList()
                        cleanedNodes!![index] = cleaned
                    }
                    index to cleaned
                }
                val conversation = cleanedNodes?.let { conversation.copy(messageNodes = it) } ?: conversation
                val entities = upserts.map { (index, node) ->
                    MessageNodeEntity(node.id.toString(), conversation.id.toString(), index,
                        JsonInstant.encodeToString(node.messages), node.selectIndex)
                }
                conversationDAO.update(conversationMetadataToEntity(conversation))
                if (entities.isNotEmpty()) messageNodeDAO.insertAll(entities)
                messageFtsManager.reindexNodes(
                    conversation.id.toString(), conversation.title, conversation.updateAt,
                    changes.changedIds, upserts.map { it.second }, failOnError = true,
                )
                conversation
            }
            deleteUnreferencedChatFiles(discardedFiles)
            saved
        } catch (e: Exception) {
            Log.e(TAG, "updateConversationWithDelta failed, conversationId=${conversation.id}", e)
            throw e
        }
    }

    suspend fun deleteConversation(
        conversation: Conversation,
        retainAttachments: Boolean = false,
    ): Conversation = withContext(Dispatchers.IO) {
        try {
            // Read after generation has stopped; the list projection/old UI snapshot may be stale.
            val fullConversation = getConversationById(conversation.id) ?: conversation
            messageFtsManager.deleteConversation(conversation.id.toString())
            database.withTransaction {
                if (retainAttachments) {
                    undoAttachmentNames[conversation.id] = fullConversation.files.mapNotNull {
                        localAttachmentFile(it.toString())?.name
                    }.toSet()
                }
                // message_node 会通过 CASCADE 自动删除
                conversationDAO.delete(
                    conversationToConversationEntity(conversation)
                )
            }
            if (!retainAttachments) releaseDeletedConversationAttachments(fullConversation)
            fullConversation
        } catch (e: Exception) {
            undoAttachmentNames.remove(conversation.id)
            Log.e(TAG, "deleteConversation failed, conversationId=${conversation.id}", e)
            throw e
        }
    }

    /** Undo keeps bytes until its action completes; recheck references after restore before collection. */
    suspend fun releaseDeletedConversationAttachments(conversation: Conversation) = withContext(Dispatchers.IO) {
        undoAttachmentNames.remove(conversation.id)
        deleteUnreferencedChatFiles(conversation.files.mapTo(mutableSetOf()) { it.toString() })
    }

    /** Preserve the original conversation/list identity when no deleted attachment is present. */
    fun withoutDeletedAttachments(conversation: Conversation): Conversation {
        if (!filesManager.hasDeletedAttachments) return conversation
        var changed = false
        val nodes = conversation.messageNodes.map { node ->
            val messages = filesManager.withoutDeletedAttachments(node.messages)
            if (messages === node.messages) node else {
                changed = true
                node.copy(messages = messages)
            }
        }
        return if (changed) conversation.copy(messageNodes = nodes) else conversation
    }

    /** Each read is complete (including oversized rows), and each update is compare-and-set. */
    suspend fun repairInvalidAttachments(): Int = withContext(Dispatchers.IO) {
        var repaired = 0
        for (conversationId in conversationDAO.getAllIds()) {
            for (id in messageNodeDAO.getNodeIdsOfConversation(conversationId)) {
                database.withTransaction {
                    val entity = messageNodeDAO.loadCompleteNode(id) ?: return@withTransaction
                    val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    check(original.isNotEmpty() && entity.selectIndex in original.indices) {
                        "会话包含无法读取的消息，附件清理已停止，原始消息未被覆盖。"
                    }
                    val cleaned = filesManager.validAttachments(original)
                    if (cleaned !== original) {
                        repaired += messageNodeDAO.repairAttachments(
                            entity.id, entity.messages, JsonInstant.encodeToString(cleaned),
                        )
                    }
                }
            }
        }
        repaired
    }

    /** Lifecycle garbage collection follows durable writes and retains every DB branch/settings reference. */
    private suspend fun deleteUnreferencedChatFiles(urls: Set<String>) {
        if (urls.isEmpty()) return
        val store = settingsStore ?: return
        for (url in urls) {
            val file = localAttachmentFile(url) ?: continue
            if (!filesManager.isManagedChatFile(url)) continue
            database.withTransaction {
                val settings = store.settingsFlow.value
                if (undoAttachmentNames.values.none { file.name in it } &&
                    !settings.init && !JsonInstant.encodeToString(settings).contains(file.name) &&
                    !messageNodeDAO.referencesFile(file.name)) {
                    filesManager.deleteChatFiles(listOf(file.toUri()))
                }
            }
        }
    }

    suspend fun searchMessages(keyword: String) = messageFtsManager.search(keyword)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        try {
            messageFtsManager.deleteAll()
            val allIds = conversationDAO.getAllIds()
            val total = allIds.size
            allIds.forEachIndexed { index, id ->
                val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
                val nodes = loadMessageNodes(entity.id)
                val conversation = conversationEntityToConversation(entity, nodes)
                messageFtsManager.indexConversation(conversation)
                onProgress(index + 1, total)
            }
        } catch (e: Exception) {
            Log.e(TAG, "rebuildAllIndexes failed", e)
            throw e
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return conversationMetadataToEntity(conversation)
    }

    private fun conversationMetadataToEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            compressionSummaries = JsonInstant.encodeToString(conversation.compressionSummaries),
            compressedNodeIds = JsonInstant.encodeToString(conversation.compressedMessageNodeIds),
            compressionRevision = conversation.compressionRevision,
            sendCompressedSummary = conversation.sendCompressedSummary,
            legacyCompressedSummary = conversation.compressedSummary,
            compressionHistory = JsonInstant.encodeToString(conversation.compressionHistory),
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>,
        messageNodesLoaded: Boolean = true,
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.also { nodes ->
                check(nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices }) {
                    "会话包含无法读取的消息，请先恢复记录。原有记录未被修改。"
                }
            },
            messageNodesLoaded = messageNodesLoaded,
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            compressionSummaries = JsonInstant.decodeFromString(conversationEntity.compressionSummaries),
            compressedMessageNodeIds = JsonInstant.decodeFromString(conversationEntity.compressedNodeIds),
            compressionRevision = conversationEntity.compressionRevision,
            sendCompressedSummary = conversationEntity.sendCompressedSummary,
            compressionHistory = JsonInstant.decodeFromString(conversationEntity.compressionHistory),
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList(), messageNodesLoaded = false)
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid): Boolean? = database.withTransaction {
        conversationDAO.togglePinStatus(conversationId.toString())
        conversationDAO.getPinStatus(conversationId.toString())
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            messageNodesLoaded = false,
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            messageNodeDAO.loadCompleteNodes(conversationId).map { entity ->
                val original = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                val messages = filesManager.validAttachments(original)
                if (messages !== original) {
                    messageNodeDAO.repairAttachments(entity.id, entity.messages, JsonInstant.encodeToString(messages))
                }
                check(messages.isNotEmpty() && entity.selectIndex in messages.indices) {
                    "会话包含无法读取的消息，请先恢复记录。原有记录未被修改。"
                }
                val nodeId = Uuid.parse(entity.id)
                MessageNode(nodeId, messages, entity.selectIndex, favoriteNodeIds.contains(nodeId))
            }
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    /**
     * 获取指定日期范围内的所有聊天记录（用于日记总结）
     * 从 message_node 表读取，不依赖 memory_bank
     */
    suspend fun getMessagesByDateRange(startDate: String, endDate: String): List<DatedMessage> = withContext(Dispatchers.IO) {
        val allConversationIds = conversationDAO.getAllIds()
        val result = mutableListOf<DatedMessage>()

        for (conversationId in allConversationIds) {
            val nodes = try {
                loadMessageNodes(conversationId)
            } catch (e: Exception) {
                Log.e(TAG, "getMessagesByDateRange: failed to load nodes, conversationId=$conversationId", e)
                continue
            }

            for (node in nodes) {
                // 只取选中的消息（selectIndex 对应的）
                val selectedMessage = node.messages.getOrNull(node.selectIndex) ?: continue
                val dateStr = selectedMessage.createdAt.toString().take(10) // yyyy-MM-dd

                if (dateStr >= startDate && dateStr <= endDate) {
                    val text = selectedMessage.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                        .trim()
                    if (text.isNotBlank()) {
                        result.add(
                            DatedMessage(
                                date = dateStr,
                                role = selectedMessage.role.name.lowercase(),
                                content = text,
                                assistantId = null, // 无法直接从消息获取
                                conversationId = conversationId
                            )
                        )
                    }
                }
            }
        }

        result.sortedBy { it.date }
    }
}

/**
 * 带日期的聊天记录条目（用于日记总结）
 */
data class DatedMessage(
    val date: String,
    val role: String,
    val content: String,
    val assistantId: String?,
    val conversationId: String
)

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
