package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDirectoryStateTest {
    private fun location(path: String, connection: String = "connection-a") =
        WorkspaceBrowseLocation("workspace", WorkspaceStorageArea.FILES, path, connection)

    @Test fun changingDirectoryCancelsOldReadBeforePublishingNewDirectory() = runTest {
        val locations = MutableStateFlow<WorkspaceBrowseLocation?>(location("old"))
        val states = mutableListOf<WorkspaceDirectoryState>()
        var oldCancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workspaceDirectoryStates(locations, MutableStateFlow(0L)) {
                if (it.path == "old") try { awaitCancellation() } finally { oldCancelled = true }
                listOf(file("new"))
            }.collect { states += it }
        }
        runCurrent(); locations.value = location("new"); runCurrent()
        assertTrue(oldCancelled)
        assertEquals("new", states.last().location?.path)
        assertEquals(listOf(file("new")), states.last().entries)
        assertFalse(states.any { it.location?.path == "old" && !it.loading })
    }

    @Test fun changingConnectionAtSamePathCancelsTheOldServerRead() = runTest {
        val locations = MutableStateFlow<WorkspaceBrowseLocation?>(location("folder"))
        val states = mutableListOf<WorkspaceDirectoryState>()
        var oldCancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workspaceDirectoryStates(locations, MutableStateFlow(0L)) {
                if (it.connectionRevision == "connection-a") try { awaitCancellation() }
                finally { oldCancelled = true }
                listOf(file("server-b"))
            }.collect { states += it }
        }
        runCurrent(); locations.value = location("folder", "connection-b"); runCurrent()
        assertTrue(oldCancelled)
        assertEquals("connection-b", states.last().location?.connectionRevision)
        assertEquals("server-b", states.last().entries.single().name)
    }

    @Test fun failedReadStaysAnErrorAndRetryQueriesAgain() = runTest {
        val revisions = MutableStateFlow(0L)
        val states = mutableListOf<WorkspaceDirectoryState>()
        var calls = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workspaceDirectoryStates(MutableStateFlow(location("folder")), revisions) {
                if (++calls == 1) error("connection unavailable")
                listOf(file("recovered"))
            }.collect { states += it }
        }
        runCurrent()
        assertEquals("connection unavailable", states.last().error)
        assertFalse(states.last().loading)
        revisions.value++; runCurrent()
        assertNull(states.last().error)
        assertEquals("recovered", states.last().entries.single().name)
    }

    @Test fun refreshCancelsAnInProgressReadAndPublishesOnlyLatestAttempt() = runTest {
        val revisions = MutableStateFlow(0L)
        val states = mutableListOf<WorkspaceDirectoryState>()
        var calls = 0
        var cancelled = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workspaceDirectoryStates(MutableStateFlow(location("folder")), revisions) {
                if (++calls == 1) try { awaitCancellation() } finally { cancelled = true }
                listOf(file("latest"))
            }.collect { states += it }
        }
        runCurrent(); revisions.value++; runCurrent()
        assertTrue(cancelled)
        assertEquals("latest", states.last().entries.single().name)
        assertEquals(2, calls)
    }

    @Test fun absentWorkspaceDoesNotQueryAndDirectoriesSortBeforeFiles() = runTest {
        val locations = MutableStateFlow<WorkspaceBrowseLocation?>(null)
        val states = mutableListOf<WorkspaceDirectoryState>()
        var calls = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            workspaceDirectoryStates(locations, MutableStateFlow(0L)) {
                calls++
                listOf(file("B"), file("a"), file("z", true))
            }.collect { states += it }
        }
        runCurrent(); assertEquals(0, calls)
        locations.value = location(""); runCurrent()
        assertEquals(listOf("z", "a", "B"), states.last().entries.map { it.name })
        assertFalse(states.last().loading)
    }

    private fun file(name: String, directory: Boolean = false) = WorkspaceFileEntry(name, name, directory, 1, 2)
}
