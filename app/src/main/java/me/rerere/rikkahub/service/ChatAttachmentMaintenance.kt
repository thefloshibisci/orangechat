/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.attachmentReferenceGuard
import me.rerere.rikkahub.data.files.localAttachmentFile
import me.rerere.rikkahub.data.files.planDuplicateAttachments
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File
import kotlin.uuid.Uuid

internal class ChatAttachmentMaintenance(
    private val context: Application,
    private val filesManager: FilesManager,
    private val conversationRepo: ConversationRepository,
    private val liveSessions: () -> List<ConversationSession>,
    private val saveConversationLocked: suspend (Uuid, Conversation) -> Unit,
) {
    private val attachmentMaintenanceMutex = Mutex()

    suspend fun deleteManagedAttachments(ids: Set<Long>): Int = withContext(Dispatchers.IO) {
        attachmentMaintenanceMutex.withLock {
            var deleted = 0
            val deletedNames = mutableSetOf<String>()
            try {
                for (id in ids) {
                    val entity = filesManager.get(id) ?: continue
                    // Record the candidate before deleting. Navigation cancellation must not
                    // interrupt the disk -> DB transition or skip unlinking its references.
                    deletedNames += filesManager.getFile(entity).name
                    if (withContext(NonCancellable) { filesManager.delete(id) }) deleted++
                }
            } finally {
                // Once bytes are deleted, finish unlinking even if the user leaves this page.
                withContext(NonCancellable) {
                    try {
                        conversationRepo.repairDeletedAttachmentReferences(deletedNames)
                    } finally {
                        refreshAttachmentReferences()
                    }
                }
            }
            deleted
        }
    }

    suspend fun cleanInvalidAttachments(): Pair<Int, Int> = attachmentMaintenanceMutex.withLock {
        try {
            filesManager.cleanInvalidFiles() to conversationRepo.repairInvalidAttachments()
        } finally {
            withContext(NonCancellable) { refreshAttachmentReferences() }
        }
    }

    suspend fun findUnreferencedAttachments(): Set<Long> = attachmentMaintenanceMutex.withLock {
        withContext(Dispatchers.IO) {
            filesManager.syncFolder()
            val entities = filesManager.list()
            val byName = entities.associateBy { filesManager.getFile(it).name }
            val sessionNames = buildSet {
                addAll(attachmentReferenceGuard.protectedNames())
                liveSessions().forEach { session ->
                    if (!session.isDeleted) {
                        session.state.value.files.forEach { uri ->
                            localAttachmentFile(uri.toString())?.let { add(it.name) }
                        }
                    }
                }
            }
            conversationRepo.findUnreferencedChatFileNames(byName.keys - sessionNames)
                .mapNotNullTo(mutableSetOf()) { byName[it]?.id }
        }
    }

    suspend fun deleteUnreferencedAttachments(ids: Set<Long>): Int = attachmentMaintenanceMutex.withLock {
        collectUnreferencedAttachments(ids).first
    }

    suspend fun previewDuplicateAttachments(): ChatService.DuplicateAttachmentSummary =
        attachmentMaintenanceMutex.withLock {
            withContext(Dispatchers.IO) {
                val plan = planDuplicateAttachments(uploadDirectory())
                ChatService.DuplicateAttachmentSummary(
                    plan.canonicalByDuplicate.size,
                    plan.canonicalByDuplicate.keys.sumOf { File(uploadDirectory(), it).length() },
                )
            }
        }

    suspend fun mergeDuplicateAttachments(): ChatService.DuplicateMergeResult =
        attachmentMaintenanceMutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = uploadDirectory()
                val guard = attachmentReferenceGuard
                val pin = Any()
                try {
                    val plan = guard.beginWrite().use {
                        guard.updateDraft(pin, dir.listFiles().orEmpty().map { it.name }.toSet())
                        planDuplicateAttachments(dir)
                    }
                    if (plan.isEmpty) return@withContext ChatService.DuplicateMergeResult(0, 0, 0, 0)
                    val rewritten = withContext(NonCancellable) {
                        guard.beginWrite().use { conversationRepo.remapDuplicateAttachmentReferences(plan) }
                    }
                    guard.updateDraft(pin, plan.canonicalByDuplicate.values.map { it.name }.toSet())
                    filesManager.syncFolder()
                    val entities = filesManager.list().associateBy { filesManager.getFile(it).name }
                    val (removed, freed) = collectUnreferencedAttachments(
                        plan.canonicalByDuplicate.keys.mapNotNullTo(mutableSetOf()) { entities[it]?.id },
                        survivors = plan.canonicalByDuplicate,
                    )
                    ChatService.DuplicateMergeResult(
                        removed,
                        freed,
                        plan.canonicalByDuplicate.size - removed,
                        rewritten,
                    )
                } finally {
                    guard.updateDraft(pin, emptySet())
                }
            }
        }

    private suspend fun collectUnreferencedAttachments(
        ids: Set<Long>,
        survivors: Map<String, File> = emptyMap(),
    ): Pair<Int, Long> = me.rerere.rikkahub.data.files.collectUnreferencedAttachments(
        filesManager,
        ids,
        conversationRepo::findUnreferencedChatFileNames,
        protectedNames = {
            liveSessions().filterNot { it.isDeleted }.flatMap { it.state.value.files }
                .mapNotNull { localAttachmentFile(it.toString())?.name }.toSet()
        },
        survivors = survivors,
    )

    private fun uploadDirectory(): File = context.filesDir.resolve(FileFolders.UPLOAD)

    private suspend fun refreshAttachmentReferences() = withContext(Dispatchers.Default) {
        liveSessions().forEach { session ->
            try {
                if (!session.isDeleted) {
                    session.withStateLock {
                        val current = session.state.value
                        val cleaned = conversationRepo.withoutDeletedAttachments(current)
                        if (cleaned !== current) {
                            session.messageJournal.invalidate()
                            saveConversationLocked(session.id, cleaned)
                        }
                    }
                }
            } catch (error: CancellationException) {
                if (!session.isDeleted) throw error
            }
        }
    }
}
