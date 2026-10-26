package me.rerere.rikkahub.data.files

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Pins pending and in-flight input without holding a writer lease for the whole queue lifetime. */
internal class QueueAttachmentReferences(
    private val coordinator: AttachmentReferenceCoordinator = attachmentReferenceGuard,
) {
    private val owner = Any()

    fun update(parts: List<UIMessagePart>) {
        coordinator.beginWrite().use {
            val urls = attachmentUrls(listOf(UIMessage.user("").copy(parts = parts)))
            coordinator.updateDraft(owner, urls.mapNotNull { localAttachmentFile(it)?.name }.toSet())
        }
    }

    /** Releasing a pin does not delete bytes; normal guarded garbage collection may reclaim them. */
    fun release() = update(emptyList())
}
