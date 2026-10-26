package me.rerere.rikkahub.ui.components.charts

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ChartImageExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: ChartGalleryProvider
    private lateinit var encoded: File

    @Before fun setup() {
        provider = ChartGalleryProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        encoded = File.createTempFile("encoded-chart", ".png", context.cacheDir).apply {
            writeBytes(ByteArray(24_113) { (it % 251).toByte() })
        }
    }

    @Test fun publishesOnlyAfterExactEncodedBytesAreCopied() = runBlocking {
        publishChartPng(context.contentResolver, encoded, "chart.png")
        assertArrayEquals(encoded.readBytes(), provider.file.readBytes())
        assertArrayEquals(encoded.readBytes(), provider.bytesAtPublish)
        assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals("image/png", provider.inserted!!.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("chart.png", provider.inserted!!.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals(listOf("insert", "open", "publish"), provider.events)
        assertFalse(provider.deleted)
    }

    @Test fun insertFailureIsReportedWithoutTryingToShareOrWrite() = runBlocking {
        provider.rejectInsert = true
        val failure = runCatching { publishChartPng(context.contentResolver, encoded, "chart.png") }.exceptionOrNull()
        assertEquals("无法创建相册图片", failure?.message)
        assertEquals(listOf("insert"), provider.events)
    }

    @Test fun outputOpenFailureRemovesPendingRowAndPropagatesError() = runBlocking {
        provider.rejectOpen = true
        val failure = runCatching { publishChartPng(context.contentResolver, encoded, "chart.png") }.exceptionOrNull()
        assertTrue(failure is FileNotFoundException)
        assertTrue(provider.deleted)
        assertFalse(provider.file.exists())
        assertFalse(provider.events.contains("publish"))
    }

    @Test fun missingEncodedFileRemovesPendingRow() = runBlocking {
        assertTrue(encoded.delete())
        val failure = runCatching { publishChartPng(context.contentResolver, encoded, "chart.png") }.exceptionOrNull()
        assertTrue(failure is FileNotFoundException)
        assertEquals(listOf("insert", "delete"), provider.events)
        assertTrue(provider.deleted)
    }

    @Test fun failedPublicationRemovesCompletedButUnpublishedImage() = runBlocking {
        provider.rejectPublish = true
        val failure = runCatching { publishChartPng(context.contentResolver, encoded, "chart.png") }.exceptionOrNull()
        assertEquals("相册图片保存未完成", failure?.message)
        assertArrayEquals(encoded.readBytes(), provider.bytesAtPublish)
        assertTrue(provider.deleted)
        assertFalse(provider.file.exists())
    }

    @Test fun cancellationBeforeCopyRemovesPendingImageAndNeverPublishes() = runBlocking {
        var failure: Throwable? = null
        supervisorScope {
            val child = launch {
                val job = currentCoroutineContext()[Job]!!
                provider.onOpen = { job.cancel(CancellationException("export cancelled")) }
                try {
                    publishChartPng(context.contentResolver, encoded, "chart.png")
                } catch (error: CancellationException) {
                    failure = error
                    throw error
                }
            }
            child.join()
            assertTrue(child.isCancelled)
        }
        assertNotNull(failure)
        assertEquals(listOf("insert", "open", "delete"), provider.events)
        assertTrue(provider.deleted)
    }

    @Test fun cleanupFailureDoesNotHideTheOriginalSaveFailure() = runBlocking {
        provider.rejectOpen = true
        provider.rejectDelete = true
        val failure = runCatching { publishChartPng(context.contentResolver, encoded, "chart.png") }.exceptionOrNull()
        assertTrue(failure is FileNotFoundException)
        assertEquals(1, failure!!.suppressed.size)
        assertEquals("未能清理未完成的相册图片", failure.suppressed.single().message)
    }

    @Test fun legacyPermissionGateBlocksUntilPermissionIsGranted() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(context).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            assertFalse(ensureChartExportPermission(activity))
            shadowOf(context).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            assertTrue(ensureChartExportPermission(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

class ChartGalleryProvider : ContentProvider() {
    lateinit var file: File
    var inserted: ContentValues? = null
    var bytesAtPublish: ByteArray? = null
    val events = mutableListOf<String>()
    var rejectInsert = false
    var rejectOpen = false
    var rejectPublish = false
    var rejectDelete = false
    var deleted = false
    var onOpen: (() -> Unit)? = null

    override fun onCreate(): Boolean {
        file = File.createTempFile("gallery-chart", ".png", requireNotNull(context).cacheDir)
        return true
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        events += "insert"
        inserted = values?.let { ContentValues(it) }
        return if (rejectInsert) null else Uri.parse("content://media/external/images/media/42")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        events += "open"
        check(inserted?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 1)
        if (rejectOpen) throw FileNotFoundException("gallery storage unavailable")
        onOpen?.invoke()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        events += "publish"
        check(values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0)
        bytesAtPublish = file.readBytes()
        return if (rejectPublish) 0 else 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        events += "delete"
        if (rejectDelete) return 0
        deleted = true
        file.delete()
        return 1
    }
    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
}
