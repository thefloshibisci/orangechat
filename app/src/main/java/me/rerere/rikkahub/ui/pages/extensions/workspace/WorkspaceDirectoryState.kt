package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

internal data class WorkspaceBrowseLocation(
    val workspaceId: String,
    val area: WorkspaceStorageArea,
    val path: String,
    val connectionRevision: String?,
)

internal data class WorkspaceDirectoryState(
    val location: WorkspaceBrowseLocation?,
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Switching directories, connections or retrying cancels the previous remote read. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun workspaceDirectoryStates(
    locations: Flow<WorkspaceBrowseLocation?>,
    refreshes: Flow<Long>,
    query: suspend (WorkspaceBrowseLocation) -> List<WorkspaceFileEntry>,
): Flow<WorkspaceDirectoryState> = combine(locations, refreshes) { location, revision -> location to revision }
    .distinctUntilChanged()
    .transformLatest { (location, _) ->
        if (location == null) {
            emit(WorkspaceDirectoryState(null))
            return@transformLatest
        }
        emit(WorkspaceDirectoryState(location, loading = true))
        try {
            val entries = query(location)
            currentCoroutineContext().ensureActive()
            emit(WorkspaceDirectoryState(location, entries.sortedWith(
                compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
            )))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emit(WorkspaceDirectoryState(location, error = error.message ?: "读取工作区目录失败，请重试"))
        }
    }
