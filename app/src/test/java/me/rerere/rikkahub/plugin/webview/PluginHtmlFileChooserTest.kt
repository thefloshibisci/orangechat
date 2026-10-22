package me.rerere.rikkahub.plugin.webview

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebChromeClient.FileChooserParams
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginHtmlFileChooserTest {
    private val contract = PluginHtmlFileChooser()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test @Config(sdk = [33])
    fun imageInputOpensSystemPhotoPickerWithMultipleSelection() {
        val intent = contract.createIntent(context,
            PluginHtmlFileRequest(arrayOf("image/*"), FileChooserParams.MODE_OPEN_MULTIPLE))
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals(MediaStore.getPickImagesMaxLimit(), intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1))
    }

    @Test @Config(sdk = [33])
    fun singleImageInputOpensSinglePhotoPicker() {
        val intent = contract.createIntent(context,
            PluginHtmlFileRequest(arrayOf("image/png"), FileChooserParams.MODE_OPEN))
        assertEquals(MediaStore.ACTION_PICK_IMAGES, intent.action)
        assertEquals("image/png", intent.type)
        assertFalse(intent.hasExtra(MediaStore.EXTRA_PICK_IMAGES_MAX))
    }

    @Test fun olderDeviceRetainsMultiImageFallback() {
        val intent = contract.createIntent(context,
            PluginHtmlFileRequest(arrayOf("image/*"), FileChooserParams.MODE_OPEN_MULTIPLE))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test fun multipleInputEnablesNativeMultiSelectionAndRetainsMimeTypes() {
        val intent = contract.createIntent(context,
            PluginHtmlFileRequest(arrayOf("image/png", "image/jpeg"), FileChooserParams.MODE_OPEN_MULTIPLE))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(arrayOf("image/png", "image/jpeg"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test fun ordinaryInputRemainsSingleAndEmptyAcceptUsesAllFiles() {
        val intent = contract.createIntent(context, PluginHtmlFileRequest(arrayOf(""), FileChooserParams.MODE_OPEN))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertArrayEquals(arrayOf("*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test fun allSelectedUrisReturnInOrderWithoutDroppingLaterImages() {
        val first = Uri.parse("content://images/1")
        val second = Uri.parse("content://images/2")
        val third = Uri.parse("content://images/3")
        val clips = ClipData.newRawUri("images", first).apply {
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(third))
        }
        assertArrayEquals(arrayOf(first, second, third), contract.parseResult(Activity.RESULT_OK,
            Intent().apply { clipData = clips }))
        assertArrayEquals(arrayOf(first), contract.parseResult(Activity.RESULT_OK, Intent().setData(first)))
    }

    @Test fun cancellingOrEmptyResultsCompleteWithNull() {
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(Uri.parse("content://images/1"))))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
    }
}
