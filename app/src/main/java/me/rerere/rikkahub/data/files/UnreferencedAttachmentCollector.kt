package me.rerere.rikkahub.data.files

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Preview IDs are candidates only; a changed reference generation always wins over collection. */
internal suspend fun collectUnreferencedAttachments(
    filesManager: FilesManager,
    ids: Set<Long>,
    findUnreferenced: suspend (Collection<String>) -> Set<String>,
    protectedNames: () -> Set<String> = { emptySet() },
    survivors: Map<String, File> = emptyMap(),
): Pair<Int, Long> = withContext(Dispatchers.IO) {
    val guard = attachmentReferenceGuard
    val snapshot = guard.snapshot() ?: return@withContext 0 to 0L
    val candidates = ids.mapNotNull { filesManager.get(it) }.filter {
        filesManager.isManagedChatFile(filesManager.getFile(it).toUri().toString())
    }
    val metadata = candidates.associate { entity ->
        val file = filesManager.getFile(entity)
        entity.id to (file.length() to file.lastModified())
    }
    val protected = guard.protectedNames() + protectedNames()
    val unused = findUnreferenced(
        candidates.map { filesManager.getFile(it).name }.toSet() - protected,
    )
    var removed = 0
    var freed = 0L
    for (entity in candidates) {
        if (filesManager.getFile(entity).name !in unused) continue
        val (size, modified) = metadata.getValue(entity.id)
        withContext(NonCancellable) {
            if (filesManager.deleteUnreferenced(entity, snapshot, size, modified, survivors[filesManager.getFile(entity).name])) {
                removed++
                freed += size
            }
        }
    }
    removed to freed
}
