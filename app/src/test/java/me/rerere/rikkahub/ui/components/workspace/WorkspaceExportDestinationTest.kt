package me.rerere.rikkahub.ui.components.workspace

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WorkspaceExportDestinationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val resolver get() = context.contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri("export.test", "downloads")
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Before fun setup() {
        context.getSharedPreferences("workspace_downloads", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun firstDownloadNeedsSelectionThenNewInstancesReuseTheGrantedFolder() {
        val destination = WorkspaceExportDestination(context)
        assertNull(destination.current())
        destination.remember(tree)
        assertEquals(tree, destination.current())
        assertEquals(tree, WorkspaceExportDestination(context).current())
        assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
    }

    @Test fun revokedPermissionRequiresSelectionAgain() {
        val destination = WorkspaceExportDestination(context)
        destination.remember(tree)
        resolver.releasePersistableUriPermission(tree, flags)
        assertNull(destination.current())
        assertNull(WorkspaceExportDestination(context).current())
    }

    @Test fun readOnlyPermissionCannotBeUsedForDownloads() {
        val destination = WorkspaceExportDestination(context)
        destination.remember(tree)
        resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(destination.current())
    }

    @Test fun changingFolderIsSharedAndAnOldFailedDownloadCannotClearTheNewSelection() {
        val destination = WorkspaceExportDestination(context)
        destination.remember(tree)
        val replacement = DocumentsContract.buildTreeDocumentUri("export.test", "other")
        WorkspaceExportDestination(context).remember(replacement)
        destination.forget(tree)
        assertEquals(replacement, destination.current())
        // Other features may use the old grant; changing downloads must not revoke it.
        assertTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
    }

    @Test fun restoredPreferenceWithoutAnOsGrantIsIgnored() {
        context.getSharedPreferences("workspace_downloads", Context.MODE_PRIVATE)
            .edit().putString("directory", tree.toString()).commit()
        assertNull(WorkspaceExportDestination(context).current())
    }
}
