package me.rerere.rikkahub.ui.components.workspace

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class WorkspaceFileExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: ExportDocumentsProvider
    private val tree = DocumentsContract.buildTreeDocumentUri("export.test", "root")

    @Before fun setup() {
        provider = ExportDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "export.test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("export.test", provider)
    }

    @Test fun duplicateNamesKeepOriginalAndProduceNumberedCopies() = runBlocking {
        provider.seed("report.zip", "original")
        provider.seed("report (1).zip", "first copy")
        val name = exportNewWorkspaceDocument(context.contentResolver, tree, "report.zip", 3) {
            it.write(byteArrayOf(1, 2, 3))
        }
        assertEquals("report (2).zip", name)
        assertEquals("original", provider.files.getValue("report.zip").readText())
        assertEquals("first copy", provider.files.getValue("report (1).zip").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3), provider.files.getValue(name).readBytes())
    }

    @Test fun partialWriteIsReportedAndNewDocumentIsRemoved() = runBlocking {
        val failure = runCatching {
            exportNewWorkspaceDocument(context.contentResolver, tree, "report.zip", 8) { it.write(1) }
        }.exceptionOrNull()
        assertEquals("文件写入不完整，请重试", failure?.message)
        assertTrue(provider.files.isEmpty())
    }

    @Test fun outputFailureIsReportedAndOriginalIsUntouched() = runBlocking {
        provider.seed("report.zip", "original")
        val failure = runCatching {
            exportNewWorkspaceDocument(context.contentResolver, tree, "report.zip", 8) {
                it.write(1)
                throw IOException("storage full")
            }
        }.exceptionOrNull()
        assertEquals("storage full", failure?.message)
        assertEquals(setOf("report.zip"), provider.files.keys)
        assertEquals("original", provider.files.getValue("report.zip").readText())
    }

    @Test fun misbehavingProviderCannotReturnAnExistingDocumentForOverwrite() = runBlocking {
        provider.seed("report.zip", "original")
        provider.returnExisting = true
        var wrote = false
        val failure = runCatching {
            exportNewWorkspaceDocument(context.contentResolver, tree, "report.zip", 0) { wrote = true }
        }.exceptionOrNull()
        assertEquals("文件服务返回了已有文件，已停止以防覆盖", failure?.message)
        assertFalse(wrote)
        assertEquals("original", provider.files.getValue("report.zip").readText())
    }

    @Test fun namesWithoutExtensionsAndHiddenFilesAreNumbered() {
        assertEquals("notes (1)", availableExportName("notes", setOf("notes")))
        assertEquals(".env (1)", availableExportName(".env", setOf(".env")))
        assertEquals("new.zip", availableExportName("new.zip", setOf("other.zip")))
    }
}

class ExportDocumentsProvider : DocumentsProvider() {
    val files = linkedMapOf<String, File>()
    var returnExisting = false
    override fun onCreate() = true
    fun seed(name: String, text: String) {
        files[name] = File.createTempFile("export-test", ".bin", requireNotNull(context).cacheDir).apply { writeText(text) }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        queryChildDocuments("root", projection, sortOrder = null)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return MatrixCursor(columns).apply {
            files.keys.forEach { name -> addRow(columns.map { name }) }
        }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (returnExisting) return files.keys.first()
        check(displayName !in files)
        seed(displayName, "")
        return displayName
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" && documentId in files
    override fun deleteDocument(documentId: String) { files.remove(documentId)?.delete() }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(documentId), ParcelFileDescriptor.parseMode(mode))
}
